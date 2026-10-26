#!/usr/bin/env python3
"""Turn a clean page render into a convincing phone photo of a printed page.

Warm paper tint, uneven lighting (a shadow towards one side), the page lying on a table
with a random perspective tilt, camera blur and sensor noise.

    simulate_photo.py page.jpg photo.jpg SEED [BLUR]
"""
import random
import sys

import numpy as np
from PIL import Image, ImageChops, ImageFilter


def main(src, dst, seed, blur=1.0):
    random.seed(seed)
    page = Image.open(src).convert("RGB")
    w, h = page.size
    page = ImageChops.multiply(page, Image.new("RGB", (w, h), (238, 232, 218)))
    grad = Image.linear_gradient("L").resize((w, h)).rotate(random.choice([0, 90, 180, 270])).resize((w, h))
    dark = ImageChops.multiply(page, Image.new("RGB", (w, h), (150, 140, 125)))
    page = Image.composite(page, dark, grad.point(lambda v: 150 + v * 105 // 255))
    cw, ch = int(w * 1.22), int(h * 1.18)
    canvas = Image.new("RGB", (cw, ch), (78, 62, 50))
    ox, oy = (cw - w) // 2, (ch - h) // 2
    canvas.paste(page, (ox, oy))
    j = lambda: random.uniform(-0.025, 0.025)  # noqa: E731
    quad = [(ox + w * j(), oy + h * j()), (ox + w + w * j(), oy + h * j()),
            (ox + w + w * j(), oy + h + h * j()), (ox + w * j(), oy + h + h * j())]
    src_pts = [(ox, oy), (ox + w, oy), (ox + w, oy + h), (ox, oy + h)]
    a, b = [], []
    for (x, y), (u, v) in zip(quad, src_pts):
        a.append([x, y, 1, 0, 0, 0, -u * x, -u * y]); b.append(u)
        a.append([0, 0, 0, x, y, 1, -v * x, -v * y]); b.append(v)
    coef = np.linalg.solve(np.array(a), np.array(b))
    photo = canvas.transform((cw, ch), Image.PERSPECTIVE, tuple(coef), Image.BICUBIC, fillcolor=(78, 62, 50))
    photo = photo.filter(ImageFilter.GaussianBlur(blur))
    photo = Image.blend(photo, Image.effect_noise(photo.size, 14).convert("RGB"), 0.05)
    photo.save(dst, quality=90)


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2], int(sys.argv[3]), float(sys.argv[4]) if len(sys.argv) > 4 else 1.0)
