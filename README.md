# Bike Screen

**Turn an old Android screen into a cast target for your phone.** No ads, no accounts, no setup screens.

Some screens run Android but can't receive casts from your phone: exercise-bike and treadmill tablets, cheap TV boxes, old tablets on a wall. Usually there's no Google Play on them, and the free "cast receiver" apps you can sideload are full of ads that won't even load without Google services. Bike Screen is a small, ad-free receiver for exactly that case:

1. Install it once (sideload, see below). It starts by itself every time the device boots.
2. On your phone, tap the app's cast / 投屏 button. The device shows up by name (default **"Bike Screen"**).
3. The video plays full screen on the device. Play, pause, seek and stop from your phone, or tap the screen for controls; it closes itself when you stop.

It speaks **DLNA / UPnP** (as a MediaRenderer), the open casting standard many video apps use, Douyin's 投屏 among them. It is **not** Chromecast or AirPlay. It runs on Android 5.0+ (API 21), plays video with ExoPlayer on the device's hardware decoder, starts playback after 0.5 s of buffering, and the APK is about 2.3 MB (2 MB of it is the bundled title font). Optionally, it can also make every app on the device louder (see *Loudness boost*).

> Unofficial hobby project. Not affiliated with, endorsed by, or connected to Peloton Interactive, ByteDance, Douyin or the Digital Living Network Alliance. Product names are used only to describe what the app was tested with; all trademarks belong to their owners. Installing apps on a device you own may be restricted by that device's terms of service. Check them first.

## Status

| | |
|---|---|
| **Tested** | Douyin 投屏 → Peloton Bike+ screen (model RB1VO, Android 11, no GMS); global loudness boost on the same device |
| **Untested** | Every other device and every other casting app. Standard DLNA controllers should work, but this hasn't been checked. |
| **Not supported** | AirPlay, screen mirroring, Google Cast/Chromecast, local media browsing |

A hobby project shared as-is: issues and pull requests are welcome, but no support or fixes are promised.

## Details

- Starts at boot as a foreground service and announces itself on the LAN over SSDP every 10 s. It holds Wi-Fi, multicast and partial wake locks so it always answers. This costs some idle power.
- A cast opens a full-screen player and wakes the screen. Stopping on the phone, or pressing Back, closes the player.
- **On-screen controls:** tap the video for a bar that hides after 6 s: -10 s, pause, +10 s, speed (1 / 1.25 / 1.5 / 2x, kept across videos), **Hold** and **Next**. Hold finishes the current video even when the phone moves on, then plays the phone's latest pick (tap Hold off to play it now); while it is on, the phone's Stop waits too (Back still stops). The phone keeps the playlist, and DLNA gives the screen no way to ask for another video, so Next freezes the picture and reports the video's last 2 s and then its end; the phone then sends its next video. Douyin needs that run-up: a bare stop, or a jump straight to the end, reads as a dropout and it re-sends the same video. There is no Previous. Between videos the screen stays up with *Loading…* for up to 4 s.
- **Title:** the video's title (from the phone's cast metadata, hashtags dropped) shows at the top for 8 s when a video starts and whenever the controls are up. It uses a bundled Noto Sans SC subset (~2 MB, `tools/subset-font.py`), so Chinese titles render even on devices without a CJK font.
- **Fast start:** playback begins after 0.5 s of buffered video (ExoPlayer's default is 2.5 s).
- **Volume:** the phone's volume slider scales the player's own gain, which defaults to 0.55 (`CAST_GAIN`). The device's system volume is never changed.
- **Loudness boost** (`BOOST_MB`, default +6 dB): a `LoudnessEnhancer` on audio session 0, the global output mix, to lift quiet apps. Session 0 effects are deprecated, but **verified working on the Peloton RB1VO (Android 11, MediaTek)**: 0 / +6 / +12 dB steps were clearly audible in Jellyfin's integrated player. Other vendor builds may silently ignore them. `enabled=true` in the log only means the effect was created; only listening confirms it works. The service also attaches to any app that sends `ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION`. Casts are corrected for the boost, so their net gain stays at `CAST_GAIN`. To change it on a running device:
  `adb shell am broadcast -a io.github.bikescreen.BOOST --ei mb 800 -p io.github.bikescreen` (0 = off, max 2000)

## Build

Needs JDK 17 and the Android SDK (platform 34, build-tools 34). The Gradle wrapper (8.7) handles everything else.

```sh
echo "sdk.dir=/path/to/android/sdk" > local.properties
./gradlew assembleRelease          # → app/build/outputs/apk/release/app-release.apk
```

Optional settings in `local.properties`, which is git-ignored:

| Key | Default | Effect |
|---|---|---|
| `friendlyName` | `Bike Screen` | Name shown in the phone's cast list |
| `debugEndpoints` | `false` | Serves `GET /log` and `GET /cmd/boost` on the LAN (see below) |
| `signing.storeFile`, `signing.storePassword`, `signing.keyAlias`, `signing.keyPassword` | unset | Signs with your own key. Without them, release builds are signed with the Android **debug key**. |

Never commit a keystore. If you switch keys later, uninstall the old build first, because Android refuses an update signed with a different key.

## Install (sideload)

Enable developer options and USB or Wi-Fi debugging on the device.

**Without building:** download `bike-screen-<version>.apk` from [Releases](../../releases), check its SHA-256 against the release notes, then:

```sh
adb install bike-screen-1.3.apk
adb shell appops set io.github.bikescreen SYSTEM_ALERT_WINDOW allow
adb shell dumpsys deviceidle whitelist +io.github.bikescreen
adb shell am start -n io.github.bikescreen/.PlayerActivity
```

Release APKs use the default name "Bike Screen" and leave the LAN endpoints off; build it yourself to change either.

**From source:**

```sh
./install.sh usb             # or: ./install.sh <device-ip>   (Wi-Fi adb on :5555)
```

This installs the APK and runs three one-time `adb shell` steps:

- `appops set io.github.bikescreen SYSTEM_ALERT_WINDOW allow`: on Android 10+, this lets the background service open the player.
- `dumpsys deviceidle whitelist +io.github.bikescreen`: keeps the receiver alive when the device idles.
- Launches the app once. Android only delivers the boot broadcast to an app that has been launched before.

## LAN endpoints

Everything listens on TCP 49152 (falling back to a random port) plus SSDP on UDP 1900. There is **no authentication**: anyone on your network can cast, pause or stop. Use it on a home network you trust.

| Path | Purpose |
|---|---|
| `/desc.xml`, `/AVTransport.xml`, `/RenderingControl.xml`, `/ConnectionManager.xml` | UPnP device and service descriptions |
| `POST /<Service>/control`, `SUBSCRIBE /<Service>/event` | SOAP control and GENA events |
| `GET /log` | *debugEndpoints only.* The last 1000 log lines. Cast URLs have their query strings (signed tokens) replaced with `?…`. |
| `GET /cmd/boost[?mb=N]` | *debugEndpoints only.* Read or set the loudness boost |

Logcat tag: `bike-dlna`, at WARN level, because some vendor builds drop INFO.

## Layout

```
app/src/main/java/io/github/bikescreen/
  dlna/Renderer.java    SSDP, HTTP, SOAP, GENA: plain Java, no Android imports
  dlna/Scpd.java        service descriptions
  BikeService.java      foreground service: ExoPlayer, locks, boost
  PlayerActivity.java   full-screen surface
  BootReceiver.java
tools/DesktopMain.java  runs the renderer on a desktop JVM with a fake player, for protocol debugging
```

## License

Apache License 2.0. See [LICENSE](LICENSE). Third-party components are listed in [NOTICE](NOTICE).
