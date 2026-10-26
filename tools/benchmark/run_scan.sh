#!/bin/bash
# End-to-end scan benchmark on an emulator or device: import the three newest photos, create a
# "Digital text" PDF, save it to Downloads and pull it to $1.
set -u
HERE=$(cd "$(dirname "$0")" && pwd)
ADB=${ADB:-adb}; export ADB
PKG=${PKG:-com.narl3yyy.pdfcs}
APK=${APK:-$HERE/../../app/build/outputs/apk/release/app-release.apk}
UI="$HERE/ui.sh"
$ADB install -r "$APK" >/dev/null
$ADB shell 'rm -f /sdcard/Download/*.pdf'
$ADB shell am force-stop $PKG; $ADB shell am start -n $PKG/.MainActivity >/dev/null; sleep 4
$UI text "Add"; sleep 1; $UI text "Images to searchable"; sleep 4
for x in 176 540 900; do $UI tap $x 816; sleep 0.7; done   # first row of the photo picker
$UI text "Add"; sleep 8
$UI text "Create searchable PDF"; sleep 2; $ADB logcat -c; $UI text "Start"
for _ in $(seq 1 120); do
  n=$($ADB logcat -d -s PageRebuilder:I | grep -c "page:")
  [ "$n" -ge 3 ] && break
  $ADB logcat -d -b crash | grep -q FATAL && break
  sleep 4
done
$ADB logcat -d -s PageRebuilder:I | grep "page:"
$ADB logcat -d -b crash | grep -A3 FATAL | head -5
sleep 5; $UI text "More"; sleep 1; $UI text "Save to device"; sleep 4; $UI text "SAVE"; sleep 3
F=$($ADB shell ls -t /sdcard/Download/ | head -1 | tr -d '\r'); echo "file: $F"
$ADB pull "/sdcard/Download/$F" "$1" >/dev/null
