package net.kdt.pojavlaunch.vr;

import android.os.Build;
import android.util.Log;

import com.google.gson.JsonObject;

import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;

import java.io.File;

/**
 * Decides whether the game launches in the immersive VR activity, and holds the settings
 * that differ from the regular (touch/window) path.
 */
public class VRMode {
    /** Size of Minecraft's main framebuffer. It is never presented; Vivecraft renders each eye into OpenXR swapchains. */
    public static final int WINDOW_WIDTH = 1280;
    public static final int WINDOW_HEIGHT = 720;

    /** True in the :game process when it was started by {@link VRGameActivity}. */
    public static boolean sRunningInVR = false;

    public static boolean isMetaHeadset() {
        return "Oculus".equalsIgnoreCase(Build.MANUFACTURER) || "Meta".equalsIgnoreCase(Build.MANUFACTURER);
    }

    /** Whether Play should start the game in VR */
    public static boolean shouldLaunchInVR() {
        return LauncherPreferences.PREF_VR_MODE && VLoader.isAvailable();
    }

    /**
     * Point Vivecraft at its OpenXR provider and start straight in VR, since the immersive activity
     * has nothing to show otherwise. Same values QuestCraft's Pojlib forces (CheckVivecraftConfig).
     * Creates the file on a fresh install; Vivecraft fills in the other settings with defaults.
     */
    public static void applyVivecraftConfig(File gameDir) {
        File config = new File(gameDir, "config/vivecraft-client-config.json");
        try {
            JsonObject obj = config.exists()
                    ? Tools.GLOBAL_GSON.fromJson(Tools.read(config), JsonObject.class)
                    : new JsonObject();
            obj.addProperty("stereoProviderPluginID", "OPENXR");
            obj.addProperty("vrEnabled", "true");
            obj.addProperty("vrHotswitchingEnabled", "false");
            // Its update checker points at upstream Vivecraft, which has no OpenXR/Android support
            obj.addProperty("alwaysShowUpdates", "false");
            obj.addProperty("disableGarbageCollectorMessage", "true");
            obj.addProperty("seated", "false");
            Tools.write(config.getAbsolutePath(), Tools.GLOBAL_GSON.toJson(obj));
        } catch (Exception e) {
            Log.e("VRMode", "Failed to update the Vivecraft config", e);
        }
    }
}
