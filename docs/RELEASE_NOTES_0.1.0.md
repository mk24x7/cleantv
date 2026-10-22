Clean TV 0.1.0

First public build. Verified on a Mi TV 4A 32 (magnolia, Android 9).

- Inputs panel with live HDMI and AV state, boot straight into the chosen input
- Free live channels: public broadcaster HLS streams and broadcasters' YouTube live feeds, user override via /sdcard/CleanTV/channels.m3u
- Apps grid from installed banners, reorder and hide, Tools row for utilities
- USB drive tiles
- Screensavers: Matrix, Mystify, Starfield, Pipes, launcher-only idle timer with adjustable delay
- Memory tile with free RAM, background sweep 30 s after the launcher opens
- Send to TV: stream a video, a link or photos from a phone or laptop; nothing is uploaded
- Home opens the launcher; the Mi button toggles launcher and last app, long press starts the screensaver
- Scripts: install, restrict-background, freeze-xiaomi, rollback

Install: scripts/install.sh <tv-ip> cleantv-release.apk
