#!/bin/zsh
# Build and install Bike Screen over Wi-Fi adb: ./install.sh <device-ip>   (USB: ./install.sh usb)
set -e
DEV=${1:?usage: ./install.sh <device-ip> | usb}
PKG=io.github.bikescreen
# JDK 17: use $JAVA_HOME if set, else Homebrew's openjdk@17 if present
[[ -z $JAVA_HOME && -d /opt/homebrew/opt/openjdk@17 ]] && export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
cd "${0:A:h}"
./gradlew -q assembleRelease
if [[ $DEV == usb ]]; then A=(adb -d); else adb connect $DEV:5555 >/dev/null; A=(adb -s $DEV:5555); fi
$A install -r app/build/outputs/apk/release/app-release.apk
$A shell appops set $PKG SYSTEM_ALERT_WINDOW allow      # lets the service open the player from the background
$A shell dumpsys deviceidle whitelist +$PKG >/dev/null   # keep the receiver alive when the bike idles
$A shell am start -n $PKG/.PlayerActivity >/dev/null     # first start; boot starts it after this
echo "installed — logs: $A logcat -s bike-dlna"
