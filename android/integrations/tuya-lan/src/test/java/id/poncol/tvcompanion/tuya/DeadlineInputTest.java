package id.poncol.tvcompanion.tuya;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

public class DeadlineInputTest {
    private static final class TestSocket extends Socket {
        final List<Integer> timeouts = new ArrayList<>();
        @Override public void setSoTimeout(int timeout) { timeouts.add(timeout); }
    }
    @Test public void slowFragmentsCannotExtendAbsoluteDeadline() throws Exception {
        AtomicLong now = new AtomicLong();
        TestSocket socket = new TestSocket();
        InputStream fragments = new InputStream() {
            @Override public int read() { return 1; }
            @Override public int read(byte[] b, int offset, int length) {
                now.addAndGet(600_000_000L); b[offset] = 1; return 1;
            }
        };
        try {
            DeadlineInput.readFully(socket, fragments, new byte[18], 1_500_000_000L, now::get);
            fail("Fragmented header exceeded session deadline");
        } catch (SocketTimeoutException expected) {}
        assertEquals(Arrays.asList(1500, 900, 300), socket.timeouts);
    }
    @Test public void bodySharesTheHeadersRemainingDeadline() throws Exception {
        AtomicLong now = new AtomicLong();
        TestSocket socket = new TestSocket();
        DeadlineInput.readFully(socket, new ByteArrayInputStream(new byte[18]), new byte[18], 1_000_000_000L, now::get);
        now.set(900_000_000L);
        InputStream body = new ByteArrayInputStream(new byte[32]) {
            @Override public synchronized int read(byte[] b, int off, int len) {
                now.addAndGet(150_000_000L); return super.read(b, off, len);
            }
        };
        try { DeadlineInput.readFully(socket, body, new byte[32], 1_000_000_000L, now::get); fail(); }
        catch (SocketTimeoutException expected) {}
        assertEquals(Integer.valueOf(100), socket.timeouts.get(socket.timeouts.size() - 1));
    }
    @Test public void interruptedReadDoesNotConsumeInputOrClearInterrupt() throws Exception {
        ByteArrayInputStream input = new ByteArrayInputStream(new byte[4]);
        Thread.currentThread().interrupt();
        try {
            try { DeadlineInput.readFully(new TestSocket(), input, new byte[4], Long.MAX_VALUE, () -> 0L); fail(); }
            catch (InterruptedIOException expected) {}
            assertEquals(4, input.available());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }
    @Test public void eofIsRejectedRatherThanDecryptingAnIncompleteFrame() throws Exception {
        try { DeadlineInput.readFully(new TestSocket(), new ByteArrayInputStream(new byte[2]), new byte[18], Long.MAX_VALUE, () -> 0L); fail(); }
        catch (EOFException expected) {}
    }
}
