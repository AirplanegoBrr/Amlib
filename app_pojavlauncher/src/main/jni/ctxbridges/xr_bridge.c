//
// Surfaceless EGL bridge used when the game runs as an immersive VR activity.
// There is no Android window: Vivecraft renders each eye straight into OpenXR swapchains
// (see vloader.c), so Minecraft's own window is never presented. Mirrors what
// QuestCraft's Pojlib does in its egl_bridge.c.
//
#include <EGL/egl.h>
#include <string.h>
#include <stdlib.h>
#include <environ/environ.h>
#include "xr_bridge.h"
#include "egl_loader.h"

#define TAG __FILE_NAME__
#include <log.h>

static __thread gl_render_window_t* currentBundle;
static EGLDisplay g_XrEglDisplay;

bool xr_init() {
    // Loads EGL and initializes the default display, which gl_init_context() uses
    if(!gl_init()) return false;
    g_XrEglDisplay = eglGetDisplay_p(EGL_DEFAULT_DISPLAY);
    return g_XrEglDisplay != EGL_NO_DISPLAY;
}

gl_render_window_t* xr_get_current() {
    return currentBundle;
}

gl_render_window_t* xr_init_context(gl_render_window_t* share) {
    return gl_init_context(share);
}

// Fallback for drivers without EGL_KHR_surfaceless_context
static bool xr_make_current_pbuffer(gl_render_window_t* bundle) {
    if(bundle->surface == NULL) {
        const EGLint pbuffer_attrs[] = {EGL_WIDTH, 16, EGL_HEIGHT, 16, EGL_NONE};
        bundle->surface = eglCreatePbufferSurface_p(g_XrEglDisplay, bundle->config, pbuffer_attrs);
        if(bundle->surface == EGL_NO_SURFACE) {
            LOGE("eglCreatePbufferSurface_p() failed: %04x", eglGetError_p());
            bundle->surface = NULL;
            return false;
        }
    }
    return eglMakeCurrent_p(g_XrEglDisplay, bundle->surface, bundle->surface, bundle->context);
}

void xr_make_current(gl_render_window_t* bundle) {
    if(bundle == NULL) {
        if(eglMakeCurrent_p(g_XrEglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT)) {
            currentBundle = NULL;
        }
        return;
    }
    if(pojav_environ->mainWindowBundle == NULL) {
        pojav_environ->mainWindowBundle = (basic_render_window_t*)bundle;
    }
    bool made;
    if(bundle->surface != NULL) {
        // Already fell back to a pbuffer for this context
        made = xr_make_current_pbuffer(bundle);
    } else {
        made = eglMakeCurrent_p(g_XrEglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, bundle->context);
        if(!made) {
            LOGW("Surfaceless eglMakeCurrent unavailable (%04x), using a pbuffer", eglGetError_p());
            made = xr_make_current_pbuffer(bundle);
        }
    }
    if(made) {
        currentBundle = bundle;
        LOGI("XR context current: display=%p context=%p surface=%p", g_XrEglDisplay, bundle->context, bundle->surface);
    } else {
        LOGE("eglMakeCurrent returned with error: %04x", eglGetError_p());
    }
}

// Frames are submitted through OpenXR, there is nothing to present here
void xr_swap_buffers() {}

void xr_setup_window() {}

// Pacing comes from xrWaitFrame
void xr_swap_interval(int swapInterval) {}
