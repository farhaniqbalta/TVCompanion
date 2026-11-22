package id.poncol.vssprobe;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;

/**
 * Throwaway feasibility probe for vendor.mediatek.tv.mtkmtalservice@1.0::IMtkVssService.
 * Always checks reachability (ping, interfaceDescriptor), then runs the comma-separated
 * commands from the "cmds" extra, one HIDL call each. Results go to logcat tag VssProbe.
 *
 *   am broadcast -n id.poncol.vssprobe27/id.poncol.vssprobe.Main --es cmds "info,init,info"
 *
 * A receiver (no UI) so the video playing in the foreground is not paused.
 *
 * Transaction codes follow .hal order (see docs/research/ambilight-capture-bottleneck.md §6.1).
 */
public class Main extends BroadcastReceiver {
    private static final String TAG = "VssProbe";
    private static final String FQ = "vendor.mediatek.tv.mtkmtalservice@1.0::IMtkVssService";
    private static final String IBASE = "android.hidl.base@1.0::IBase";
    private static final int PING = 0x0f504e47; // HIDL base: B_PACK_CHARS(0x0f,'P','N','G')
    private static final int DESCRIPTOR = 0x0f445343;

    private Class<?> parcelClass;
    private Object binder;
    private Method transact;

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.i(TAG, "start pkg=" + context.getPackageName()
                + " targetSdk=" + context.getApplicationInfo().targetSdkVersion);
        try {
            parcelClass = Class.forName("android.os.HwParcel");
            Class<?> hwBinder = Class.forName("android.os.HwBinder");
            transact = Class.forName("android.os.IHwBinder")
                    .getMethod("transact", int.class, parcelClass, parcelClass, int.class);
            binder = hwBinder.getMethod("getService", String.class, String.class)
                    .invoke(null, FQ, "default");
            Log.i(TAG, "getService -> " + binder);

            call(PING, IBASE);
            Object rep = call(DESCRIPTOR, IBASE);
            Log.i(TAG, "RESULT reachable: " + p(rep, "readString"));

            String cmds = intent.getStringExtra("cmds");
            if (cmds != null) for (String cmd : cmds.split(",")) run(cmd.trim());
        } catch (Throwable t) {
            Throwable cause = t instanceof InvocationTargetException ? t.getCause() : t;
            Log.e(TAG, "RESULT blocked: " + cause, cause);
        }
    }

    private void run(String cmd) {
        String[] parts = cmd.split(":");
        String name = parts[0];
        String[] args = parts.length > 1 ? parts[1].split("/") : new String[0];
        long t0 = System.nanoTime();
        try {
            String out;
            switch (name) {
                case "mtalinit": out = intCall(1); break;
                case "init": out = intCall(11); break;
                case "start": out = intCall(7); break;
                case "stop": out = intCall(8); break;
                case "trigger": out = intCall(17); break;
                case "next420": out = intCall(4); break;
                case "connect": out = intCall(15, i(args[0])); break;
                case "disconnect": out = intCall(16, i(args[0])); break;
                case "color": out = intCall(12, i(args[0])); break;
                case "dump": out = intCall(13, i(args[0])); break;
                case "info": out = structCall(18, 24); break;
                case "config": out = scanStruct(6); break;
                case "fb": out = frameBuffer(args); break;
                case "himem": out = himem(); break;
                case "fb420": out = frameBuffer420(i(args[0])); break;
                default: out = "unknown command";
            }
            Log.i(TAG, cmd + " -> " + out + " (" + (System.nanoTime() - t0) / 1000 + " us)");
        } catch (Throwable t) {
            Throwable cause = t instanceof InvocationTargetException ? t.getCause() : t;
            Log.e(TAG, cmd + " failed: " + cause);
        }
    }

    private Object call(int code, String token, Object... int32Args) throws Exception {
        Object req = parcelClass.newInstance();
        Object rep = parcelClass.newInstance();
        p(req, "writeInterfaceToken", token);
        for (Object arg : int32Args) p(req, "writeInt32", arg);
        transact.invoke(binder, code, req, rep, 0);
        p(rep, "verifySuccess");
        return rep;
    }

    /** Methods returning a plain int32 (or nothing: then "void"). */
    private String intCall(int code, Object... int32Args) throws Exception {
        Object rep = call(code, FQ, int32Args);
        try {
            return "ret=" + p(rep, "readInt32");
        } catch (InvocationTargetException e) {
            return "void";
        }
    }

    /** Methods returning (int32 ret, struct). */
    private String structCall(int code, int size) throws Exception {
        Object rep = call(code, FQ);
        int ret = (Integer) p(rep, "readInt32");
        Object blob = parcelClass.getMethod("readBuffer", long.class).invoke(rep, (long) size);
        Method getInt32 = blob.getClass().getMethod("getInt32", long.class);
        StringBuilder words = new StringBuilder();
        for (int off = 0; off < size; off += 4) {
            words.append(' ').append((Integer) getInt32.invoke(blob, (long) off));
        }
        return "ret=" + ret + " words:" + words;
    }

    /** (int32 ret, struct) of unknown size: libhwbinder rejects a mismatched readBuffer, so scan. */
    private String scanStruct(int code) throws Exception {
        for (int size = 4; size <= 1024; size += 4) {
            try {
                return "size=" + size + " " + structCall(code, size);
            } catch (InvocationTargetException e) {
                // wrong size, try the next
            }
        }
        return "no size in 4..1024 matched";
    }

    /** MTVSS_GetframeBuffer(u16 x, u16 y, u16 w, u16 h, u32 n). */
    private String frameBuffer(String[] a) throws Exception {
        Object req = parcelClass.newInstance();
        Object rep = parcelClass.newInstance();
        p(req, "writeInterfaceToken", FQ);
        Method writeInt16 = parcelClass.getMethod("writeInt16", short.class);
        for (int k = 0; k < 4; k++) writeInt16.invoke(req, (short) i(a[k]));
        p(req, "writeInt32", i(a[4]));
        transact.invoke(binder, 19, req, rep, 0);
        p(rep, "verifySuccess");
        try {
            return "ret=" + p(rep, "readInt32");
        } catch (InvocationTargetException e) {
            return "void";
        }
    }

    /** MTVSS_GetframeBuffer420(hidl_memory): passes our own ashmem buffer, then summarizes it. */
    private String frameBuffer420(int size) throws Exception {
        android.os.SharedMemory shm = android.os.SharedMemory.create("vssprobe", size);
        java.nio.ByteBuffer view = shm.mapReadOnly();
        FileDescriptor fd = (FileDescriptor) shm.getClass().getMethod("getFileDescriptor").invoke(shm);
        Class<?> nativeHandle = Class.forName("android.os.NativeHandle");
        Object handle = nativeHandle.getConstructor(FileDescriptor[].class, int[].class, boolean.class)
                .newInstance(new FileDescriptor[] {fd}, new int[0], false);
        Object mem = Class.forName("android.os.HidlMemory")
                .getConstructor(String.class, long.class, nativeHandle)
                .newInstance("ashmem", (long) size, handle);
        Object req = parcelClass.newInstance();
        Object rep = parcelClass.newInstance();
        p(req, "writeInterfaceToken", FQ);
        parcelClass.getMethod("writeHidlMemory", mem.getClass()).invoke(req, mem);
        transact.invoke(binder, 10, req, rep, 0);
        p(rep, "verifySuccess");
        String out;
        try {
            out = "ret=" + p(rep, "readInt32");
        } catch (InvocationTargetException e) {
            out = "void";
        }
        long sum = 0;
        int nonZero = 0;
        for (int k = 0; k < size; k++) {
            int b = view.get(k) & 0xff;
            sum += b;
            if (b != 0) nonZero++;
        }
        android.os.SharedMemory.unmap(view);
        shm.close();
        return out + " buffer avg=" + (sum / size) + " nonzero=" + nonZero;
    }

    /** MTVSS_GetHimemeorybuffer() -> (int32 ret, hidl_memory). Maps it read-only and summarizes. */
    private String himem() throws Exception {
        Object rep = call(20, FQ);
        int ret = (Integer) p(rep, "readInt32");
        Object mem = parcelClass.getMethod("readHidlMemory").invoke(rep);
        String name = (String) mem.getClass().getMethod("getName").invoke(mem);
        long size = (Long) mem.getClass().getMethod("getSize").invoke(mem);
        Object handle = mem.getClass().getMethod("getHandle").invoke(mem);
        String out = "ret=" + ret + " name=" + name + " size=" + size;
        if (handle == null) return out + " handle=null";
        FileDescriptor[] fds = (FileDescriptor[]) handle.getClass()
                .getMethod("getFileDescriptors").invoke(handle);
        int[] ints = (int[]) handle.getClass().getMethod("getInts").invoke(handle);
        out += " fds=" + fds.length + " ints=" + java.util.Arrays.toString(ints);
        if (fds.length == 0 || size <= 0) return out;
        try (FileInputStream in = new FileInputStream(fds[0]);
             FileChannel ch = in.getChannel()) {
            MappedByteBuffer buf = ch.map(FileChannel.MapMode.READ_ONLY, 0, size);
            long sum = 0;
            int nonZero = 0;
            StringBuilder head = new StringBuilder();
            for (int k = 0; k < size; k++) {
                int b = buf.get(k) & 0xff;
                sum += b;
                if (b != 0) nonZero++;
                if (k < 32) head.append(String.format("%02x", b));
            }
            out += " mapped avg=" + (sum / size) + " nonzero=" + nonZero + " head=" + head;
        } catch (Throwable t) {
            out += " map failed: " + t;
        }
        return out;
    }

    private Object p(Object target, String method, Object... args) throws Exception {
        for (Method m : parcelClass.getMethods()) {
            if (m.getName().equals(method) && m.getParameterTypes().length == args.length) {
                return m.invoke(target, args);
            }
        }
        throw new NoSuchMethodException(method);
    }

    private static int i(String s) {
        return (int) Long.decode(s).longValue();
    }
}
