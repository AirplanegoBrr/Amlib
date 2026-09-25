package net.kdt.pojavlaunch;

import android.app.Application;
import android.content.Context;

import androidx.preference.PreferenceManager;

import net.kdt.pojavlaunch.lifecycle.ContextExecutor;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.tasks.AsyncAssetManager;

/**
 * Setup Amlib needs once per process before anything else runs. The phone app gets it from
 * PojavApplication; an app that embeds Amlib as a library (AmethystXR-Menu) calls it through
 * XRApi.init(), and Amlib's own activities in other processes (VRGameActivity in :game) call it too.
 */
public final class AmlibCore {
    private static boolean sInitialized;

    private AmlibCore() {}

    public static synchronized void init(Context context) {
        if (sInitialized) return;
        sInitialized = true;
        Application application = (Application) context.getApplicationContext();
        ContextExecutor.setApplication(application);
        // The phone app's first activity sets this up as a side effect (LocaleUtils); an app
        // embedding Amlib doesn't go through it
        if (LauncherPreferences.DEFAULT_PREF == null) {
            LauncherPreferences.DEFAULT_PREF = PreferenceManager.getDefaultSharedPreferences(application);
        }
        if (Tools.checkStorageRoot(application)) {
            // Implicitly initializes early constants and storage constants.
            // Required to run the main activity properly.
            LauncherPreferences.loadPreferences(application);
        } else {
            // In other cases, only initialize enough for the basicmost basics to work
            // and not explode.
            Tools.initEarlyConstants(application);
        }
        Tools.DEVICE_ARCHITECTURE = Architecture.getDeviceArchitecture();
        //Force x86 lib directory for Asus x86 based zenfones
        if (Architecture.isx86Device() && Architecture.is32BitsDevice()) {
            String originalJNIDirectory = application.getApplicationInfo().nativeLibraryDir;
            application.getApplicationInfo().nativeLibraryDir = originalJNIDirectory.substring(0,
                    originalJNIDirectory.lastIndexOf("/"))
                    .concat("/x86");
        }
        AsyncAssetManager.unpackRuntime(application.getAssets());
    }
}
