{
  description = "File Manager Android dev shell";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = { self, nixpkgs, flake-utils }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        pkgs = import nixpkgs {
          inherit system;
          config = {
            android_sdk.accept_license = true;
            allowUnfree = true;
          };
        };

        gradlePkg = pkgs.gradle_8 or pkgs.gradle;
        jdkPkg = pkgs.jdk17;

        buildToolsVersion = "34.0.0";

        android = pkgs.androidenv.composeAndroidPackages {
          platformVersions = [ "35" ];
          buildToolsVersions = [ buildToolsVersion "35.0.0" ];
          includeNDK = false;
          includeEmulator = false;
          includeSources = false;
          includeSystemImages = false;
        };

        androidSdkRoot = "${android.androidsdk}/libexec/android-sdk";
        buildToolsDir = "${androidSdkRoot}/build-tools/${buildToolsVersion}";
      in
      {
        devShells.default = pkgs.mkShell {
          packages = [
            gradlePkg
            jdkPkg
            android.androidsdk
            pkgs.android-tools
            pkgs.usbutils
            pkgs.git
            pkgs.curl
            pkgs.python3
            pkgs.gawk
            pkgs.gnutar
            pkgs.gzip
            pkgs.unzip
            pkgs.zip
          ];

          JAVA_HOME = "${jdkPkg.home}";
          ANDROID_HOME = androidSdkRoot;
          ANDROID_SDK_ROOT = androidSdkRoot;
          GRADLE_OPTS = "-Dorg.gradle.project.android.aapt2FromMavenOverride=${buildToolsDir}/aapt2";

          shellHook = ''
            export GRADLE_USER_HOME="''${XDG_CACHE_HOME:-$HOME/.cache}/gradle"
            export ANDROID_USER_HOME="''${XDG_STATE_HOME:-$HOME/.local/state}/android"
            export PATH="${androidSdkRoot}/platform-tools:${buildToolsDir}:$PATH"

            echo "Android SDK: $ANDROID_SDK_ROOT"
            echo "JDK: $JAVA_HOME"
            echo "Build with: gradle :app:assembleDebug"
          '';
        };
      });
}
