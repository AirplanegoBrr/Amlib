package net.kdt.pojavlaunch.vr;

import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.fragment.app.FragmentActivity;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleEventObserver;
import androidx.lifecycle.LifecycleOwner;

import com.google.gson.JsonObject;

import net.kdt.pojavlaunch.JMinecraftVersionList;
import net.kdt.pojavlaunch.Logger;
import net.kdt.pojavlaunch.LwjglGlfwKeycode;
import net.kdt.pojavlaunch.PojavProfile;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.lifecycle.ContextExecutor;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.utils.JREUtils;
import net.kdt.pojavlaunch.utils.MCOptionUtils;
import net.kdt.pojavlaunch.value.MinecraftAccount;
import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;

import org.lwjgl.glfw.CallbackBridge;

import java.io.File;
import java.io.IOException;

/**
 * Decides whether the game launches in the immersive VR activity, and holds the settings
 * that differ from the regular (touch/window) path.
 */
public class VRMode {
    /** Size of Minecraft's main framebuffer. It is never presented; Vivecraft renders each eye into OpenXR swapchains. */
    public static final int WINDOW_WIDTH = 1280;
    public static final int WINDOW_HEIGHT = 720;

    /** The renderer QuestCraft uses; Vivecraft needs an OpenGL ES context for XR_KHR_opengl_es_enable */
    private static final String VR_RENDERER = "opengles_mobileglues";

    /** True once this process runs (or is about to run) the game in VR */
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

    /**
     * Runs the game in VR inside the given activity's process and blocks until it exits (the
     * process then exits too). Vivecraft opens its OpenXR session on this activity, so an app that
     * embeds Amlib can keep its own VR scene up while the game loads and hand the headset over
     * when {@link VLoader#gameReady} turns true, like QuestCraft's wrapper does.
     */
    public static void runGame(FragmentActivity activity, MinecraftProfile profile, String versionId) throws Throwable {
        sRunningInVR = true;
        try {
            File latestLogFile = new File(Tools.DIR_GAME_HOME, "latestlog.txt");
            if (!latestLogFile.exists() && !latestLogFile.createNewFile())
                throw new IOException("Failed to create a new log file");
            Logger.begin(latestLogFile.getAbsolutePath());
        } catch (IOException e) {
            Log.e("VRMode", "Failed to open the game log", e);
        }

        // Give Minecraft a fixed-size main window; it is never shown, only the eye buffers are
        CallbackBridge.windowWidth = CallbackBridge.physicalWidth = WINDOW_WIDTH;
        CallbackBridge.windowHeight = CallbackBridge.physicalHeight = WINDOW_HEIGHT;
        MCOptionUtils.load(Tools.getGameDirPath(profile).getAbsolutePath());
        MCOptionUtils.set("fullscreen", "false");
        MCOptionUtils.set("overrideWidth", String.valueOf(WINDOW_WIDTH));
        MCOptionUtils.set("overrideHeight", String.valueOf(WINDOW_HEIGHT));
        MCOptionUtils.save();

        // Set the activity for the executor, so Tools.showErrorRemote() and friends find it
        activity.runOnUiThread(() -> {
            ContextExecutor.setActivity(activity);
            activity.getLifecycle().addObserver(new WindowStateObserver());
        });

        JMinecraftVersionList.Version versionInfo = Tools.getVersionInfo(versionId);
        CallbackBridge.nativeSetUseInputStackQueue(versionInfo.arguments != null);
        if (!VR_RENDERER.equals(Tools.LOCAL_RENDERER)) {
            Log.i("VRMode", "VR uses " + VR_RENDERER + " instead of the profile renderer " + profile.pojavRendererName);
        }
        Tools.LOCAL_RENDERER = VR_RENDERER;
        LauncherPreferences.writeMGRendererSettings();

        MinecraftAccount account = PojavProfile.getCurrentProfileContent(activity, null);
        Logger.appendToLog("--------- Starting game in VR with Launcher Debug!");
        Tools.printLauncherInfo(versionId, Tools.isValidString(profile.javaArgs) ? profile.javaArgs : LauncherPreferences.PREF_CUSTOM_JAVA_ARGS, Tools.getTotalDeviceMemory(activity));
        JREUtils.redirectAndPrintJRELog();
        LauncherProfiles.load();
        int requiredJavaVersion = versionInfo.javaVersion != null ? versionInfo.javaVersion.majorVersion : 8;
        Tools.launchMinecraft(activity, account, profile, versionId, requiredJavaVersion);
    }

    /** Tells the game when the activity it runs in is shown, focused or paused (Meta button, headset off) */
    private static class WindowStateObserver implements LifecycleEventObserver {
        @Override
        public void onStateChanged(@NonNull LifecycleOwner owner, @NonNull Lifecycle.Event event) {
            switch (event) {
                case ON_START: setWindowAttrib(LwjglGlfwKeycode.GLFW_VISIBLE, 1); break;
                case ON_STOP: setWindowAttrib(LwjglGlfwKeycode.GLFW_VISIBLE, 0); break;
                case ON_RESUME:
                    setWindowAttrib(LwjglGlfwKeycode.GLFW_FOCUSED, 1);
                    setWindowAttrib(LwjglGlfwKeycode.GLFW_HOVERED, 1);
                    break;
                case ON_PAUSE:
                    setWindowAttrib(LwjglGlfwKeycode.GLFW_FOCUSED, 0);
                    setWindowAttrib(LwjglGlfwKeycode.GLFW_HOVERED, 0);
                    break;
            }
        }

        private static void setWindowAttrib(int attrib, int value) {
            CallbackBridge.nativeSetWindowAttrib(attrib, value);
        }
    }
}
