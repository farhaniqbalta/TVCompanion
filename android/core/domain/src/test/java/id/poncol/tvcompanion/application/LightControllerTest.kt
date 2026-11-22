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
                is LightCommand.Color -> state.copy(whiteMode = false, colorMode = true, color = command.color)
                LightCommand.WhiteMode -> state.copy(whiteMode = true, colorMode = false)
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

    @Test fun colorCommandsRequireConfirmedSupportedStateAndDoNotPublishOptimisticColor() {
        val worker = Worker(); val lamp = Lamp(); val controller = LightController(lamp, { it() }, worker)
        controller.refresh(); worker.complete()
        val requested = LightColor(240, 1000, 500)
        controller.execute(LightCommand.Color(requested)); assertTrue(worker.tasks.isEmpty())
        lamp.state = LightState(true, true, 40, LightColor(0, 1000, 400))
        controller.refresh(); worker.complete()
        controller.execute(LightCommand.Color(requested)); controller.execute(LightCommand.Color(LightColor(120,1000,500)))
        assertEquals(1, worker.tasks.size); assertEquals(0, controller.state.light?.color?.hue)
        worker.complete(); assertEquals(requested, controller.state.light?.color); assertEquals(1, lamp.writes)
        controller.execute(LightCommand.WhiteMode); worker.complete()
        assertTrue(controller.state.light!!.whiteMode); assertEquals(40, controller.state.light?.brightness)
        controller.close()
    }

    @Test fun readThenExecuteWorksWithoutPriorRefresh() {
        val worker = Worker(); val lamp = Lamp(); val c = LightController(lamp, { it() }, worker)
        var result: LightResult? = null
        c.readThenExecute({ LightCommand.Power(false) }) { result = it }
        worker.complete()
        assertEquals(LightOutcome.CONFIRMED, result!!.outcome)
        assertEquals(false, result!!.state.light?.on); assertEquals(1, lamp.reads); assertEquals(1, lamp.writes)
    }
    @Test fun readThenExecuteBuildNullIsUnsupportedWithoutWrite() {
        val worker = Worker(); val lamp = Lamp(); val c = LightController(lamp, { it() }, worker)
        var result: LightResult? = null
        c.readThenExecute({ null }) { result = it }; worker.complete()
        assertEquals(LightOutcome.UNSUPPORTED, result!!.outcome); assertEquals(0, lamp.writes)
        assertNotNull(c.state.light) // fresh read still published
    }
    @Test fun concurrentCallerGetsBusyAndEachDoneFiresOnce() {
        val worker = Worker(); val lamp = Lamp(); val c = LightController(lamp, { it() }, worker)
        val seen = mutableListOf<LightOutcome>()
        c.refresh { seen += it.outcome }
        c.readThenExecute({ LightCommand.Power(false) }) { seen += it.outcome }
        assertEquals(listOf(LightOutcome.BUSY), seen)
        worker.complete()
        assertEquals(listOf(LightOutcome.BUSY, LightOutcome.CONFIRMED), seen)
        assertTrue(worker.tasks.isEmpty())
    }
    @Test fun failedWriteIsUnconfirmedAndUiExecuteWithoutStateIsUnsupported() {
        val worker = Worker(); val lamp = Lamp().apply { failWrite = true }; val c = LightController(lamp, { it() }, worker)
        var ui: LightResult? = null; c.execute(LightCommand.Power(true)) { ui = it }
        assertEquals(LightOutcome.UNSUPPORTED, ui!!.outcome)
        var remote: LightResult? = null; c.readThenExecute({ LightCommand.Power(true) }) { remote = it }; worker.complete()
        assertEquals(LightOutcome.UNCONFIRMED, remote!!.outcome); assertEquals(1, lamp.writes) // no retry
    }
    @Test fun multipleObserversAndCloseHandle() {
        val worker = Worker(); val c = LightController(Lamp(), { it() }, worker)
        var a = 0; var b = 0
        val ha = c.observe { a++ }; c.observe { b++ }
        ha.close(); c.refresh(); worker.complete()
        assertEquals(1, a); assertEquals(3, b) // initial + busy + result
    }
    @Test fun afterCloseEveryCallCompletesClosed() {
        val c = LightController(Lamp(), { it() }, Worker()); c.close()
        var r: LightResult? = null; c.readThenExecute({ LightCommand.Power(true) }) { r = it }
        assertEquals(LightOutcome.CLOSED, r!!.outcome)
    }
    @Test fun closingObserverDoesNotCloseControllerOrDropDone() {
        val worker = Worker(); val lamp = Lamp(); val c = LightController(lamp, { it() }, worker)
        val screen = c.observe { }
        var r: LightResult? = null
        c.readThenExecute({ LightCommand.Power(false) }) { r = it }
        screen.close(); worker.complete()
        assertEquals(LightOutcome.CONFIRMED, r!!.outcome); assertEquals(0, lamp.closes); assertFalse(worker.stopped)
    }
}
