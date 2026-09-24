package net.kdt.pojavlaunch.vr;

import androidx.annotation.Nullable;

import com.google.gson.annotations.SerializedName;

/** Schema of assets/vr/mods.json (same layout as QuestCraft's Pojlib mods.json) */
public class VRModList {
    public Version[] versions;

    public static class Version {
        /** Minecraft version, e.g. "1.21.11" */
        public String name;
        /** Always kept installed (Vivecraft, Fabric API) */
        public Mod[] coreMods;
        /** Installed once, the user may remove them */
        public Mod[] defaultMods;
    }

    public static class Mod {
        public String slug;
        public String version;
        @SerializedName("download_link")
        public String downloadLink;
    }

    public @Nullable Version find(String minecraftVersion) {
        if (versions == null) return null;
        for (Version version : versions) {
            if (minecraftVersion.equals(version.name)) return version;
        }
        return null;
    }
}
