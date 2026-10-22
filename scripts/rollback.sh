#!/usr/bin/env bash
# Puts the stock Google launcher back as Home and removes Clean TV.
set -euo pipefail
TV="${1:?usage: rollback.sh <tv-ip>}"
PKG=app.cleantv
adb connect "$TV"
adb shell pm enable com.google.android.tvlauncher
adb shell pm enable com.google.android.tvrecommendations
adb shell cmd package set-home-activity com.google.android.tvlauncher/.MainActivity || true
adb shell settings put secure enabled_accessibility_services ""
adb uninstall $PKG || true
adb reboot
echo "Stock launcher restored."
