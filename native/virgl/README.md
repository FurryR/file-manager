# Android host VirGL renderer

`../build-virgl.py` builds a real ARM64 Android `virgl_test_server` from
virglrenderer 1.3.0 and libepoxy 1.5.10, statically linking both libraries into
`app/src/main/jniLibs/arm64-v8a/libvirglserver.so`. The `.so` name lets Android's
installer extract the executable into `nativeLibraryDir`.

From the repository root, with Python 3.12+, Git and an Android NDK:

```sh
python native/build-virgl.py --ndk /path/to/android-ndk-r30
```

The script also accepts `--work`, `--tooling` and `--jobs`. By default, all
downloads, generated sources, build files and local Python packages live in a
`virgl-build` directory next to the repository. It does not install tools into
the user's Python environment or require a host C compiler. Meson 1.7.2,
Ninja 1.11.1.4 and PyYAML 6.0.2 are installed with `pip --target` when missing.
All upstream source downloads are pinned and verified with SHA-256. Changed
patches get a separate source/build directory; existing checkouts are preserved.

The build uses NDK API 26, EGL/GLES and 16 KiB ELF segment alignment. It disables
unit tests, fuzzers, Vulkan/Venus, GBM, X11, DRM native contexts and video codecs.
It only builds the server target. The script checks ELF architecture and rejects
unexpected non-system dynamic dependencies before staging the result.

## Runtime contract

Launch the executable as the app UID, using a short socket pathname inside the
app's private data directory:

```sh
libvirglserver.so --use-gles --use-egl-surfaceless --multi-clients --socket-path /private/app/path/virgl.sock
```

`--multi-clients` disables per-client forks and services multiple clients in one
renderer. Clients must belong to the same trust boundary. The server speaks the
upstream vtest protocol over this Unix socket, including `SCM_RIGHTS` for shared
memory. A bridge must preserve frame bytes and attached descriptors, in both
directions. A byte-only TCP proxy is insufficient for newer vtest commands.

The binary's direct runtime dependencies are `libandroid.so`, `libm.so`,
`libdl.so` and `libc.so`. Libepoxy loads Android's `libEGL.so` and `libGLESv2.so`
at runtime. There are no Termux paths, ANGLE libraries or separately shipped
libepoxy/libvirglrenderer shared libraries. EGL uses `EGL_DEFAULT_DISPLAY`;
when surfaceless contexts are unavailable, a 1-by-1 pbuffer is used instead.
The shared-memory path falls back to API-26 `ASharedMemory_create` if the raw
memfd syscall is unavailable or denied.

Compilation and ELF inspection do not prove that a particular device driver or
guest Mesa installation can render successfully. Device validation must include
a real guest GL application, its reported renderer, and the displayed output.

## Sources and patches

- [virglrenderer 1.3.0](https://gitlab.freedesktop.org/virgl/virglrenderer/-/archive/virglrenderer-1.3.0/virglrenderer-virglrenderer-1.3.0.tar.gz)
  — SHA-256 `56170f8caa1bb642a2624b649e3bcca095ec2834814e5c308efc8a85a709e4ce`.
- [libepoxy 1.5.10](https://github.com/anholt/libepoxy/archive/refs/tags/1.5.10.tar.gz)
  — SHA-256 `a7ced37f4102b745ac86d6a70a9da399cc139ff168ba6b8002b4d8d43c900c15`.
- The numbered compatibility patches are derived from
  [Termux packages at 5952664fae176ea6929f354bf1b1af70c4a1cffb](https://github.com/termux/termux-packages/tree/5952664fae176ea6929f354bf1b1af70c4a1cffb/packages/virglrenderer-android).
  Patch 0001's Meson context was rebased to this exact source archive. ANGLE and
  Vulkan patches are deliberately not applied. The build script applies the
  additional static-linking, Android EGL and public shared-memory changes with
  exact-source checks. The DXT/S3TC compatibility code originates from gl4es.

Upstream MIT notices for virglrenderer, libepoxy and gl4es are preserved under
`licenses/`. The build also stages those notices into APK assets under
`native-licenses/`.
