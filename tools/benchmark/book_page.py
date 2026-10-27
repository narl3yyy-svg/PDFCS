#!/usr/bin/env python3
"""Typeset a book page (serif, justified paragraphs, running head, page number) at 300 dpi.

Writes page.png and page.txt (the exact words, for accuracy scoring).

    book_page.py page.png SEED
"""
import random
import sys

from PIL import Image, ImageDraw, ImageFont

SERIF = "/usr/share/fonts/truetype/liberation/LiberationSerif-Regular.ttf"
SERIF_I = "/usr/share/fonts/truetype/liberation/LiberationSerif-Italic.ttf"
SERIF_B = "/usr/share/fonts/truetype/liberation/LiberationSerif-Bold.ttf"
WORDS = ("the lighthouse keeper climbed stairs storm arrived before midnight rolling from west violence "
         "rattled every window old stone tower margaret lantern brass key counting steps grandfather taught "
         "hundred twelve lamp room forgiving great lens turning patient indifferent throwing beam across black "
         "water toward ships would never see somewhere beyond reef bell ringing faint irregular heartbeat rhythm "
         "wrote time logbook eleven wind northwest visibility poor barometer falling waited dawn harbour village "
         "fishermen nets morning tide gulls cliffs salt quiet letters mother winter supplies boat harbourmaster").split()


def paragraph(rnd, n):
    out = []
    for i in range(n):
        w = rnd.choice(WORDS)
        out.append(w.capitalize() if i == 0 or out[-1].endswith(".") else w)
        if rnd.random() < 0.09:
            out[-1] += rnd.choice([".", ".", ",", ";"])
    return " ".join(out).rstrip(",;") + "."


def wrap(d, text, font, width, indent):
    """Greedy line breaking by measured width, as a typesetter does."""
    lines, cur = [], []
    for w in text.split():
        avail = width - (indent if not lines else 0)
        if cur and d.textlength(" ".join(cur + [w]), font=font) > avail:
            lines.append(" ".join(cur)); cur = [w]
        else:
            cur.append(w)
    if cur:
        lines.append(" ".join(cur))
    return lines


def main(path, seed):
    rnd = random.Random(seed)
    W, H = 1650, 2550  # 5.5 × 8.5 in at 300 dpi
    img = Image.new("RGB", (W, H), "white")
    d = ImageDraw.Draw(img)
    body = ImageFont.truetype(SERIF, 46)
    head = ImageFont.truetype(SERIF_I, 38)
    title = ImageFont.truetype(SERIF_B, 72)
    words = []
    margin, top = 190, 210
    running = "THE LIGHTHOUSE KEEPER"
    d.text((W // 2 - d.textlength(running, font=head) / 2, 110), running, font=head, fill=(30, 30, 30))
    words += running.split()
    y = top
    if seed % 3 == 0:
        t = f"Chapter {seed % 20 + 1}"
        d.text((margin, y + 40), t, font=title, fill=(15, 15, 15)); words += t.split(); y += 220
    width = W - 2 * margin
    while y < H - 330:
        text = paragraph(rnd, rnd.randint(40, 110))
        lines = wrap(d, text, body, width, indent=60)
        for k, line in enumerate(lines):
            if y > H - 330:
                break
            parts = line.split()
            x = margin + (60 if k == 0 else 0)
            natural = sum(d.textlength(p, font=body) for p in parts)
            gaps = len(parts) - 1
            space = (width - (x - margin) - natural) / gaps if gaps and k < len(lines) - 1 else d.textlength(" ", font=body)
            for p in parts:
                d.text((x, y), p, font=body, fill=(25, 22, 20)); x += d.textlength(p, font=body) + space
                words.append(p)
            y += 66
        y += 22
    num = str(40 + seed)
    d.text((W // 2 - d.textlength(num, font=body) / 2, H - 170), num, font=body, fill=(30, 30, 30)); words.append(num)
    img.save(path)
    with open(path.rsplit(".", 1)[0] + ".txt", "w") as f:
        f.write(" ".join(words))


if __name__ == "__main__":
    main(sys.argv[1], int(sys.argv[2]))
