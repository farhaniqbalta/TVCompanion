package id.poncol.tvcompanion.tuya;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.function.LongSupplier;

/** Absolute session deadline, enforced for every fragment rather than every frame. */
final class DeadlineInput {
    static void check(long deadline, LongSupplier clock) throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Operation cancelled");
        if (deadline - clock.getAsLong() <= 0) throw new SocketTimeoutException("Session deadline");
    }
    static void readFully(Socket socket, InputStream input, byte[] target, long deadline, LongSupplier clock) throws IOException {
        int offset = 0;
        while (offset < target.length) {
            check(deadline, clock);
            long left = deadline - clock.getAsLong();
            if (left <= 0) throw new SocketTimeoutException("Session deadline");
            long millis = left / 1_000_000L + (left % 1_000_000L == 0 ? 0 : 1);
            socket.setSoTimeout((int)Math.min(4000L, millis));
            int read = input.read(target, offset, target.length - offset);
            check(deadline, clock);
            if (read < 0) throw new EOFException("Incomplete frame");
            if (read == 0) throw new IOException("Empty read");
            offset += read;
        }
    }
    private DeadlineInput() {}
}
