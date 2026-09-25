package net.kdt.pojavlaunch.vr;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;

import com.kdt.mcgui.ProgressLayout;

import net.kdt.pojavlaunch.AmlibCore;
import net.kdt.pojavlaunch.JMinecraftVersionList;
import net.kdt.pojavlaunch.PojavProfile;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.authenticator.microsoft.MicrosoftBackgroundLogin;
import net.kdt.pojavlaunch.authenticator.microsoft.MicrosoftDeviceCodeLogin;
import net.kdt.pojavlaunch.extra.ExtraConstants;
import net.kdt.pojavlaunch.extra.ExtraCore;
import net.kdt.pojavlaunch.modloaders.FabricVersion;
import net.kdt.pojavlaunch.modloaders.FabriclikeUtils;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.progresskeeper.ProgressKeeper;
import net.kdt.pojavlaunch.progresskeeper.ProgressListener;
import net.kdt.pojavlaunch.services.GameService;
import net.kdt.pojavlaunch.tasks.AsyncAssetManager;
import net.kdt.pojavlaunch.tasks.AsyncMinecraftDownloader;
import net.kdt.pojavlaunch.tasks.AsyncVersionList;
import net.kdt.pojavlaunch.tasks.MinecraftDownloader;
import net.kdt.pojavlaunch.utils.DownloadUtils;
import net.kdt.pojavlaunch.utils.FileUtils;
import net.kdt.pojavlaunch.value.MinecraftAccount;
import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;

import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.net.UnknownHostException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Java side of the AmethystXR VR menu (a fork of QuestCraft's QCXR-XR-Wrapper). It keeps the
 * shape of the parts of QuestCraft's pojlib.API that the menu calls over JNI, backed by Amethyst's
 * profiles, accounts and downloads. Method names, parameters and public fields are the menu's
 * contract; change them together with AmethystXR's scripts.
 */
public class XRApi {
    private static final String TAG = "XRApi";
    /** Profiles created from the VR menu get their own game directory under this one */
    private static final String INSTANCES_DIR = "vr-instances";

    // Read or set by the menu
    public static String msaMessage = "";
    public static String model = "Quest";
    public static String profileImage;
    public static String profileName;
    public static String profileUUID;
    public static boolean isDemoMode;
    public static MinecraftAccount currentAcc;
    public static boolean developerMods;
    public static boolean customRAMValue;
    public static String memoryValue = "2048";
    /** Tells the menu to fade out and let go of the headset, the game is taking over */
    private static boolean sComponentsUnpacked;

    private static volatile boolean sDownloading;
    private static volatile int sDownloadProgress;

    /**
     * Call once before anything else when an app embeds Amlib (the phone app gets this from
     * PojavApplication). Safe to call repeatedly.
     */
    public static void init(Context context) {
        AmlibCore.init(context);
        if (sComponentsUnpacked || !Tools.checkStorageRoot(context)) return;
        sComponentsUnpacked = true;
        // The phone app's TestStorageActivity did this; without it the LWJGL jars are missing
        // and the game fails to start. Only here, never in the :game process, so it can't race a launch.
        AsyncAssetManager.unpackComponents(context);
        AsyncAssetManager.unpackSingleFiles(context);
    }

    // Instances

    public static XRInstances loadAll() {
        return new XRInstances();
    }

    /** Minecraft versions that have a VR mod set in mods.json */
    public static String[] getQCSupportedVersions(Activity activity) {
        try {
            VRModList list = VRModInstaller.loadModList(activity);
            String[] names = new String[list.versions.length];
            for (int i = 0; i < names.length; i++) names[i] = list.versions[i].name;
            return names;
        } catch (IOException e) {
            Log.e(TAG, "Can't load the VR mod list", e);
            return new String[0];
        }
    }

    /** Creates a Fabric profile with its own game directory. Mods are installed by prelaunch(). */
    public static XRInstance createNewInstance(Activity activity, XRInstances instances, String instanceName,
                                               boolean useDefaultMods, String minecraftVersion,
                                               String modLoader, @Nullable String imageURL) throws IOException {
        if (!"Fabric".equalsIgnoreCase(modLoader)) throw new IOException("Only Fabric instances can run in VR");
        String versionId = installFabric(minecraftVersion);

        MinecraftProfile profile = new MinecraftProfile();
        profile.name = instanceName;
        profile.lastVersionId = versionId;
        profile.gameDir = INSTANCES_DIR + "/" + instanceName.replaceAll("[^A-Za-z0-9._ -]", "_");
        LauncherProfiles.load();
        String key = LauncherProfiles.getFreeProfileKey();
        LauncherProfiles.mainProfileJson.profiles.put(key, profile);
        LauncherProfiles.write();

        VRInstanceSettings settings = new VRInstanceSettings();
        settings.defaultMods = useDefaultMods;
        settings.imageUrl = imageURL;
        File gameDir = Tools.getGameDirPath(profile);
        FileUtils.ensureDirectory(gameDir);
        settings.save(gameDir);

        XRInstance instance = new XRInstance(key, profile, minecraftVersion);
        instances.instances.add(instance);
        return instance;
    }

    /** Modpack import (.mrpack); not supported yet */
    public static XRInstance createNewInstance(Activity activity, XRInstances instances, String instanceName,
                                               String imageURL, String modLoader, String mrpackFile) {
        Log.w(TAG, "Modpack import isn't supported yet: " + mrpackFile);
        return null;
    }

    public static boolean deleteInstance(XRInstances instances, XRInstance instance) throws IOException {
        LauncherProfiles.load();
        LauncherProfiles.mainProfileJson.profiles.remove(instance.profileKey);
        LauncherProfiles.write();
        instances.instances.remove(instance);
        // Only delete game directories we created, never a shared one like .minecraft
        File gameDir = instance.getGameDir();
        File ours = new File(Tools.DIR_GAME_HOME, INSTANCES_DIR);
        if (gameDir.getCanonicalPath().startsWith(ours.getCanonicalPath() + File.separator)) {
            org.apache.commons.io.FileUtils.deleteDirectory(gameDir);
        }
        return true;
    }

    // Mods and resource packs added from the menu

    public static void addExtraProject(XRInstances instances, XRInstance instance, String slug, String fileName,
                                       String version, String url, String type) {
        try {
            VRInstanceSettings settings = instance.loadSettings();
            VRInstanceSettings.ExtraProject old = settings.findExtraProject(slug);
            if (old != null) {
                settings.extraProjects.remove(old);
                old.getTarget(instance.getGameDir()).delete();
            }
            VRInstanceSettings.ExtraProject project = new VRInstanceSettings.ExtraProject();
            project.slug = slug;
            project.fileName = fileName;
            project.version = version;
            project.download_link = url;
            project.type = type;
            settings.extraProjects.add(project);
            settings.save(instance.getGameDir());
            instance.refresh();
        } catch (IOException e) {
            Log.e(TAG, "Failed to add " + slug, e);
        }
    }

    public static boolean hasExtraProject(XRInstance instance, String slug) {
        return instance.loadSettings().findExtraProject(slug) != null;
    }

    public static boolean removeExtraProject(XRInstances instances, XRInstance instance, String slug) {
        try {
            VRInstanceSettings settings = instance.loadSettings();
            VRInstanceSettings.ExtraProject project = settings.findExtraProject(slug);
            if (project == null) return false;
            settings.extraProjects.remove(project);
            project.getTarget(instance.getGameDir()).delete();
            settings.save(instance.getGameDir());
            instance.refresh();
            return true;
        } catch (IOException e) {
            Log.e(TAG, "Failed to remove " + slug, e);
            return false;
        }
    }

    // Launching

    /**
     * Downloads the game, Java and the VR mods for the instance. Blocks until done; the menu calls
     * it from a background thread and shows getDownloadPercentage() meanwhile.
     */
    public static boolean prelaunch(Activity activity, XRInstances instances, XRInstance instance) {
        VLoader.gameReady = false;
        ProgressListener progressListener = new ProgressListener() {
            @Override public void onProgressStarted() {}
            @Override public void onProgressUpdated(int progress, int resid, Object... va) { sDownloadProgress = progress; }
            @Override public void onProgressEnded() {}
        };
        sDownloadProgress = 0;
        sDownloading = true;
        ProgressKeeper.addListener(ProgressLayout.DOWNLOAD_MINECRAFT, progressListener);
        try {
            // Wait for init()'s component unpacking, the launch needs those files
            CountDownLatch unpacked = new CountDownLatch(1);
            ProgressKeeper.waitUntilDone(unpacked::countDown);
            unpacked.await();
            applySettings(instance);
            if (currentAcc != null) PojavProfile.setCurrentProfile(activity, currentAcc.username);
            loadVersionList();

            String versionId = instance.profile.lastVersionId;
            JMinecraftVersionList.Version listed = AsyncMinecraftDownloader.getListedVersion(
                    AsyncMinecraftDownloader.normalizeVersionId(versionId));
            AtomicReference<Throwable> failure = new AtomicReference<>();
            CountDownLatch done = new CountDownLatch(1);
            new MinecraftDownloader().start(activity, listed, versionId, new AsyncMinecraftDownloader.DoneListener() {
                @Override public void onDownloadDone() { done.countDown(); }
                @Override public void onDownloadFailed(Throwable throwable) { failure.set(throwable); done.countDown(); }
            });
            done.await();
            if (failure.get() != null) {
                Log.e(TAG, "Prelaunch failed", failure.get());
                return false;
            }
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Prelaunch failed", e);
            return false;
        } finally {
            ProgressKeeper.removeListener(ProgressLayout.DOWNLOAD_MINECRAFT, progressListener);
            sDownloading = false;
        }
    }

    /**
     * Runs the game in VR inside this activity's process, like Pojlib does. Blocks until the game
     * exits, which also ends the process, so call it off the main thread; it only returns by
     * throwing when the launch fails. Keep the menu's VR scene up until {@link #isGameReady()},
     * then fade out and release the headset: Vivecraft opens its own OpenXR session on this
     * activity about 2.5 seconds later.
     */
    public static void launchInstance(FragmentActivity activity, MinecraftAccount account, XRInstance instance) throws Throwable {
        applySettings(instance);
        if (account != null) PojavProfile.setCurrentProfile(activity, account.username);
        VRMode.runGame(activity, instance.profile, instance.profile.lastVersionId);
    }

    /** True once Vivecraft is about to open its OpenXR session (set through libvloader) */
    public static boolean isGameReady() {
        return VLoader.gameReady;
    }

    /** Stops a running game */
    public static void restartLauncher(Activity activity) {
        activity.startService(new Intent(activity, GameService.class).putExtra("kill", true));
        VLoader.gameReady = false;
    }

    public static boolean isDownloadsCompleted() {
        return !sDownloading;
    }

    /** 0 to 100 */
    public static float getDownloadPercentage() {
        return sDownloading ? sDownloadProgress : 100f;
    }

    // Accounts

    /**
     * Signs into a saved account (refreshing it when expired), or starts a device code sign-in when
     * uuid is null or unknown. Progress goes to msaMessage; currentAcc is set when done.
     */
    public static void login(Activity activity, @Nullable String uuid) {
        currentAcc = null;
        MinecraftAccount saved = uuid == null ? null : findAccount(uuid);
        new Thread(() -> {
            try {
                if (saved == null) {
                    MicrosoftDeviceCodeLogin.DeviceCode code = MicrosoftDeviceCodeLogin.requestCode();
                    msaMessage = code.message();
                    String refreshToken = MicrosoftDeviceCodeLogin.waitForRefreshToken(code, () -> false);
                    finishMicrosoftLogin(activity, refreshToken);
                } else if (saved.isMicrosoft && saved.expiresAt < System.currentTimeMillis() && Tools.isOnline(activity)) {
                    msaMessage = "Refreshing your sign-in…";
                    finishMicrosoftLogin(activity, saved.msaRefreshToken);
                } else {
                    setAccount(activity, saved);
                }
            } catch (Exception e) {
                Log.e(TAG, "Sign-in failed", e);
                msaMessage = signInError(activity, e);
            }
        }, "XRApi login").start();
    }

    private static void finishMicrosoftLogin(Activity activity, String refreshToken) {
        msaMessage = "Signing in…";
        new MicrosoftBackgroundLogin(true, refreshToken).performLogin(null,
                account -> setAccount(activity, account),
                error -> msaMessage = signInError(activity, error));
    }

    /** Shows "No internet connection" instead of a raw DNS or socket error */
    private static String signInError(Context context, Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof UnknownHostException) return "No internet connection";
        }
        if (!Tools.isOnline(context)) return "No internet connection";
        return "Sign-in failed: " + error.getMessage();
    }

    private static void setAccount(Context context, MinecraftAccount account) {
        PojavProfile.setCurrentProfile(context, account.username);
        profileName = account.username;
        profileUUID = account.profileId;
        isDemoMode = account.isDemo();
        profileImage = null;
        msaMessage = "";
        currentAcc = account;
    }

    public static boolean removeAccount(Activity activity, String uuid) {
        MinecraftAccount account = findAccount(uuid);
        if (account == null) return false;
        if (currentAcc != null && uuid.equals(currentAcc.profileId)) currentAcc = null;
        return new File(Tools.DIR_ACCOUNT_NEW, account.username + ".json").delete();
    }

    private static @Nullable MinecraftAccount findAccount(String uuid) {
        for (MinecraftAccount account : PojavProfile.getAllProfiles()) {
            if (uuid.equals(account.profileId)) return account;
        }
        return null;
    }

    // Misc

    public static boolean hasConnection(Context context) {
        return Tools.isOnline(context);
    }

    /** QuestCraft-specific, nothing to fix in Amethyst */
    public static boolean fixDataPermissions() {
        return true;
    }

    public static void mirrorNativesInFolder(Context activity, XRInstances instances, XRInstance instance, String path) {
        Log.w(TAG, "mirrorNativesInFolder isn't supported");
    }

    public static void unzipSavesFromFolder(XRInstance instance, String path) {
        Log.w(TAG, "unzipSavesFromFolder isn't supported");
    }

    // Helpers

    /** Makes the instance current and carries the menu's settings over to the game process */
    private static void applySettings(XRInstance instance) {
        LauncherPreferences.PREF_VR_MODE = true;
        LauncherPreferences.PREF_VR_DEV_MODS = developerMods;
        android.content.SharedPreferences.Editor editor = LauncherPreferences.DEFAULT_PREF.edit()
                .putString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE, instance.profileKey)
                .putBoolean("vrMode", true)
                .putBoolean("vrDevMods", developerMods);
        if (customRAMValue) {
            try {
                int ram = Integer.parseInt(memoryValue);
                LauncherPreferences.PREF_RAM_ALLOCATION = ram;
                editor.putInt("allocation", ram);
            } catch (NumberFormatException e) {
                Log.w(TAG, "Ignoring RAM value " + memoryValue);
            }
        }
        editor.commit();
    }

    /** Installs the newest stable Fabric loader for a Minecraft version, returns its version id */
    private static String installFabric(String minecraftVersion) throws IOException {
        FabriclikeUtils fabric = FabriclikeUtils.FABRIC_UTILS;
        FabricVersion[] loaders = fabric.downloadLoaderVersions(minecraftVersion);
        if (loaders == null || loaders.length == 0) throw new IOException("No Fabric loader for Minecraft " + minecraftVersion);
        String loaderVersion = loaders[0].version;
        for (FabricVersion loader : loaders) {
            if (loader.stable) { loaderVersion = loader.version; break; }
        }
        String json = DownloadUtils.downloadString(fabric.createJsonDownloadUrl(minecraftVersion, loaderVersion));
        try {
            String versionId = new JSONObject(json).getString("id");
            File versionDir = new File(Tools.DIR_HOME_VERSION, versionId);
            FileUtils.ensureDirectory(versionDir);
            Tools.write(new File(versionDir, versionId + ".json").getAbsolutePath(), json);
            return versionId;
        } catch (org.json.JSONException e) {
            throw new IOException("Bad Fabric profile json", e);
        }
    }

    /** The downloader needs Mojang's version list, which the regular launcher loads on start */
    private static void loadVersionList() throws InterruptedException {
        if (ExtraCore.getValue(ExtraConstants.RELEASE_TABLE) != null) return;
        CountDownLatch done = new CountDownLatch(1);
        new AsyncVersionList().getVersionList(versions -> {
            // Null when offline without a cached list; the downloader then only uses local files
            if (versions != null) ExtraCore.setValue(ExtraConstants.RELEASE_TABLE, versions);
            done.countDown();
        }, false);
        done.await();
    }
}
