#!/bin/bash
# End-to-end scan benchmark on an emulator or device: import the N newest photos (default 3) with
# "Images to searchable PDF" (converted automatically), save the result to Downloads and pull it
# to $1.
set -u
HERE=$(cd "$(dirname "$0")" && pwd)
ADB=${ADB:-adb}; export ADB
PKG=${PKG:-com.narl3yyy.pdfcs}
APK=${APK:-$HERE/../../app/build/outputs/apk/release/app-release.apk}
N=${N:-3}
UI="$HERE/ui.sh"
$ADB install -r "$APK" >/dev/null
$ADB shell 'rm -f /sdcard/Download/*.pdf'
$ADB shell am force-stop $PKG; $ADB shell am start -n $PKG/.MainActivity >/dev/null; sleep 4
$UI text "Add"; sleep 1; $UI text "Images to searchable"; sleep 4
xs=(176 540 900)
for i in $(seq 0 $((N - 1))); do $UI tap ${xs[$i]} 816; sleep 0.7; done   # first row of the photo picker
$ADB logcat -c
$UI text "Add"
for _ in $(seq 1 200); do
  n=$($ADB logcat -d -s PageRebuilder:I | grep -c "page:")
  [ "$n" -ge "$N" ] && $ADB logcat -d -s OcrWorker:I | grep -q "finished:\|failed:" && break
  $ADB logcat -d -b crash | grep -q FATAL && break
  sleep 4
done
$ADB logcat -d -s PageRebuilder:I | grep "page:"
$ADB logcat -d -s OcrWorker:I | grep "finished:\|failed:" | head -1
$ADB logcat -d -b crash | grep -A3 FATAL | head -5
sleep 3; $UI text "More"; sleep 1; $UI text "Save to device"; sleep 4; $UI text "SAVE"; sleep 3
F=$($ADB shell ls -t /sdcard/Download/ | head -1 | tr -d '\r'); echo "file: $F"
$ADB pull "/sdcard/Download/$F" "$1" >/dev/null
