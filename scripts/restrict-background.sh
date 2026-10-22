#!/usr/bin/env bash
# Stops streaming apps from waking up at boot and sitting in memory unopened.
# They still launch normally from the launcher. Reversible: replace "deny" with "allow".
set -euo pipefail
TV="${1:?usage: restrict-background.sh <tv-ip> [package ...]}"; shift || true
PKGS=("$@")
if [ ${#PKGS[@]} -eq 0 ]; then
  PKGS=(in.startv.hotstar com.amazon.amazonvideo.livingroom com.sonyliv com.mitv.tvhome.mitvplus \
        com.google.android.play.games com.google.android.youtube.tvmusic com.android.vending \
        com.ionitech.airscreen org.videolan.vlc com.phlox.tvwebbrowser com.apple.atve.androidtv.appletv)
fi
adb connect "$TV"
for p in "${PKGS[@]}"; do
  if adb shell pm list packages | tr -d '\r' | grep -q "^package:$p$"; then
    adb shell cmd appops set "$p" RUN_ANY_IN_BACKGROUND deny
    adb shell cmd appops set "$p" RUN_IN_BACKGROUND deny
    adb shell am force-stop "$p"
    echo "restricted $p"
  fi
done
