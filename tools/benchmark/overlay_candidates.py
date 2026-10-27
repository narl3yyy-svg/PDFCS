#!/usr/bin/env python3
"""Draws a scene's candidate outlines from report.txt (best-scored in red, truth in green).

    overlay_candidates.py report.txt scene_x.jpg OUT.jpg [TOP]
"""
import json
import re
import sys

from PIL import Image, ImageDraw

report, photo, out = sys.argv[1:4]
top = int(sys.argv[4]) if len(sys.argv) > 4 else 3
name = photo.split("/")[-1]
lines = open(report).read().splitlines()
start = next(i for i, l in enumerate(lines) if l.startswith(name))
cands = []
for l in lines[start + 1:]:
    if not l.startswith("    "):
        break
    m = re.search(r"q=(\S+) score=([\d.]+)", l)
    if m:
        pts = [tuple(float(v) / 1000 for v in p.split(":")) for p in m.group(1).split(",")]
        cands.append((float(m.group(2)), pts))
im = Image.open(photo).convert("RGB")
d = ImageDraw.Draw(im)
truth = json.load(open(photo[:-4] + ".json"))
d.line([tuple(p) for p in truth["corners"]] + [tuple(truth["corners"][0])], fill=(0, 220, 0), width=12)
colours = [(255, 0, 0), (255, 140, 0), (0, 120, 255)]
for k, (score, pts) in enumerate(sorted(cands, reverse=True)[:top]):
    xy = [(x * im.width, y * im.height) for x, y in pts]
    d.line(xy + [xy[0]], fill=colours[k % 3], width=10)
im.resize((450, 600)).save(out)
