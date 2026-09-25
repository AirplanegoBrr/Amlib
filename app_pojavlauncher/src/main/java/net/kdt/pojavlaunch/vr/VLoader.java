package net.kdt.pojavlaunch.vr;

import android.content.Context;
import android.util.Log;

/**
 * Android-side half of the Vivecraft OpenXR bridge (libvloader).
 * The game-side half is Vivecraft's own org.vivecraft.util.VLoader, which reads the
 * activity handed over here and flips {@link #gameReady} once its XR session is up.
 */
public class VLoader {
    private static final boolean sLoaded = loadNative();

    /** Set from native code (by field name) when Vivecraft finishes Android setup. */
    public static volatile boolean gameReady = false;

    private static boolean loadNative() {
        try {
            // Pulls in libopenxr_loader.so as a dependency
            System.loadLibrary("vloader");
            return true;
        } catch (UnsatisfiedLinkError e) {
            // Only built for arm64-v8a
            Log.i("VLoader", "libvloader unavailable, VR bridge disabled: " + e.getMessage());
            return false;
        }
    }

    public static boolean isAvailable() {
        return sLoaded;
    }

    public static void setAndroidInitInfo(Context ctx) {
        if (!sLoaded) return;
        gameReady = false;
        nativeSetAndroidInitInfo(ctx);
    }

    /**
     * Calls one of the host app's OpenXR functions that take a single handle, such as
     * xrRequestExitSession, xrDestroySession or xrDestroyInstance. The host looks up the function
     * pointer with its own loader (Godot: OpenXRAPIExtension.get_instance_proc_addr), since its
     * handles belong to that loader. Used to release the headset before Vivecraft takes over.
     * @return the XrResult, or -1 when the VR bridge isn't available
     */
    public static int callHostXr(long function, long handle) {
        if (!sLoaded || function == 0) return -1;
        return nativeCallHostXr(function, handle);
    }

    private static native void nativeSetAndroidInitInfo(Context ctx);
    private static native int nativeCallHostXr(long function, long handle);
}
