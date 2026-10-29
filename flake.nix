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

          android = pkgs.androidenv.composeAndroidPackages {
            platformVersions = [ "35" ];
            buildToolsVersions = [ "34.0.0" "35.0.0" ];
            includeNDK = true;
            includeEmulator = false;
            includeSources = false;
            includeSystemImages = false;
          };

          gradlePackage =
            if pkgs ? gradle_8 then pkgs.gradle_8 else pkgs.gradle;
          jdkPackage = pkgs.jdk17;
          pythonPackage = pkgs.python3.withPackages (ps: [ ps.pip ]);
          androidSdkRoot = "${android.androidsdk}/libexec/android-sdk";
          buildToolsVersion = "34.0.0";
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
              pkgs.gawk
              pkgs.file
              pkgs.gnutar
              pkgs.gzip
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
              echo "Build APK: gradle :app:assembleDebug"
              echo "Build native artifacts first: bash native/build-android-aarch64.sh"
              echo "Build proot separately: bash native/build-proot.sh"
            '';
          };
        });
    };
}
