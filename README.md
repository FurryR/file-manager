# File

Android file manager built with Kotlin, Jetpack Compose and a Rust native
daemon. Package name: `io.furryr.file`.

## Features

- Local storage, SAF trees, FTP, SFTP, SSH, AList and HTTP(S) locations.
- Tabs with independent history, archive views, text editor and HTML viewer.
- Optional root or Shizuku access for system directories.
- Optional Linux containers (chroot/proot) with Wayland and PulseAudio.
- Terminal, in-app browser, userscripts, ad blocking and a Chrome DevTools tab.
- Optional AI agent with user-configured OpenAI-compatible providers.

## Build

Requirements:

- JDK 17
- Android SDK 35
- NDK 30.0.16248370
- Rust nightly; `rust-toolchain.toml` pins the toolchain and components
- Node.js/npm for the pinned DevTools frontend
- meson, ninja, cmake, pkg-config, m4, bison, gettext, gawk, patch and a host
  C toolchain for native builds

```bash
# Release: universal APK with all four ABIs.
./gradlew assembleRelease

# Debug: arm64-v8a only by default, so the edit/run loop stays fast.
./gradlew assembleDebug
./gradlew assembleDebug -Pabi=x86_64        # emulator or another device
./gradlew assembleDebug -Pabi=universal     # every ABI
```

A release build runs the native build first. To reuse already staged
`app/src/main/jniLibs` and `assets`:

```bash
./gradlew assembleRelease -PskipNativeBuild -PskipDevToolsBuild
```

`-PskipPostprocess` stops the local Zopfli/zipalign/apksigner step. F-Droid
uses it because the build server signs the APK itself:

```bash
./gradlew assembleRelease -PskipPostprocess
```

Release builds contain `arm64-v8a`, `armeabi-v7a`, `x86` and `x86_64`.
Debug builds compile only the selected ABI (arm64-v8a unless `-Pabi` is
given). Missing Rust targets are installed on demand for the ABI being
built; F-Droid's `prebuild` installs all four because its release build
needs them.

The flake at `flake.nix` provides the complete development environment on
NixOS (`nix develop`). F-Droid itself builds on Debian with the metadata in
`fdroid/`; it does not use Nix.

## F-Droid

Submission notes and the `fdroiddata` metadata draft live in
[`fdroid/`](fdroid/). Store text, icon and changelog are under
`fastlane/metadata/android/`.

## License

GPL-3.0-only. Third-party licenses are listed in `licenses/native.json`,
`app/src/main/assets/licenses.json` and `app/src/main/assets/native-licenses/`.
