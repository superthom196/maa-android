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
  "$ADB" -s "$PHONE" install -r app/build/outputs/apk/release/app-release.apk
  "$ADB" -s "$PHONE" shell am start -n io.github.superthom196.maa/.ui.MainActivity
fi
