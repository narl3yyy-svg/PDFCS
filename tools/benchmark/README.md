# Scan accuracy benchmark

How PDFCS's "Digital text" output is measured against a real document.

1. Render pages of a born-digital PDF and turn them into realistic phone photos:

   ```bash
   pdftoppm -r 340 -f 1 -l 3 -jpeg original.pdf page
   for i in 1 2 3; do python3 simulate_photo.py page-0$i.jpg photo$i.jpg $i 1.4; done
   ```

   340 dpi is what a 12 MP phone gives for a full A4 page; the blur and noise are a hand-held shot.

2. Push the photos to an emulator or a phone, then run the end-to-end scan. It imports the newest
   photos with *Images to searchable PDF*, which converts them automatically, and saves the result:

   ```bash
   adb push photo1.jpg photo2.jpg photo3.jpg /sdcard/Pictures/
   ./run_scan.sh rebuilt.pdf
   ```

3. Compare the words with the original (page mapping `original:rebuilt`):

   ```bash
   python3 word_accuracy.py original.pdf rebuilt.pdf 1:1 2:2 3:3
   ```

The score counts every word the original's text layer contains, including those inside figures,
graph axes and subscripts, so it is stricter than what a reader sees: words PDFCS could not verify
keep their original pixels and are shown correctly even when their hidden search text is off.

## Page finding and clean-up on realistic scenes

`scene.py` lays a page (a datasheet page, or a book page from `book_page.py` with its ground-truth
text) on a background, such as a wood desk, a light desk, a white desk, dark cloth or a checked
tablecloth, and photographs it at an angle, with a known outline. `make_set.sh DIR [PDF]` makes a set of
40 scenes. Set `SEEDMUL` to make a different, hold-out set.

```bash
./make_set.sh /tmp/scenes original.pdf   # the PDF supplies the datasheet pages
./gradlew testDebugUnitTest --tests '*SceneBenchmark*' -Dpdfcs.scenes=/tmp/scenes
```

This writes three reports to the scene folder:

- `report.txt`: the outline error of each photo, as a share of the page diagonal.
- `clean.txt`: after the full straightening pipeline, the remaining tilt and lean, and the background left at the book pages' edges.
- `flat.txt`: for `flat_*.jpg` images (pages already flat, such as rendered PDF pages), whether they are left whole.

The `clean_*.jpg` images show each result.
