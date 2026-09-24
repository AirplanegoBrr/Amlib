package net.kdt.pojavlaunch.vr;

import static net.kdt.pojavlaunch.MainActivity.INTENT_MINECRAFT_VERSION;

import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Color;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

import androidx.core.content.ContextCompat;

import net.kdt.pojavlaunch.BaseActivity;
import net.kdt.pojavlaunch.JMinecraftVersionList;
import net.kdt.pojavlaunch.Logger;
import net.kdt.pojavlaunch.LwjglGlfwKeycode;
import net.kdt.pojavlaunch.PojavProfile;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.customcontrols.gamepad.DefaultDataProvider;
import net.kdt.pojavlaunch.customcontrols.gamepad.Gamepad;
import net.kdt.pojavlaunch.lifecycle.ContextExecutor;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.services.GameService;
import net.kdt.pojavlaunch.utils.JREUtils;
import net.kdt.pojavlaunch.utils.MCOptionUtils;
import net.kdt.pojavlaunch.value.MinecraftAccount;
import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;

import org.lwjgl.glfw.CallbackBridge;

import java.io.File;
import java.io.IOException;

import fr.spse.gamepad_remapper.GamepadHandler;
import fr.spse.gamepad_remapper.RemapperManager;
import fr.spse.gamepad_remapper.RemapperView;

/**
 * Runs the game as an immersive OpenXR app. Unlike MainActivity there is no render surface or
 * touch controls: the game renders headless (ctxbridges/xr_bridge.c) and Vivecraft presents
 * through OpenXR using the handles libvloader hands it. Physical gamepads still work.
 */
public class VRGameActivity extends BaseActivity implements ServiceConnection {
    private static final String TAG = "VRGameActivity";
    /** The renderer QuestCraft uses; Vivecraft needs an OpenGL ES context for XR_KHR_opengl_es_enable */
    private static final String VR_RENDERER = "opengles_mobileglues";

    private MinecraftProfile mProfile;
    private String mVersionId;
    private GameService.LocalBinder mServiceBinder;
    private GamepadHandler mGamepadHandler;
    private RemapperManager mInputManager;
    private View mContentView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        VRMode.sRunningInVR = true;

        // Nothing here is ever visible in the headset, Vivecraft draws through OpenXR
        mContentView = new View(this);
        mContentView.setBackgroundColor(Color.BLACK);
        setContentView(mContentView);

        mProfile = LauncherProfiles.getCurrentProfile();
        String version = getIntent().getStringExtra(INTENT_MINECRAFT_VERSION);
        mVersionId = version == null ? mProfile.lastVersionId : version;
        File gameDir = Tools.getGameDirPath(mProfile);

        try {
            File latestLogFile = new File(Tools.DIR_GAME_HOME, "latestlog.txt");
            if(!latestLogFile.exists() && !latestLogFile.createNewFile())
                throw new IOException("Failed to create a new log file");
            Logger.begin(latestLogFile.getAbsolutePath());
        } catch (IOException e) {
            Log.e(TAG, "Failed to open the game log", e);
        }

        setupWindow(gameDir);

        mInputManager = new RemapperManager(this, new RemapperView.Builder(null)
                .remapA(true).remapB(true).remapX(true).remapY(true)
                .remapLeftJoystick(true).remapRightJoystick(true)
                .remapStart(true).remapSelect(true)
                .remapLeftShoulder(true).remapRightShoulder(true)
                .remapLeftTrigger(true).remapRightTrigger(true)
                .remapDpad(true));

        // Set the activity for the executor, so Tools.showErrorRemote() and friends find it
        ContextExecutor.setActivity(this);
        Intent gameServiceIntent = new Intent(this, GameService.class);
        ContextCompat.startForegroundService(this, gameServiceIntent);
        // The game starts once the service is attached, like in MainActivity
        bindService(gameServiceIntent, this, 0);
    }

    /** Give Minecraft a fixed-size main window; it is never shown, only the eye buffers are */
    private void setupWindow(File gameDir) {
        CallbackBridge.windowWidth = CallbackBridge.physicalWidth = VRMode.WINDOW_WIDTH;
        CallbackBridge.windowHeight = CallbackBridge.physicalHeight = VRMode.WINDOW_HEIGHT;
        MCOptionUtils.load(gameDir.getAbsolutePath());
        MCOptionUtils.set("fullscreen", "false");
        MCOptionUtils.set("overrideWidth", String.valueOf(VRMode.WINDOW_WIDTH));
        MCOptionUtils.set("overrideHeight", String.valueOf(VRMode.WINDOW_HEIGHT));
        MCOptionUtils.save();
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder service) {
        mServiceBinder = (GameService.LocalBinder) service;
        if(mServiceBinder.isActive) return; // Game already running in this process
        mServiceBinder.isActive = true;
        new Thread(() -> {
            try {
                runCraft();
            } catch (Throwable e) {
                Tools.showErrorRemote(e);
            }
        }, "JVM Main thread").start();
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {}

    private void runCraft() throws Throwable {
        JMinecraftVersionList.Version versionInfo = Tools.getVersionInfo(mVersionId);
        CallbackBridge.nativeSetUseInputStackQueue(versionInfo.arguments != null);

        if(!VR_RENDERER.equals(Tools.LOCAL_RENDERER)) {
            Log.i(TAG, "VR uses " + VR_RENDERER + " instead of the profile renderer " + mProfile.pojavRendererName);
        }
        Tools.LOCAL_RENDERER = VR_RENDERER;
        LauncherPreferences.writeMGRendererSettings();

        MinecraftAccount account = PojavProfile.getCurrentProfileContent(this, null);
        Logger.appendToLog("--------- Starting game in VR with Launcher Debug!");
        Tools.printLauncherInfo(mVersionId, Tools.isValidString(mProfile.javaArgs) ? mProfile.javaArgs : LauncherPreferences.PREF_CUSTOM_JAVA_ARGS, Tools.getTotalDeviceMemory(this));
        JREUtils.redirectAndPrintJRELog();
        LauncherProfiles.load();
        int requiredJavaVersion = versionInfo.javaVersion != null ? versionInfo.javaVersion.majorVersion : 8;
        Tools.launchMinecraft(this, account, mProfile, mVersionId, requiredJavaVersion);
        Tools.runOnUiThread(() -> mServiceBinder.isActive = false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        CallbackBridge.nativeSetWindowAttrib(LwjglGlfwKeycode.GLFW_FOCUSED, 1);
        CallbackBridge.nativeSetWindowAttrib(LwjglGlfwKeycode.GLFW_HOVERED, 1);
    }

    @Override
    protected void onPause() {
        CallbackBridge.nativeSetWindowAttrib(LwjglGlfwKeycode.GLFW_FOCUSED, 0);
        CallbackBridge.nativeSetWindowAttrib(LwjglGlfwKeycode.GLFW_HOVERED, 0);
        super.onPause();
    }

    @Override
    protected void onStart() {
        super.onStart();
        CallbackBridge.nativeSetWindowAttrib(LwjglGlfwKeycode.GLFW_VISIBLE, 1);
    }

    @Override
    protected void onStop() {
        CallbackBridge.nativeSetWindowAttrib(LwjglGlfwKeycode.GLFW_VISIBLE, 0);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ContextExecutor.clearActivity();
    }

    // Physical gamepads, same mapping as the regular game screen but without the on-screen cursor

    private GamepadHandler getGamepad(InputDevice device) {
        if(mGamepadHandler == null)
            mGamepadHandler = new Gamepad(mContentView, device, DefaultDataProvider.INSTANCE, false);
        return mGamepadHandler;
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        if(Gamepad.isGamepadEvent(event)) {
            mInputManager.handleMotionEventInput(this, event, getGamepad(event.getDevice()));
            return true;
        }
        return super.dispatchGenericMotionEvent(event);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if(Gamepad.isGamepadEvent(event)) {
            mInputManager.handleKeyEventInput(this, event, getGamepad(event.getDevice()));
            return true;
        }
        return super.dispatchKeyEvent(event);
    }
}
