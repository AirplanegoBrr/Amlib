package net.kdt.pojavlaunch.vr;

import android.util.Log;

import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** The profiles that can launch in VR (Fabric ones), as the list object the VR menu passes around */
public class XRInstances {
    final List<XRInstance> instances = new ArrayList<>();

    XRInstances() {
        LauncherProfiles.load();
        for (Map.Entry<String, MinecraftProfile> entry : LauncherProfiles.mainProfileJson.profiles.entrySet()) {
            MinecraftProfile profile = entry.getValue();
            String versionId = profile.lastVersionId;
            if (versionId == null || !versionId.toLowerCase().contains("fabric")) continue;
            instances.add(new XRInstance(entry.getKey(), profile, getMinecraftVersion(versionId)));
        }
    }

    /** Called by the menu over JNI */
    public XRInstance[] toArray() {
        return instances.toArray(new XRInstance[0]);
    }

    private static String getMinecraftVersion(String versionId) {
        try {
            return VRModInstaller.getMinecraftVersion(versionId);
        } catch (Exception e) {
            Log.w("XRInstances", "Can't read the version json of " + versionId, e);
            return versionId;
        }
    }
}
