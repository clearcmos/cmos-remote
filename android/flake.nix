{
  description = "CMOS Remote Android app development environment";

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
            allowUnfree = true;
            android_sdk.accept_license = true;
          };
        };

        buildToolsVersion = "35.0.0";

        androidComposition = pkgs.androidenv.composeAndroidPackages {
          platformVersions = [ "35" ];
          buildToolsVersions = [ buildToolsVersion ];
          includeEmulator = false;
          includeNDK = false;
          includeSources = false;
          includeSystemImages = false;
        };

        androidSdk = androidComposition.androidsdk;
      in
      {
        devShells.default = pkgs.mkShell {
          buildInputs = with pkgs; [
            androidSdk
            jdk17
            gradle
          ];

          ANDROID_HOME = "${androidSdk}/libexec/android-sdk";
          ANDROID_SDK_ROOT = "${androidSdk}/libexec/android-sdk";
          JAVA_HOME = "${pkgs.jdk17}";

          shellHook = ''
            export PATH="$ANDROID_HOME/platform-tools:$PATH"
            export PATH="$ANDROID_HOME/build-tools/${buildToolsVersion}:$PATH"

            echo "CMOS Remote Android development environment loaded"
            echo "ANDROID_HOME: $ANDROID_HOME"
            echo "Java: $(java -version 2>&1 | head -n 1)"
            echo ""
            echo "Commands:"
            echo "  ./gradlew assembleDebug   - Build debug APK"
            echo "  ./gradlew installDebug    - Build and install to connected device"
            echo "  adb devices               - List connected devices"
            echo ""
          '';
        };
      }
    );
}
