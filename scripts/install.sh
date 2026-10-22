#!/usr/bin/env bash
# Installs Clean TV on an Android TV over ADB and makes it the home screen.
# Usage: scripts/install.sh <tv-ip> [path/to/app-debug.apk]
set -euo pipefail
TV="${1:?usage: install.sh <tv-ip> [apk]}"
APK="${2:-app/build/outputs/apk/debug/app-debug.apk}"
PKG=app.cleantv

adb connect "$TV"
adb shell settings put global verifier_verify_adb_installs 0
adb install -r -t "$APK"
adb shell settings put global verifier_verify_adb_installs 1

# Lets the launcher set the system screensaver from its own menu.
adb shell pm grant $PKG android.permission.WRITE_SECURE_SETTINGS
# Key filter: turns the remote's Mi (PatchWall) button into launcher toggle and screensaver.
adb shell settings put secure enabled_accessibility_services $PKG/$PKG.KeyFilterService
adb shell settings put secure accessibility_enabled 1
# Make it Home.
adb shell cmd package set-home-activity $PKG/.HomeActivity
adb shell am start -n $PKG/.HomeActivity
echo "Clean TV installed and set as Home."
