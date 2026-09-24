#!/bin/zsh
# Build (and optionally install) MAA using Android Studio's bundled JDK.
# Usage: ./build.sh                          -> assembleRelease (minified, debug-signed unless a key is set)
#        PHONE=<adb serial> ./build.sh install -> assembleRelease + install and launch on the phone
set -e
cd "$(dirname "$0")"
export JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
./gradlew assembleRelease
if [[ "$1" == "install" ]]; then
  ADB="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"
  if [[ -z "$PHONE" ]]; then
    PHONE=$("$ADB" devices | awk 'NR>1 && $2=="device" {print $1; exit}')
  fi
  if [[ -z "$PHONE" ]]; then
    echo "No phone on adb. Plug it in (USB debugging on) or set PHONE=<serial|ip:port>." >&2
    exit 1
  fi
  # Record the Play Store as installer: Android Auto hides sideloaded media apps unless its
  # developer "Unknown sources" switch is on, and it decides by installer, not by signature.
  "$ADB" -s "$PHONE" install -r -i com.android.vending app/build/outputs/apk/release/app-release.apk
  "$ADB" -s "$PHONE" shell am start -n io.github.superthom196.maa/.ui.MainActivity
fi
