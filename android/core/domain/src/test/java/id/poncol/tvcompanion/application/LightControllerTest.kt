package id.poncol.tvcompanion.application

import id.poncol.tvcompanion.domain.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit

class LightControllerTest {
    private class Worker : AbstractExecutorService() {
        val tasks = ArrayDeque<Runnable>()
        var stopped = false
        override fun execute(task: Runnable) { tasks.addLast(task) }
        fun complete() { tasks.removeFirst().run() }
        override fun shutdown() { stopped = true }
        override fun shutdownNow(): MutableList<Runnable> { stopped = true; return tasks.toMutableList().also { tasks.clear() } }
        override fun isShutdown() = stopped
        override fun isTerminated() = stopped && tasks.isEmpty()
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = isTerminated
    }
    private class Lamp : LightGateway {
        var state = LightState(true, true, 40)
        var closes = 0
        override fun close() { closes++ }
        var reads = 0
        var writes = 0
        var failWrite = false
        override fun read(): LightState { reads++; return state }
        override fun execute(command: LightCommand): LightState {
            writes++
            if (failWrite) error("Synthetic protocol failure")
            state = when (command) {
                is LightCommand.Power -> state.copy(on = command.on)
                is LightCommand.Brightness -> state.copy(brightness = command.percent)
            }
            return state
        }
    }
    @Test fun noDeviceWorkUntilRequestedAndOnlyOnePendingOperation() {
        val worker = Worker(); val lamp = Lamp()
        val controller = LightController(lamp, { it() }, worker)
        assertEquals(0, lamp.reads)
        controller.execute(LightCommand.Power(false))
        assertTrue(worker.tasks.isEmpty()) // A write requires confirmed state first.
        controller.refresh(); controller.refresh()
        assertEquals(1, worker.tasks.size)
        assertTrue(controller.state.busy)
        worker.complete()
        assertEquals(1, lamp.reads)
        assertEquals(40, controller.state.light?.brightness)
        assertFalse(controller.state.busy)
        assertTrue(worker.tasks.isEmpty()) // No recurring refresh scheduled.
        controller.close()
    }
    @Test fun unconfirmedWriteClearsActionableStateAndDoesNotRetry() {
        val worker = Worker(); val lamp = Lamp()
        val controller = LightController(lamp, { it() }, worker)
        controller.refresh(); worker.complete()
        lamp.failWrite = true
        controller.execute(LightCommand.Power(false)); worker.complete()
        assertNull(controller.state.light)
        assertTrue(controller.state.message.contains("may have applied"))
        controller.execute(LightCommand.Power(false))
        assertEquals(1, lamp.writes)
        assertTrue(worker.tasks.isEmpty())
        controller.close()
    }
    @Test fun brightnessRequiresWhiteModeAndPower() {
        val worker = Worker(); val lamp = Lamp()
        val controller = LightController(lamp, { it() }, worker)
        lamp.state = LightState(true, false, 40)
        controller.refresh(); worker.complete()
        controller.execute(LightCommand.Brightness(50))
        assertTrue(worker.tasks.isEmpty())
        lamp.state = LightState(false, true, 40)
        controller.refresh(); worker.complete()
        controller.execute(LightCommand.Brightness(50))
        assertTrue(worker.tasks.isEmpty())
        assertEquals(0, lamp.writes)
        controller.close()
    }
    @Test fun closeDropsPendingUiResultsAndReleasesObserver() {
        val worker = Worker(); val lamp = Lamp()
        val callbacks = ArrayDeque<() -> Unit>()
        val controller = LightController(lamp, { callbacks.addLast(it) }, worker)
        var renders = 0
        controller.observe { renders++ }
        controller.refresh(); worker.complete()
        val before = renders
        controller.close()
        callbacks.removeFirst().invoke()
        controller.refresh()
        assertEquals(before, renders)
        assertEquals(1, lamp.closes)
        controller.close()
        assertEquals(1, lamp.closes)
        assertTrue(worker.isShutdown)
        assertTrue(worker.tasks.isEmpty())
    }
    @Test fun writePublishesOnlyConfirmedGatewayResult() {
        val worker = Worker(); val lamp = Lamp()
        val controller = LightController(lamp, { it() }, worker)
        controller.refresh(); worker.complete()
        controller.execute(LightCommand.Power(false))
        assertEquals(true, controller.state.light?.on)
        assertTrue(controller.state.busy)
        worker.complete()
        assertEquals(false, controller.state.light?.on)
        assertEquals(1, lamp.writes)
        controller.close()
    }
    @Test(expected = IllegalArgumentException::class)
    fun brightnessRejectsOutOfRangeInput() { LightCommand.Brightness(101) }
}
