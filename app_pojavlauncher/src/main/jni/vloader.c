//
// Ported from QuestCraftPlusPlus/Pojlib (vloader.cpp, originally by Judge)
//
// The Java_org_vivecraft_util_VLoader_* functions are called from inside the game JVM by
// Vivecraft's org.vivecraft.util.VLoader, so their names and signatures must not change.
// They hand Vivecraft the handles it needs to create an OpenXR session on Minecraft's context.
//

#include <stdbool.h>
#include <stdint.h>
#include <jni.h>
#include <EGL/egl.h>
#include "environ/environ.h"
#include "log.h"

JNIEXPORT jlong JNICALL
Java_org_vivecraft_util_VLoader_getEGLDisplay(JNIEnv* env, jclass clazz) {
    return (jlong) eglGetCurrentDisplay();
}

JNIEXPORT jlong JNICALL
Java_org_vivecraft_util_VLoader_getEGLContext(JNIEnv* env, jclass clazz) {
    return (jlong) eglGetCurrentContext();
}

JNIEXPORT jlong JNICALL
Java_org_vivecraft_util_VLoader_getEGLConfig(JNIEnv* env, jclass clazz) {
    EGLConfig cfg = NULL;
    EGLint num_configs = 0;

    static const EGLint attribs[] = {
            EGL_RED_SIZE, 8,
            EGL_GREEN_SIZE, 8,
            EGL_BLUE_SIZE, 8,
            EGL_ALPHA_SIZE, 8,
            // Minecraft required on initial 24
            EGL_DEPTH_SIZE, 24,
            EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
            EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
            EGL_NONE
    };

    if (!eglChooseConfig(eglGetCurrentDisplay(), attribs, &cfg, 1, &num_configs) || num_configs < 1) {
        LOGE("VLoader: eglChooseConfig failed: 0x%x", eglGetError());
        return 0;
    }
    return (jlong) cfg;
}

JNIEXPORT jlong JNICALL
Java_org_vivecraft_util_VLoader_getDalvikVM(JNIEnv* env, jclass clazz) {
    return (jlong) pojav_environ->dalvikJavaVMPtr;
}

JNIEXPORT jlong JNICALL
Java_org_vivecraft_util_VLoader_getDalvikActivity(JNIEnv* env, jclass clazz) {
    return (jlong) pojav_environ->vrActivity;
}

// Called by Vivecraft once its XR session is up; tells the Android side the game is ready
JNIEXPORT void JNICALL
Java_org_vivecraft_util_VLoader_setupAndroid(JNIEnv* env, jclass clazz) {
    JavaVM* dvm = pojav_environ->dalvikJavaVMPtr;
    jclass loaderClass = pojav_environ->vrLoaderClass;
    if (dvm == NULL || loaderClass == NULL) {
        LOGE("VLoader: setupAndroid called before VLoader.setAndroidInitInfo");
        return;
    }

    // This runs on the game's render thread, which stays attached (same as Pojlib)
    JNIEnv* dvEnv;
    if ((*dvm)->GetEnv(dvm, (void**) &dvEnv, JNI_VERSION_1_6) != JNI_OK
        && (*dvm)->AttachCurrentThread(dvm, &dvEnv, NULL) != JNI_OK) {
        LOGE("VLoader: failed to attach to the Android VM");
        return;
    }
    jfieldID fieldID = (*dvEnv)->GetStaticFieldID(dvEnv, loaderClass, "gameReady", "Z");
    (*dvEnv)->SetStaticBooleanField(dvEnv, loaderClass, fieldID, JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_net_kdt_pojavlaunch_vr_VLoader_nativeSetAndroidInitInfo(JNIEnv* env, jclass clazz, jobject ctx) {
    if (pojav_environ->vrActivity != NULL) (*env)->DeleteGlobalRef(env, pojav_environ->vrActivity);
    pojav_environ->vrActivity = (*env)->NewGlobalRef(env, ctx);
    if (pojav_environ->vrLoaderClass == NULL) {
        pojav_environ->vrLoaderClass = (*env)->NewGlobalRef(env, clazz);
    }
}

// OpenXR handles are 64 bits wide and XrResult is an int32_t; that is all this needs, so it
// doesn't pull in the OpenXR headers
typedef int32_t (*host_xr_handle_fn)(uint64_t handle);

JNIEXPORT jint JNICALL
Java_net_kdt_pojavlaunch_vr_VLoader_nativeCallHostXr(JNIEnv* env, jclass clazz, jlong function, jlong handle) {
    int32_t result = ((host_xr_handle_fn) (intptr_t) function)((uint64_t) handle);
    LOGI("VLoader: host OpenXR call returned %d", result);
    return result;
}
