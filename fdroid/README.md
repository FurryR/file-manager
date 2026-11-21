# F-Droid submission

This directory keeps the source-side F-Droid notes and a draft of the
`fdroiddata` metadata. The file is not read by the app build.

## Before the merge request

1. Commit the Gradle wrapper (`gradle/wrapper/gradle-wrapper.jar` and
   `gradle-wrapper.properties`). It is no longer ignored.
2. Keep `versionCode` and `versionName` literal in
   `app/build.gradle.kts`, bump both for every release, and tag the release
   (`v1.0.0`). `UpdateCheckMode: Tags` relies on this.
3. Replace `commit: v1.0.0` in `io.furryr.file.yml` with the full commit hash
   of the tag. F-Droid prefers a hash over a moving tag.
4. Add at least one phone screenshot (two are recommended) under
   `fastlane/metadata/android/en-US/images/phoneScreenshots/`. They must be
   captured from a real device; the icon is already present.
5. Build once from a clean clone:
   ```bash
   ./gradlew assembleRelease -PskipPostprocess
   ```
   The expected output is
   `app/build/outputs/apk/release/app-release-unsigned.apk` and it contains
   all four ABIs.
6. Copy `io.furryr.file.yml` to a `fdroiddata` fork as
   `metadata/io.furryr.file.yml`, run `fdroid lint io.furryr.file`, then open
   a merge request.

## Build environment notes

- The app builds a universal APK containing arm64-v8a, armeabi-v7a, x86 and
  x86_64. Debug builds compile only arm64-v8a by default; pass
  `-Pabi=...` for one ABI or `-Pabi=universal` for all. Release builds and
  the F-Droid build compile every ABI. The native script installs a missing
  Rust target only for the ABI it is currently building.
- F-Droid signs the APK. `skipPostprocess` stops the local Zopfli/zipalign/
  apksigner step, which would otherwise produce `app-release-signed.apk`.
- The native build needs NDK 30.0.16248370, Rust nightly with `rust-src`
  (`rust-toolchain.toml`), Node/npm, meson, ninja, cmake, pkg-config, m4,
  bison, gettext, gawk and a host C toolchain. These are installed through
  `sudo`/`prebuild`.
- `gradle/wrapper/gradle-wrapper.jar` is a build tool, not shipped in the
  APK; it is listed under `scanignore` for the source scanner.
- Root and Shizuku are optional. `RequiresRoot` stays `false`.
- The container root filesystem download happens only after the user creates
  a container; the AI agent only contacts the provider the user configures.
- `targetSdk 28` is intentional: full-disk file management and the root/
  container features need the legacy storage model. Explain this in review
  questions rather than changing it as part of the submission.
