#!/bin/bash
# Builds the simulation set: book pages and datasheet pages photographed on every background.
#   make_set.sh OUT_DIR [DATASHEET.pdf]
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
OUT=$1; PDF=${2:-}
mkdir -p "$OUT"
cd "$OUT"
for i in 1 2 3 4; do python3 -I "$HERE/book_page.py" book$i.png $((i * 7)); done
if [ -n "$PDF" ]; then pdftoppm -r 250 -f 1 -l 4 -png "$PDF" ds; fi
n=0
for bg in wood light_desk white_desk dark_cloth tablecloth; do
  for seed in 1 2 3 4 5 6 7 8; do
    if [ $((seed % 2)) -eq 0 ] && ls ds-*.png >/dev/null 2>&1; then src=$(ls ds-*.png | sed -n "$(( (seed / 2 - 1) % 4 + 1 ))p")
    else src=book$(( (seed + 1) / 2 )).png; fi
    python3 -I "$HERE/scene.py" "$src" "scene_${bg}_$seed.jpg" $((seed * ${SEEDMUL:-101} + ${#bg})) --background $bg
    echo "scene_${bg}_$seed.jpg $src" >> sources.txt
    n=$((n + 1))
  done
done
echo "$n scenes in $OUT"
