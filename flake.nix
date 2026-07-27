{
  description = "Talkcan Android PTT scaffold development environment";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
    omni-keymap = {
      url = "github:nilp0inter/OmniKeymap/main";
      flake = false;
    };
    sleepwalker = {
      url = "github:nilp0inter/sleepwalker/aeb5adc6c48d3f8f779314b7401964f6c5e5e905";
      flake = false;
    };
  };

  outputs = { nixpkgs, flake-utils, omni-keymap, sleepwalker, ... }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        pkgs = import nixpkgs {
          inherit system;
          config = {
            allowUnfree = true;
            android_sdk.accept_license = true;
          };
        };

        androidBuildToolsVersion = "35.0.0";

        androidComposition = pkgs.androidenv.composeAndroidPackages {
          buildToolsVersions = [ androidBuildToolsVersion ];
          platformVersions = [ "31" "35" ];
          includeEmulator = false;
          includeCmake = false;
          includeSources = false;
          includeSystemImages = false;
          includeNDK = true;
        };

        androidSdk = androidComposition.androidsdk;
        androidNdk = androidComposition.ndk-bundle;

        kotlinDebugAdapter = pkgs.stdenvNoCC.mkDerivation {
          pname = "kotlin-debug-adapter";
          version = "0.4.4";
          src = pkgs.fetchurl {
            url = "https://github.com/fwcd/kotlin-debug-adapter/releases/download/0.4.4/adapter.zip";
            hash = "sha256-OHTLre0P24Ipo4EWeJWwpsr4i3rf+rxpD89ab7ZdEbY=";
          };
          dontUnpack = true;
          nativeBuildInputs = [ pkgs.unzip ];
          installPhase = ''
            unzip -q "$src" -d "$TMPDIR"
            mkdir -p "$out"
            cp -R "$TMPDIR/adapter/." "$out"
          '';
        };

        talkcanAndroidDebugAdapter = pkgs.writeShellApplication {
          name = "talkcan-android-debug-adapter";
          runtimeInputs = [ androidSdk kotlinDebugAdapter pkgs.jdk17 pkgs.coreutils pkgs.python3 ];
          text = ''
            set -eu

            readonly appId=io.talkcan
            readonly forwardPort=37099

            # </dev/null on every adb call: `adb shell` forwards its stdin to
            # the device, so an unredirected adb would swallow the client's DAP
            # bytes (the initialize request) off this script's stdin before the
            # proxy ever reads them.
            devicePid="$(adb shell pidof "$appId" </dev/null | tr -d '\r' || true)"
            case "$devicePid" in
              "" | *[!0-9]*)
                echo "No debuggable $appId process found. Install and launch the debug app before attaching." >&2
                exit 1
                ;;
            esac

            adb forward --remove "tcp:$forwardPort" </dev/null >/dev/null 2>&1 || true
            adb forward "tcp:$forwardPort" "jdwp:$devicePid" </dev/null >/dev/null

            # exec: the proxy (not an intermediate bash) becomes OMP's direct child.
            exec python3 ${./.omp/android-dap-proxy.py}
          '';
        };
      in
      {
        devShells.default = pkgs.mkShell {
          packages = [
            pkgs.rtk
            androidSdk
            pkgs.gradle
            pkgs.jdk17
            pkgs.kotlin
            pkgs.kotlin-language-server
            kotlinDebugAdapter
            talkcanAndroidDebugAdapter
            (pkgs.python3.withPackages (p: [
              p.huggingface-hub
              p.hf-transfer
            ]))
          ];

          ANDROID_HOME = "${androidSdk}/libexec/android-sdk";
          ANDROID_SDK_ROOT = "${androidSdk}/libexec/android-sdk";
          ANDROID_NDK_HOME = "${androidNdk}/libexec/android-sdk/ndk-bundle";
          ANDROID_NDK_ROOT = "${androidNdk}/libexec/android-sdk/ndk-bundle";
          NDK_DIR = "${androidNdk}/libexec/android-sdk/ndk-bundle";
          JAVA_HOME = pkgs.jdk17.home;
          GRADLE_OPTS = "-Dorg.gradle.project.android.aapt2FromMavenOverride=${androidSdk}/libexec/android-sdk/build-tools/${androidBuildToolsVersion}/aapt2";
          HF_HUB_ENABLE_HF_TRANSFER = "1";
          OMNI_KEYMAP_PATH = "${omni-keymap}";
          SLEEPWALKER_CORE_PATH = "${sleepwalker}/android/sleepwalker-core";

          shellHook = ''
            export GRADLE_USER_HOME="$PWD/.gradle"
          '';
        };
      });
}
