#!/bin/bash
# Tiny adb UI driver for the emulator harness.
#   ui.sh tap X Y | ui.sh text "Label or content description" | ui.sh shot NAME
ADB=${ADB:-adb}
OUT=${OUT:-/tmp/pdfcs-bench}
mkdir -p "$OUT"
case "$1" in
  tap) $ADB shell input tap "$2" "$3" ;;
  text)
    $ADB shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
    $ADB pull /sdcard/ui.xml "$OUT/ui.xml" >/dev/null 2>&1
    xy=$(python3 -I - "$2" "$OUT/ui.xml" <<'PY'
import re, sys
want, path = sys.argv[1].lower(), sys.argv[2]
xml = open(path).read()
for m in re.finditer(r'<node [^>]*>', xml):
    n = m.group(0)
    label = re.search(r' text="([^"]*)"', n).group(1) + '|' + re.search(r'content-desc="([^"]*)"', n).group(1)
    if want in label.lower():
        a = list(map(int, re.findall(r'\d+', re.search(r'bounds="([^"]*)"', n).group(1))))
        print((a[0] + a[2]) // 2, (a[1] + a[3]) // 2)
        break
PY
)
    [ -z "$xy" ] && { echo "NOT FOUND: $2" >&2; exit 1; }
    $ADB shell input tap $xy ;;
  shot) $ADB exec-out screencap -p > "$OUT/$2.png" ;;
esac
