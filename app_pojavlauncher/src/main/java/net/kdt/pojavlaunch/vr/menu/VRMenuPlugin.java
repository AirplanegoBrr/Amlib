package net.kdt.pojavlaunch.vr.menu;

import android.app.Activity;
import android.content.Intent;
import android.util.Log;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.fragment.app.FragmentActivity;

import net.kdt.pojavlaunch.LauncherActivity;
import net.kdt.pojavlaunch.PojavProfile;
import net.kdt.pojavlaunch.value.MinecraftAccount;
import net.kdt.pojavlaunch.vr.VLoader;
import net.kdt.pojavlaunch.vr.XRApi;
import net.kdt.pojavlaunch.vr.XRInstance;
import net.kdt.pojavlaunch.vr.XRInstances;

import org.godotengine.godot.Godot;
import org.godotengine.godot.plugin.GodotPlugin;
import org.godotengine.godot.plugin.UsedByGodot;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The "Amethyst" singleton the Godot VR menu (AmethystXR-Menu) talks to. Godot loads it from the
 * manifest entry org.godotengine.plugin.v2.Amethyst. The menu polls getMenuState()
 * for everything it shows, and calls signIn()/createInstance()/play(). Built on XRApi.
 */
public class VRMenuPlugin extends GodotPlugin {
    private static final String TAG = "VRMenuPlugin";

    private volatile boolean mBusy;
    /** What the busy work is: "creating", "downloading" or "starting" (for the progress bar) */
    private volatile String mPhase = "";
    private volatile String mStatus = "";
    /** Name of the instance createInstance() made last, so the menu can select it */
    private volatile String mLastCreated = "";
    private XRInstances mInstances;
    private String[] mVersions;

    public VRMenuPlugin(Godot godot) {
        super(godot);
    }

    @NonNull
    @Override
    public String getPluginName() {
        return "Amethyst";
    }

    @Override
    public View onMainCreate(Activity activity) {
        XRApi.init(activity);
        restoreLastAccount(activity);
        return null;
    }

    /** Restores the account last used in the launcher, so the user is signed in right away */
    private void restoreLastAccount(Activity activity) {
        String name = PojavProfile.getCurrentProfileName(activity);
        MinecraftAccount account = name.isEmpty() ? null : MinecraftAccount.load(name);
        if (account != null && account.profileId != null && !account.isLocal()) {
            XRApi.login(activity, account.profileId);
        }
    }

    @UsedByGodot
    public String getMenuState() {
        try {
            if (mInstances == null) mInstances = XRApi.loadAll();
            if (mVersions == null) mVersions = XRApi.getQCSupportedVersions(getActivity());

            JSONObject state = new JSONObject();
            boolean signedIn = XRApi.currentAcc != null;
            state.put("signedIn", signedIn);
            state.put("account", signedIn ? XRApi.profileName : "");
            state.put("loginMessage", XRApi.msaMessage == null ? "" : XRApi.msaMessage);

            JSONArray instances = new JSONArray();
            for (XRInstance instance : mInstances.toArray()) {
                instances.put(new JSONObject().put("name", instance.instanceName).put("version", instance.versionName)
                        .put("gameDir", instance.gameDir));
            }
            state.put("instances", instances);
            state.put("versions", new JSONArray(mVersions));

            state.put("busy", mBusy);
            state.put("phase", mBusy ? mPhase : "");
            state.put("lastCreated", mLastCreated);
            state.put("progress", mBusy ? XRApi.getDownloadPercentage() : 0);
            state.put("status", mStatus);
            return state.toString();
        } catch (JSONException e) {
            Log.e(TAG, "Failed to build the menu state", e);
            return "{}";
        }
    }

    /** Starts a device code sign-in; the code shows up as loginMessage */
    @UsedByGodot
    public void signIn() {
        XRApi.login(getActivity(), null);
    }

    /**
     * Makes a new Fabric instance with the default VR mods for a Minecraft version that has a VR
     * mod set. It's named after the version ("1.21.11", then "1.21.11 (2)" and so on).
     */
    @UsedByGodot
    public void createInstance(String version) {
        if (mBusy) return;
        mBusy = true;
        mPhase = "creating";
        Activity activity = getActivity();
        new Thread(() -> {
            String name = freeName(version);
            try {
                mStatus = "Creating " + name + "…";
                XRApi.createNewInstance(activity, mInstances, name, true, version, "Fabric", null);
                mLastCreated = name;
                mStatus = "Created " + name;
            } catch (Exception e) {
                Log.e(TAG, "Failed to create " + name, e);
                mStatus = "Couldn't create the instance: " + e.getMessage();
            } finally {
                mBusy = false;
            }
        }, "VR menu create").start();
    }

    /**
     * Downloads what's needed and starts the game in VR. The game runs in this process, so the
     * menu keeps showing its loading state until the game takes over the headset.
     */
    @UsedByGodot
    public void play(String name) {
        if (mBusy) return;
        mBusy = true;
        Activity activity = getActivity();
        new Thread(() -> {
            try {
                XRInstance instance = find(name);
                if (instance == null) throw new IllegalStateException("No instance called " + name);
                mPhase = "downloading";
                mStatus = "Downloading…";
                if (!XRApi.prelaunch(activity, mInstances, instance)) {
                    mStatus = "Download failed, check your connection and try again";
                    return;
                }
                mPhase = "starting";
                mStatus = "Starting Minecraft…";
                // Runs the game in this process until it exits; the menu stays up meanwhile and
                // hands the headset over once isGameReady() (see main.gd)
                XRApi.launchInstance((FragmentActivity) activity, XRApi.currentAcc, instance);
            } catch (Throwable e) {
                Log.e(TAG, "Failed to start " + name, e);
                mStatus = "Couldn't start the game: " + e.getMessage();
            } finally {
                mBusy = false;
            }
        }, "VR menu launch").start();
    }

    /** True once Vivecraft is about to take over the headset; the menu must release it now */
    @UsedByGodot
    public boolean isGameReady() {
        return XRApi.isGameReady();
    }

    /** Calls one of Godot's own OpenXR functions on a handle, see VLoader.callHostXr */
    @UsedByGodot
    public int callXr(long function, long handle) {
        return VLoader.callHostXr(function, handle);
    }

    /** Leaves VR for the regular 2D launcher */
    @UsedByGodot
    public void openLauncher() {
        Activity activity = getActivity();
        activity.startActivity(new Intent(activity, LauncherActivity.class));
        activity.runOnUiThread(activity::finish);
    }

    private String freeName(String version) {
        String name = version;
        for (int i = 2; find(name) != null; i++) name = version + " (" + i + ")";
        return name;
    }

    private XRInstance find(String name) {
        for (XRInstance instance : mInstances.toArray()) {
            if (instance.instanceName.equals(name)) return instance;
        }
        return null;
    }
}
