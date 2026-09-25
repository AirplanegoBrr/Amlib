package net.kdt.pojavlaunch.vr;

import android.content.Context;
import android.util.Log;

import androidx.annotation.Nullable;

import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import com.kdt.mcgui.ProgressLayout;

import net.kdt.pojavlaunch.JMinecraftVersionList;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.utils.DownloadUtils;
import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;

import org.apache.commons.io.IOUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Keeps the VR mods (Vivecraft with its OpenXR provider, Fabric API, and the default performance
 * mods) installed for the profile's Minecraft version before a VR launch, following assets/vr/mods.json.
 * The list is fetched from the Amlib repository so it can be updated without an app release,
 * with the last fetched copy and the bundled asset as fallbacks.
 */
public class VRModInstaller {
    private static final String TAG = "VRModInstaller";
    private static final String REMOTE_BASE = "https://raw.githubusercontent.com/AirplanegoBrr/Amlib/v3_openjdk/app_pojavlauncher/src/main/assets/vr/";
    /** Records which jars in the mods folder this installer owns */
    private static final String STATE_FILE = ".amethyst-vr-mods.json";

    private static class InstalledMod {
        String version;
        String file;
        /** A default mod the user deleted; it is not reinstalled */
        boolean removed;
    }

    /**
     * QuestCraft's settings for the Quest (assets/vr/defaults, from Pojlib): render and simulation
     * distance 4, no vsync, fast graphics, and its Sodium, ImmediatelyFast, More Culling, ModernFix
     * and Vivecraft configs. File name and the folder it goes in, relative to the game directory.
     */
    private static final String[][] DEFAULT_CONFIGS = {
            {"options.txt", ""},
            {"sodium-options.json", "config"},
            {"immediatelyfast.json", "config"},
            {"moreculling.toml", "config"},
            {"modernfix-mixins.properties", "config"},
            {"vivecraft-client-config.json", "config"},
    };
    /**
     * Writes the default configs an instance doesn't have yet, like Pojlib does on install. Files
     * that already exist are left alone, so settings players change are kept. The window size and
     * Vivecraft settings VRMode applies on every launch still override theirs.
     */
    private static void applyDefaultConfigs(Context context, File gameDir) throws IOException {
        for (String[] config : DEFAULT_CONFIGS) {
            Tools.copyAssetFile(context, "vr/defaults/" + config[0], new File(gameDir, config[1]).getAbsolutePath(), false);
        }
    }

    /**
     * Install or update the VR mods for {@code versionId} (the profile's version, e.g. a Fabric loader id).
     * Runs on the download thread, before the game starts.
     */
    public static void sync(Context context, String versionId) throws IOException {
        String minecraftVersion = getMinecraftVersion(versionId);
        if (!versionId.toLowerCase().contains("fabric")) {
            throw new IOException(context.getString(R.string.vr_needs_fabric, versionId));
        }

        MinecraftProfile profile = LauncherProfiles.getCurrentProfile();
        File gameDir = Tools.getGameDirPath(profile);
        VRInstanceSettings settings = VRInstanceSettings.load(gameDir);
        applyDefaultConfigs(context, gameDir);
        // Instances made in the VR menu get their mods from the menu, for any version it offers
        if (settings.menuMods) {
            VRMode.applyVivecraftConfig(gameDir);
            return;
        }

        VRModList modList = loadModList(context);
        VRModList.Version mods = modList.find(minecraftVersion);
        if (mods == null) {
            throw new IOException(context.getString(R.string.vr_no_mod_set, minecraftVersion));
        }

        File modsDir = new File(gameDir, "mods");
        if (!modsDir.isDirectory() && !modsDir.mkdirs()) throw new IOException("Failed to create " + modsDir);

        Map<String, InstalledMod> state = readState(modsDir);
        List<VRModList.Mod> wanted = new ArrayList<>();
        Set<String> coreSlugs = new HashSet<>();
        if (mods.coreMods != null) for (VRModList.Mod mod : mods.coreMods) { wanted.add(mod); coreSlugs.add(mod.slug); }
        if (settings.defaultMods && mods.defaultMods != null) for (VRModList.Mod mod : mods.defaultMods) wanted.add(mod);

        // Drop mods we installed that the list no longer has (e.g. another Minecraft version's set)
        Set<String> wantedSlugs = new HashSet<>();
        for (VRModList.Mod mod : wanted) wantedSlugs.add(mod.slug);
        for (String slug : new ArrayList<>(state.keySet())) {
            if (wantedSlugs.contains(slug)) continue;
            InstalledMod old = state.remove(slug);
            if (old != null && old.file != null) new File(modsDir, old.file).delete();
        }

        for (int i = 0; i < wanted.size(); i++) {
            VRModList.Mod mod = wanted.get(i);
            boolean isCore = coreSlugs.contains(mod.slug);
            InstalledMod installed = state.get(mod.slug);
            File installedFile = installed == null || installed.file == null ? null : new File(modsDir, installed.file);

            if (!isCore && installed != null) {
                if (installed.removed) continue;
                if (installedFile == null || !installedFile.exists()) {
                    Log.i(TAG, "Default mod " + mod.slug + " was removed by the user, not reinstalling");
                    installed.removed = true;
                    continue;
                }
            }
            if (installed != null && mod.version.equals(installed.version) && installedFile != null && installedFile.exists()) continue;

            ProgressLayout.setProgress(ProgressLayout.DOWNLOAD_MINECRAFT, i * 100 / wanted.size(),
                    R.string.vr_mods_downloading, mod.slug + " " + mod.version);
            File target = new File(modsDir, fileNameFor(mod));
            download(mod.downloadLink, target);
            if (installedFile != null && !installedFile.equals(target)) installedFile.delete();
            InstalledMod record = new InstalledMod();
            record.version = mod.version;
            record.file = target.getName();
            state.put(mod.slug, record);
            Log.i(TAG, "Installed " + mod.slug + " " + mod.version);
        }

        // Two copies of Vivecraft or Fabric API stop Fabric from loading
        for (String slug : coreSlugs) {
            InstalledMod installed = state.get(slug);
            if (installed != null) disableDuplicates(modsDir, new File(modsDir, installed.file), state);
        }

        writeState(modsDir, state);

        // Mods and resource packs added from the VR menu
        for (VRInstanceSettings.ExtraProject project : settings.extraProjects) {
            File target = project.getTarget(gameDir);
            if (target.exists()) continue;
            ProgressLayout.setProgress(ProgressLayout.DOWNLOAD_MINECRAFT, 100,
                    R.string.vr_mods_downloading, project.slug + " " + project.version);
            File parent = target.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("Failed to create " + parent);
            download(project.download_link, target);
        }

        VRMode.applyVivecraftConfig(gameDir);
    }

    /** The vanilla version a (possibly modded) version id is based on */
    static String getMinecraftVersion(String versionId) throws IOException {
        File json = new File(Tools.DIR_HOME_VERSION, versionId + "/" + versionId + ".json");
        JMinecraftVersionList.Version version = Tools.GLOBAL_GSON.fromJson(Tools.read(json), JMinecraftVersionList.Version.class);
        return version.inheritsFrom != null ? version.inheritsFrom : versionId;
    }

    public static VRModList loadModList(Context context) throws IOException {
        String name = LauncherPreferences.PREF_VR_DEV_MODS ? "devmods.json" : "mods.json";
        File cache = new File(Tools.DIR_DATA, "vr/" + name);
        try {
            String remote = DownloadUtils.downloadString(REMOTE_BASE + name);
            VRModList parsed = Tools.GLOBAL_GSON.fromJson(remote, VRModList.class);
            if (parsed != null && parsed.versions != null) {
                Tools.write(cache.getAbsolutePath(), remote);
                return parsed;
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not fetch the VR mod list, using a local copy", e);
        }
        if (cache.exists()) {
            try {
                return Tools.GLOBAL_GSON.fromJson(Tools.read(cache), VRModList.class);
            } catch (Exception e) {
                Log.w(TAG, "Cached VR mod list is unreadable", e);
            }
        }
        try (InputStream is = context.getAssets().open("vr/" + name)) {
            return Tools.GLOBAL_GSON.fromJson(new InputStreamReader(is, StandardCharsets.UTF_8), VRModList.class);
        }
    }

    private static String fileNameFor(VRModList.Mod mod) {
        return (mod.slug + "-" + mod.version).replaceAll("[^A-Za-z0-9._+-]", "_") + ".jar";
    }

    /** Download to a temporary file first so a failed download never leaves a broken jar behind */
    private static void download(String url, File target) throws IOException {
        File temp = new File(target.getPath() + ".part");
        try (FileOutputStream os = new FileOutputStream(temp)) {
            DownloadUtils.download(url, os);
        } catch (IOException e) {
            temp.delete();
            throw e;
        }
        if (target.exists() && !target.delete()) throw new IOException("Failed to replace " + target);
        if (!temp.renameTo(target)) throw new IOException("Failed to move " + temp + " to " + target);
    }

    /** Rename other jars that contain the same Fabric mod id as {@code owned} to .disabled */
    private static void disableDuplicates(File modsDir, File owned, Map<String, InstalledMod> state) {
        String modId = readFabricModId(owned);
        if (modId == null) return;
        Set<String> ownedFiles = new HashSet<>();
        for (InstalledMod installed : state.values()) if (installed.file != null) ownedFiles.add(installed.file);
        File[] jars = modsDir.listFiles(file -> file.isFile() && file.getName().endsWith(".jar"));
        if (jars == null) return;
        for (File jar : jars) {
            if (ownedFiles.contains(jar.getName())) continue;
            if (!modId.equals(readFabricModId(jar))) continue;
            File disabled = new File(jar.getPath() + ".disabled");
            Log.i(TAG, "Disabling " + jar.getName() + ", it duplicates " + owned.getName());
            if (!jar.renameTo(disabled)) Log.w(TAG, "Failed to disable " + jar);
        }
    }

    private static @Nullable String readFabricModId(File jar) {
        try (ZipFile zip = new ZipFile(jar)) {
            ZipEntry entry = zip.getEntry("fabric.mod.json");
            if (entry == null) return null;
            try (InputStream is = zip.getInputStream(entry)) {
                JsonObject obj = Tools.GLOBAL_GSON.fromJson(IOUtils.toString(is, StandardCharsets.UTF_8), JsonObject.class);
                return obj.has("id") ? obj.get("id").getAsString() : null;
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static Map<String, InstalledMod> readState(File modsDir) {
        File file = new File(modsDir, STATE_FILE);
        if (!file.exists()) return new HashMap<>();
        try {
            Map<String, InstalledMod> state = Tools.GLOBAL_GSON.fromJson(Tools.read(file),
                    new TypeToken<HashMap<String, InstalledMod>>(){}.getType());
            return state == null ? new HashMap<>() : state;
        } catch (Exception e) {
            Log.w(TAG, "Unreadable " + STATE_FILE + ", starting fresh", e);
            return new HashMap<>();
        }
    }

    private static void writeState(File modsDir, Map<String, InstalledMod> state) throws IOException {
        Tools.write(new File(modsDir, STATE_FILE).getAbsolutePath(), Tools.GLOBAL_GSON.toJson(state));
    }
}
