/*
 * Android soname bridge for Smithay.
 *
 * Smithay's EGL loader hardcodes `dlopen("libEGL.so.1")`, a Linux desktop
 * soname. Android ships `libEGL.so`. This stub is linked with
 * `-Wl,-soname,libEGL.so.1` and preloaded by `NativeCompositor`, so Android's
 * linker resolves Smithay's request to this already-loaded library; its
 * `DT_NEEDED` on `libEGL.so` then supplies the real EGL symbols to `dlsym`.
 *
 * It exports no wrappers on purpose: nothing here intercepts or reimplements
 * EGL, it only fixes the name lookup.
 */

__attribute__((visibility("default"))) int wayland_egl_soname_bridge(void) {
    return 0;
}
