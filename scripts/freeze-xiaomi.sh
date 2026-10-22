#!/usr/bin/env bash
# Optional: freezes the Xiaomi launcher stack and telemetry so nothing restarts in the background.
# Everything here is reversible with scripts/rollback.sh. Verified on Mi TV 4A 32 (magnolia), Android 9.
set -euo pipefail
TV="${1:?usage: freeze-xiaomi.sh <tv-ip>}"
adb connect "$TV"
for p in \
  com.google.android.tvlauncher \
  com.google.android.tvrecommendations \
  com.google.android.leanbacklauncher.recommendations \
  com.mitv.tvhome.atv \
  com.mitv.tvhome.michannel \
  com.mitv.tvhome.oemtab \
  com.xiaomi.android.tvsetup.partnercustomizer \
  com.miui.tv.analytics \
  com.xiaomi.statistic \
  com.google.android.tv.bugreportsender \
  co.sensara.tv.mitv \
  co.sensara.tv.mitv.res \
  com.xiaomi.mitv.updateservice \
  com.mitv.milinkservice \
  com.google.android.katniss \
  com.android.vending \
  com.google.android.videos \
  com.android.printspooler; do
  adb shell pm disable-user --user 0 "$p" || true
done
echo "Frozen. Re-enable any package with: adb shell pm enable <package>"
