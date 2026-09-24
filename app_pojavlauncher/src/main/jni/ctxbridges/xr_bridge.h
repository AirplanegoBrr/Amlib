//
// Surfaceless EGL bridge used when the game runs as an immersive VR activity.
//
#include <stdbool.h>
#include "gl_bridge.h"
#ifndef POJAVLAUNCHER_XR_BRIDGE_H
#define POJAVLAUNCHER_XR_BRIDGE_H

bool xr_init();
gl_render_window_t* xr_get_current();
gl_render_window_t* xr_init_context(gl_render_window_t* share);
void xr_make_current(gl_render_window_t* bundle);
void xr_swap_buffers();
void xr_setup_window();
void xr_swap_interval(int swapInterval);

#endif //POJAVLAUNCHER_XR_BRIDGE_H
