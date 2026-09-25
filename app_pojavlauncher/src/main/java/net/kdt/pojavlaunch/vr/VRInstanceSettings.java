package net.kdt.pojavlaunch.vr;

import android.util.Log;

import net.kdt.pojavlaunch.Tools;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * VR settings of one profile, stored as vr-instance.json in its game directory. Profiles made in
 * the regular launcher don't have the file and get the defaults.
 */
public class VRInstanceSettings {
    private static final String FILE_NAME = "vr-instance.json";

    /** Whether VRModInstaller installs the default (performance) mods, not just the core ones */
    public boolean defaultMods = true;
    /** The VR menu installed this instance's mods itself, so VRModInstaller leaves them alone */
    public boolean menuMods;
    /** Shown by the VR menu */
    public String imageUrl;
    /** Mods and resource packs added from the VR menu's Modrinth browser */
    public List<ExtraProject> extraProjects = new ArrayList<>();

    public static class ExtraProject {
        public String slug;
        public String version;
        // Field names match what the VR menu reads over JNI
        public String download_link;
        public String fileName;
        /** Modrinth project type: "mod" or "resourcepack" */
        public String type;

        /** Where the file goes inside the game directory */
        public File getTarget(File gameDir) {
            return new File(gameDir, ("resourcepack".equals(type) ? "resourcepacks/" : "mods/") + fileName);
        }
    }

    public static VRInstanceSettings load(File gameDir) {
        File file = new File(gameDir, FILE_NAME);
        if (file.exists()) {
            try {
                VRInstanceSettings settings = Tools.GLOBAL_GSON.fromJson(Tools.read(file), VRInstanceSettings.class);
                if (settings != null) {
                    if (settings.extraProjects == null) settings.extraProjects = new ArrayList<>();
                    return settings;
                }
            } catch (Exception e) {
                Log.w("VRInstanceSettings", "Unreadable " + file + ", using defaults", e);
            }
        }
        return new VRInstanceSettings();
    }

    public void save(File gameDir) throws IOException {
        Tools.write(new File(gameDir, FILE_NAME).getAbsolutePath(), Tools.GLOBAL_GSON.toJson(this));
    }

    public ExtraProject findExtraProject(String slug) {
        for (ExtraProject project : extraProjects) {
            if (project.slug.equalsIgnoreCase(slug)) return project;
        }
        return null;
    }
}
