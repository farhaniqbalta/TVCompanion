package id.poncol.vssprobe;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import java.lang.reflect.Method;

/**
 * Throwaway feasibility probe: can an unprivileged app reach
 * vendor.mediatek.tv.mtkmtalservice@1.0::IMtkVssService over hwbinder?
 * Calls only HIDL base methods (ping, interfaceDescriptor); no capture calls.
 * Results go to logcat tag VssProbe.
 */
public class Main extends Activity {
    private static final String TAG = "VssProbe";
    private static final String FQ = "vendor.mediatek.tv.mtkmtalservice@1.0::IMtkVssService";
    private static final String IBASE = "android.hidl.base@1.0::IBase";
    // HIDL base transaction codes: B_PACK_CHARS(0x0f, ...)
    private static final int PING = 0x0f504e47;
    private static final int DESCRIPTOR = 0x0f445343;

    private static final String VSS = FQ;

    private static void readStruct(Object binder, Method transact, Method token, Method status,
                                   Class<?> hwParcel, int code, String name) throws Exception {
        Method readInt32 = hwParcel.getMethod("readInt32");
        Method readBuffer = hwParcel.getMethod("readBuffer", long.class);
        for (int size = 4; size <= 1024; size += 4) {
            Object req = hwParcel.newInstance();
            Object rep = hwParcel.newInstance();
            token.invoke(req, VSS);
            transact.invoke(binder, code, req, rep, 0);
            status.invoke(rep);
            int ret = (Integer) readInt32.invoke(rep);
            Object blob;
            try {
                blob = readBuffer.invoke(rep, (long) size);
            } catch (java.lang.reflect.InvocationTargetException e) {
                continue;
            }
            Method getInt32 = blob.getClass().getMethod("getInt32", long.class);
            StringBuilder words = new StringBuilder();
            for (int off = 0; off < size; off += 4) {
                words.append(String.format(" %08x", (Integer) getInt32.invoke(blob, (long) off)));
            }
            Log.i(TAG, name + " ret=" + ret + " size=" + size + " words:" + words);
            return;
        }
        Log.w(TAG, name + " no struct size in 4..1024 matched");
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Log.i(TAG, "start pkg=" + getPackageName()
                + " targetSdk=" + getApplicationInfo().targetSdkVersion);
        try {
            Class<?> hwBinder = Class.forName("android.os.HwBinder");
            Class<?> iHwBinder = Class.forName("android.os.IHwBinder");
            Class<?> hwParcel = Class.forName("android.os.HwParcel");
            Log.i(TAG, "classes ok");

            Method getService = hwBinder.getMethod("getService", String.class, String.class);
            Object binder = getService.invoke(null, FQ, "default");
            Log.i(TAG, "getService -> " + binder);
            if (binder == null) return;

            Method transact = iHwBinder.getMethod("transact", int.class, hwParcel, hwParcel, int.class);
            Method token = hwParcel.getMethod("writeInterfaceToken", String.class);
            Method status = hwParcel.getMethod("verifySuccess");
            Method readString = hwParcel.getMethod("readString");

            Object req = hwParcel.newInstance();
            Object rep = hwParcel.newInstance();
            token.invoke(req, IBASE);
            transact.invoke(binder, PING, req, rep, 0);
            status.invoke(rep);
            Log.i(TAG, "ping ok");

            req = hwParcel.newInstance();
            rep = hwParcel.newInstance();
            token.invoke(req, IBASE);
            transact.invoke(binder, DESCRIPTOR, req, rep, 0);
            status.invoke(rep);
            Log.i(TAG, "descriptor -> " + readString.invoke(rep));
            Log.i(TAG, "RESULT reachable");
            // Phase 2: read-only getters. Reply = status, int32 ret, struct as one buffer.
            // HIDL rejects a readBuffer whose size differs, so scan sizes to find the struct size.
            readStruct(binder, transact, token, status, hwParcel, 18, "GetVssVideoInform");
            readStruct(binder, transact, token, status, hwParcel, 6, "GetConfig");
        } catch (Throwable t) {
            Throwable cause = t.getCause() != null ? t.getCause() : t;
            Log.e(TAG, "RESULT blocked: " + cause, cause);
        } finally {
            finish();
        }
    }
}
