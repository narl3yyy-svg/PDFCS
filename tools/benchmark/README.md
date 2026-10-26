# Scan accuracy benchmark

How PDFCS's "Digital text" output is measured against a real document.

1. Render pages of a born-digital PDF and turn them into realistic phone photos:

   ```bash
   pdftoppm -r 340 -f 1 -l 3 -jpeg original.pdf page
   for i in 1 2 3; do python3 simulate_photo.py page-0$i.jpg photo$i.jpg $i 1.4; done
   ```

   340 dpi is what a 12 MP phone gives for a full A4 page; the blur and noise are a hand-held shot.

2. Push the photos to an emulator or a phone, then run the end-to-end scan:

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
