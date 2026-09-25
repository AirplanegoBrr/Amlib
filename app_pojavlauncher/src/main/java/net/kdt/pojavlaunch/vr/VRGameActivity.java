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

import net.kdt.pojavlaunch.AmlibCore;
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

    private MinecraftProfile mProfile;
    private String mVersionId;
    private GameService.LocalBinder mServiceBinder;
    private GamepadHandler mGamepadHandler;
    private RemapperManager mInputManager;
    private View mContentView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Runs in its own process, which the app embedding Amlib doesn't set up
        AmlibCore.init(this);
        super.onCreate(savedInstanceState);
        VRMode.sRunningInVR = true;

        // Nothing here is ever visible in the headset, Vivecraft draws through OpenXR
        mContentView = new View(this);
        mContentView.setBackgroundColor(Color.BLACK);
        setContentView(mContentView);

        mProfile = LauncherProfiles.getCurrentProfile();
        String version = getIntent().getStringExtra(INTENT_MINECRAFT_VERSION);
        mVersionId = version == null ? mProfile.lastVersionId : version;

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

    @Override
    public void onServiceConnected(ComponentName name, IBinder service) {
        mServiceBinder = (GameService.LocalBinder) service;
        if(mServiceBinder.isActive) return; // Game already running in this process
        mServiceBinder.isActive = true;
        new Thread(() -> {
            try {
                runCraft();
            } catch (Throwable e) {
                onLaunchFailed(e);
            }
        }, "JVM Main thread").start();
    }

    /**
     * An error dialog can't be seen in immersive VR, so log the error and go back to the app
     * embedding Amlib (the VR menu) instead of leaving the player in an empty loading room.
     */
    private void onLaunchFailed(Throwable e) {
        Log.e(TAG, "Failed to start the game", e);
        Logger.appendToLog("Failed to start the game: " + Log.getStackTraceString(e));
        runOnUiThread(() -> {
            Intent menu = getPackageManager().getLaunchIntentForPackage(getPackageName());
            if (menu != null) startActivity(menu.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            finish();
            // Ends this :game process, so the next Play starts from scratch
            startService(new Intent(this, GameService.class).putExtra("kill", true));
        });
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {}

    private void runCraft() throws Throwable {
        VRMode.runGame(this, mProfile, mVersionId);
        Tools.runOnUiThread(() -> mServiceBinder.isActive = false);
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
