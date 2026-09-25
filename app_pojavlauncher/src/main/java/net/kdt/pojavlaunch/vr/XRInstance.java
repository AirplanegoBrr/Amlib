package net.kdt.pojavlaunch.vr;

import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;

import java.io.File;

/**
 * One Amethyst profile as the VR menu sees it. The public fields are read by name over JNI
 * (AmethystXR's PojlibInstance.Parse), so their names can't change.
 */
public class XRInstance {
    public String instanceName;
    public String instanceImageURL;
    /** Minecraft version, e.g. "1.21.11" */
    public String versionName;
    public String versionType = "release";
    public String gameDir;
    public boolean defaultMods;
    public VRInstanceSettings.ExtraProject[] extProjects;
    // QuestCraft internals the menu still reads; Amethyst resolves these at launch instead
    public String classpath;
    public String assetIndex;
    public String assetsDir;
    public String mainClass;
    public String extraNatives;

    /** Key of the profile in launcher_profiles.json */
    final String profileKey;
    final MinecraftProfile profile;

    XRInstance(String profileKey, MinecraftProfile profile, String minecraftVersion) {
        this.profileKey = profileKey;
        this.profile = profile;
        instanceName = profile.name;
        versionName = minecraftVersion;
        refresh();
    }

    File getGameDir() {
        return Tools.getGameDirPath(profile);
    }

    VRInstanceSettings loadSettings() {
        return VRInstanceSettings.load(getGameDir());
    }

    /** Re-read the per-instance settings after they changed */
    void refresh() {
        VRInstanceSettings settings = loadSettings();
        gameDir = getGameDir().getAbsolutePath();
        instanceImageURL = settings.imageUrl;
        defaultMods = settings.defaultMods;
        extProjects = settings.extraProjects.toArray(new VRInstanceSettings.ExtraProject[0]);
    }
}
