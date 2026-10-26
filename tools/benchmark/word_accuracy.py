#!/usr/bin/env python3
"""Word accuracy of a rebuilt PDF against the original document (needs poppler's pdftotext).

Compares the bag of words on matching pages, case-sensitively, and lists what is missing
and what is extra (misread).

    word_accuracy.py original.pdf rebuilt.pdf ORIG_PAGE:OUT_PAGE [...]
"""
import collections
import re
import subprocess
import sys


def words(pdf, page):
    text = subprocess.run(["pdftotext", "-f", str(page), "-l", str(page), pdf, "-"],
                          capture_output=True, text=True, check=True).stdout
    return re.findall(r"[A-Za-z0-9][A-Za-z0-9.\-/+°µ²=]*", text)


def main(orig, out, pairs):
    total = hit = 0
    for po, pn in pairs:
        o = collections.Counter(words(orig, po))
        n = collections.Counter(words(out, pn))
        common = sum((o & n).values())
        count = sum(o.values())
        print(f"original p{po} vs rebuilt p{pn}: {common}/{count} words = {100 * common / max(1, count):.1f}%")
        print("   missing:", list((o - n).elements())[:25])
        print("   misread/extra:", list((n - o).elements())[:25])
        total += count
        hit += common
    print(f"overall {hit}/{total} = {100 * hit / max(1, total):.1f}%")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2], [tuple(int(x) for x in p.split(":")) for p in sys.argv[3:]])
