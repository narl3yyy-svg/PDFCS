#!/usr/bin/env python3
"""Photograph a page in a varied scene, with ground truth.

Places a page image on a background (wood, light or near-white desk, dark cloth, patterned
tablecloth, optionally a pen beside it), turned by up to ±12°, with perspective, a colour
cast, a soft shadow, vignetting, blur, sensor noise and JPEG compression. Writes the photo and
a JSON file with the page's true corners (TL, TR, BR, BL, photo pixels) and conditions.

    scene.py page.png photo.jpg SEED [--background NAME] [--angle DEG]
"""
import argparse
import json
import math
import random

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

BACKGROUNDS = ["wood", "light_desk", "white_desk", "dark_cloth", "tablecloth"]


def background(kind, w, h, rnd):
    y, x = np.mgrid[0:h, 0:w].astype(np.float32)
    if kind == "wood":
        grain = np.sin((x * 0.02 + np.sin(y * 0.003) * 8) + rnd.random() * 6) * 0.5 + 0.5
        base = np.stack([105 + 40 * grain, 70 + 28 * grain, 45 + 15 * grain], -1)
    elif kind == "light_desk":
        base = np.full((h, w, 3), (196, 192, 186), np.float32)
    elif kind == "white_desk":  # nearly the paper's colour: the hard case
        base = np.full((h, w, 3), (222, 222, 218), np.float32)
    elif kind == "dark_cloth":
        base = np.full((h, w, 3), (38, 44, 70), np.float32)
    else:  # tablecloth: a red and white check
        check = ((x // 60 + y // 60) % 2).astype(np.float32)
        base = np.stack([170 + 70 * check, 60 + 170 * check, 60 + 170 * check], -1)
    base += np.random.default_rng(rnd.randrange(1 << 30)).normal(0, 6, (h, w, 1)).astype(np.float32)
    return base


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("page"); ap.add_argument("photo"); ap.add_argument("seed", type=int)
    ap.add_argument("--background", choices=BACKGROUNDS); ap.add_argument("--angle", type=float)
    a = ap.parse_args()
    rnd = random.Random(a.seed)
    page = Image.open(a.page).convert("RGB")
    pw, ph = page.size
    W, H = 3000, 4000  # a 12 MP portrait photo
    kind = a.background or rnd.choice(BACKGROUNDS)
    angle = a.angle if a.angle is not None else rnd.uniform(-12, 12)
    fill = rnd.uniform(0.55, 0.92)
    s = min(W * fill / pw, H * fill / ph)
    cw, ch = pw * s, ph * s
    cx = W / 2 + rnd.uniform(-1, 1) * (W - cw) * 0.15
    cy = H / 2 + rnd.uniform(-1, 1) * (H - ch) * 0.15
    t = math.radians(angle)
    def rot(px, py):
        return (cx + px * math.cos(t) - py * math.sin(t), cy + px * math.sin(t) + py * math.cos(t))
    j = lambda: rnd.uniform(-0.03, 0.03) * min(cw, ch)  # noqa: E731 (perspective)
    corners = [rot(-cw / 2 + j(), -ch / 2 + j()), rot(cw / 2 + j(), -ch / 2 + j()),
               rot(cw / 2 + j(), ch / 2 + j()), rot(-cw / 2 + j(), ch / 2 + j())]
    # Perspective transform from photo pixels to page pixels.
    src = [(0, 0), (pw, 0), (pw, ph), (0, ph)]
    A, B = [], []
    for (x, y), (u, v) in zip(corners, src):
        A.append([x, y, 1, 0, 0, 0, -u * x, -u * y]); B.append(u)
        A.append([0, 0, 0, x, y, 1, -v * x, -v * y]); B.append(v)
    coef = np.linalg.solve(np.array(A), np.array(B))
    paper = page.convert("RGBA")
    warped = paper.transform((W, H), Image.PERSPECTIVE, tuple(coef), Image.BICUBIC)
    bg = Image.fromarray(background(kind, W, H, rnd).clip(0, 255).astype(np.uint8))
    if rnd.random() < 0.5:  # a pen beside the page
        d = ImageDraw.Draw(bg)
        x0, y0 = rnd.uniform(0, W), rnd.uniform(0, H)
        ang = rnd.uniform(0, math.pi)
        d.line([(x0, y0), (x0 + 900 * math.cos(ang), y0 + 900 * math.sin(ang))], fill=(20, 40, 120), width=36)
    photo = Image.alpha_composite(bg.convert("RGBA"), warped).convert("RGB")
    arr = np.asarray(photo).astype(np.float32)
    y, x = np.mgrid[0:H, 0:W].astype(np.float32)
    # Light: a gradient from a random side, a soft shadow (a hand or the phone), vignetting.
    gx, gy = math.cos(rnd.uniform(0, 6.28)), math.sin(rnd.uniform(0, 6.28))
    light = 0.82 + 0.18 * ((x / W - 0.5) * gx + (y / H - 0.5) * gy) * 2
    sx, sy, sr = rnd.uniform(0, W), rnd.uniform(0, H), rnd.uniform(500, 1300)
    light *= 1 - 0.28 * np.exp(-(((x - sx) ** 2 + (y - sy) ** 2) / (2 * sr ** 2)))
    light *= 1 - 0.25 * (((x / W - 0.5) ** 2 + (y / H - 0.5) ** 2) * 2)
    cast = np.array([rnd.uniform(0.94, 1.04), 1.0, rnd.uniform(0.88, 1.02)], np.float32)
    arr = arr * light[..., None] * cast
    photo = Image.fromarray(arr.clip(0, 255).astype(np.uint8))
    photo = photo.filter(ImageFilter.GaussianBlur(rnd.uniform(0.8, 1.6)))
    noise = np.random.default_rng(a.seed).normal(0, 4, (H, W, 1))
    photo = Image.fromarray((np.asarray(photo) + noise).clip(0, 255).astype(np.uint8))
    photo.save(a.photo, quality=88)
    with open(a.photo.rsplit(".", 1)[0] + ".json", "w") as f:
        json.dump({"corners": corners, "angle": angle, "background": kind, "fill": fill,
                   "width": W, "height": H}, f)


if __name__ == "__main__":
    main()
