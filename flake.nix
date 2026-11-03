{
  description = "File Manager Android development shell";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";

  outputs = { nixpkgs, ... }:
    let
      # The current native scripts target the Linux x86_64 NDK host;
      # build-proot.sh also hard-codes this host tag.
      systems = [ "x86_64-linux" ];

      forAllSystems = f:
        builtins.listToAttrs (map (system: {
          name = system;
          value = f system;
        }) systems);
    in
    {
      devShells = forAllSystems (system:
        let
          pkgs = import nixpkgs {
            inherit system;
            config = {
              allowUnfree = true;
              android_sdk.accept_license = true;
            };
          };

          # nixpkgs pins the NDK catalogue in repo.json. The pinned revision
          # predates r30, so add its repository entry here: androidenv then
          # fetches, deploys and patchelfs it like a built-in version. Drop
          # this override once the pinned nixpkgs knows r30.
          ndkVersion = "30.0.16248370";
          androidRepo = pkgs.lib.recursiveUpdate
            (builtins.fromJSON (builtins.readFile (pkgs.path + "/pkgs/development/mobile/androidenv/repo.json")))
            {
              packages.ndk.${ndkVersion} = {
                archives = [
                  {
                    arch = "all";
                    os = "linux";
                    sha1 = "5107f898313790e449e87eee2183d9a20602dee9";
                    size = 738633529;
                    url = "https://dl.google.com/android/repository/android-ndk-r30-linux.zip";
                  }
                ];
                displayName = "NDK (Side by side) ${ndkVersion}";
                license = "android-sdk-license";
                name = "ndk";
                path = "ndk/${ndkVersion}";
                revision = ndkVersion;
                revision-details = {
                  "major:0" = "30";
                  "minor:1" = "0";
                  "micro:2" = "16248370";
                };
                type-details = {
                  element-attributes = {
                    "xsi:type" = "ns5:genericDetailsType";
                  };
                };
              };
            };

          android = pkgs.androidenv.composeAndroidPackages {
            platformVersions = [ "35" ];
            # AGP 8.6.1 defaults to build-tools 34.0.0 even for compileSdk 35.
            # Keep 34.0.0 installed as well, otherwise Gradle tries to download
            # it into the read-only /nix/store SDK and the build fails.
            buildToolsVersions = [ "34.0.0" "35.0.0" ];
            # One NDK for local scripts and CI.
            includeNDK = true;
            inherit ndkVersion;
            repo = androidRepo;
            includeEmulator = false;
            includeSources = false;
            includeSystemImages = false;
          };

          # AGP 8.6.1 needs a Gradle 8.x; nixpkgs' default `gradle` may be 9.x.
          # The repo does not commit gradle/wrapper/gradle-wrapper.jar (see
          # .gitignore), so this is the only usable gradle in CI.
          gradlePackage =
            if pkgs ? gradle_8 then pkgs.gradle_8 else pkgs.gradle;
          jdkPackage = pkgs.jdk17;
          pythonPackage = pkgs.python3.withPackages (ps: [ ps.pip ]);
          androidSdkRoot = "${android.androidsdk}/libexec/android-sdk";
          # Must match compileSdk 35; AGP runs this aapt2 instead of the Maven one.
          buildToolsVersion = "35.0.0";
          buildToolsDir = "${androidSdkRoot}/build-tools/${buildToolsVersion}";
        in
        {
          default = pkgs.mkShell {
            packages = [
              gradlePackage
              jdkPackage
              pkgs.rustup
              pkgs.protobuf
              pythonPackage
              pkgs.git
              pkgs.curl
              pkgs.cacert
              pkgs.gawk
              # Native builds: xkbcommon/xkeyboard-config/PulseAudio use meson,
              # libsndfile uses cmake, libltdl uses configure + make + m4.
              pkgs.meson
              pkgs.ninja
              pkgs.cmake
              pkgs.pkg-config
              pkgs.gnumake
              pkgs.gnum4
              pkgs.bison
              pkgs.file
              pkgs.gnutar
              pkgs.gzip
              pkgs.xz
              pkgs.unzip
              pkgs.zip
              pkgs.android-tools
              pkgs.usbutils
              android.androidsdk
            ];

            JAVA_HOME = "${jdkPackage.home}";
            ANDROID_HOME = androidSdkRoot;
            ANDROID_SDK_ROOT = androidSdkRoot;
            ANDROID_NDK_HOME = "${androidSdkRoot}/ndk-bundle";
            GRADLE_OPTS = "-Dorg.gradle.project.android.aapt2FromMavenOverride=${buildToolsDir}/aapt2";
            # The Nix-provided curl/git ignore the host CA bundle.
            SSL_CERT_FILE = "${pkgs.cacert}/etc/ssl/certs/ca-bundle.crt";
            NIX_SSL_CERT_FILE = "${pkgs.cacert}/etc/ssl/certs/ca-bundle.crt";

            shellHook = ''
              export GRADLE_USER_HOME="''${XDG_CACHE_HOME:-$HOME/.cache}/gradle"
              export ANDROID_USER_HOME="''${XDG_STATE_HOME:-$HOME/.local/state}/android"
              export CARGO_HOME="''${CARGO_HOME:-$HOME/.cargo}"
              export RUSTUP_HOME="''${RUSTUP_HOME:-$HOME/.rustup}"
              export PATH="${pythonPackage}/bin:$PATH"
              export PATH="$CARGO_HOME/bin:$PATH"
              export PATH="${androidSdkRoot}/platform-tools:${buildToolsDir}:$PATH"

              if ! rustup toolchain list | grep -q '^stable '; then
                rustup toolchain install stable --profile minimal
              fi
              rustup default stable >/dev/null

              echo "Android SDK: $ANDROID_SDK_ROOT"
              echo "Android NDK: $ANDROID_NDK_HOME"
              echo "JDK: $JAVA_HOME"
              echo "Gradle: $(gradle --version | awk '/^Gradle / { print $2; exit }')"
              echo "Full native + APK build:"
              echo "  bash native/build-xkbcommon.sh aarch64"
              echo "  bash scripts/fetch-xkbdata.sh"
              echo "  bash native/build-android-aarch64.sh"
              echo "  bash native/build-proot.sh"
              echo "  bash native/build-pulse.sh"
              echo "  gradle :app:assembleDebug"
            '';
          };
        });
    };
}
