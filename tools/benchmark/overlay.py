#!/usr/bin/env python3
"""Draws the true page corners (green) and the detected ones (red) over scene photos.

    overlay.py OUT.jpg scene_a.jpg [scene_b.jpg ...]
"""
import json
import sys

from PIL import Image, ImageDraw

tiles = []
for path in sys.argv[2:]:
    im = Image.open(path).convert("RGB")
    truth = json.load(open(path[:-4] + ".json"))
    d = ImageDraw.Draw(im)
    d.line([tuple(p) for p in truth["corners"]] + [tuple(truth["corners"][0])], fill=(0, 220, 0), width=14)
    try:
        f = [float(v) for v in open(path[:-4] + ".found").read().split()]
        pts = [(f[i] * im.width, f[i + 1] * im.height) for i in range(0, 8, 2)]
        d.line(pts + [pts[0]], fill=(255, 0, 0), width=14)
    except (OSError, ValueError, IndexError):
        pass
    tiles.append(im.resize((300, 400)))
out = Image.new("RGB", (310 * len(tiles), 400), "black")
for i, t in enumerate(tiles):
    out.paste(t, (i * 310, 0))
out.save(sys.argv[1])
