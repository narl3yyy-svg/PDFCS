# PDFCS

**Turn physical books into real digital PDFs with your phone, then read, search, edit and convert them.**

PDFCS photographs each page of a book or a printed document, straightens and cleans it, reads it
with on-device OCR, and rebuilds every page as a born-digital PDF page:

- The text is real text in real fonts, so you can select it, highlight it, copy it and search it in any PDF reader.
- The words are placed where they were printed, at the same size, weight (bold) and slant (italic), and in their original colour.
- Figures, photos, tables, rules and logos are cut out cleanly and placed as pictures.
- The paper turns pure white, and every page of a document gets the same size.

On top of that, PDFCS is a complete PDF tool for your phone: read, search and annotate, organise
pages, combine and split, add passwords, fill forms, add watermarks and page numbers, analyse
documents, and convert them to text, Word or images.

Everything runs on your phone. There is no account, no ads, no analytics and no cloud.

## How accurate is it?

OCR can never be guaranteed perfect. PDFCS therefore **checks every word against the page before printing it**:

1. Each recognised word is typeset and compared with the ink in the photo, allowing a one-pixel tolerance.
2. A word that doesn't match gets a second reading on its own, enlarged. Common typographic look-alikes are also tried, such as • for "+" and ² for "?".
3. If a candidate's shape matches the ink, it is printed as text.
4. If nothing matches, the word keeps its **original pixels**, with invisible text on top so it can still be searched.

So a misreading is never printed as wrong text: what you see is either verified text or the
original print.

The `tools/benchmark` folder contains the measurement. It photographs pages lying on wood, light and
white desks, dark cloth and a checked tablecloth, at an angle and with uneven light, blur and noise,
runs them through the app on an emulator, and compares the result with the original text:

- **Book pages:** 99.2–100% of words come out word-exact (9 pages on 5 backgrounds).
- **The ST USBLC6-2 datasheet:** 96% on its first page and 82% over three pages, including tables and graph labels.
- The rest are mostly subscripts (V<sub>RM</sub>), 6-pt graph labels and symbols. These are shown as the original pixels, so they look right, but their hidden search text may differ.

On 40 hold-out photo scenes the pages came out level in all 40 and upright in 39, and the background was removed from all 20 book pages.

## Features

### Scan books
- **Book-scanning camera**: full-resolution 4:3 capture with grid, flash and torch. Shoot with the **volume keys**, or use **auto-capture** every few seconds while you turn pages.
- **Automatic page detection and background removal**: each possible outline of the sheet is checked against what a page looks like, so the desk or tablecloth is cropped away, while bands printed along the page's edge are kept. You can drag the corners to adjust.
- **Straight, aligned pages**: the page is levelled from its text lines and leaning columns are stood upright. In the rebuilt page every line sits on one baseline, flush with the margins.
- **Lighting clean-up**: uneven light, colour casts and the shadow near the spine are evened out per colour channel, so the paper turns white and coloured ink stays coloured.
- **Two-page spreads** split into left and right pages. Looks: Enhanced, Grayscale, Black & white, Original.
- **Output**:
  - *Digital text* (default): rebuilt pages, for Latin, Greek and Cyrillic scripts.
  - *Original photos*: the photo with an invisible text layer, for every OCR language.
- **OCR** with Tesseract LSTM (`tessdata_best`). English is built in, and about 30 more languages can be downloaded in Settings and combined.
- **Long books**: conversion runs in the background with a progress notification. Your scan is saved as you go.
- **Automatic OCR on import**: scanned PDFs and photos you import turn into searchable digital PDFs by themselves. You can turn this off in Settings.

### Read
- Swipe between pages, pinch or double-tap to zoom, scrub through long books, jump to a page.
- **Full-text search** that ignores case and accents and finds hyphenated words. Matches are highlighted on the page and listed with their page numbers.
- **Select text** with a long-press and drag, then copy it.

### Comment
- Highlight, underline and strike out selected text.
- Sticky notes and freehand drawing with a pen or highlighter.
- A list of all comments, with delete.

### Organise and edit
- **Organise pages**: rotate, delete, move, duplicate, insert blank pages, reverse. Bookmarks to deleted pages are cleaned up, and the last page can't be deleted.
- Insert another PDF, extract pages, split into parts, and combine documents from the library.
- Add text to a page, add a watermark, and add page numbers or a header/footer line.
- **Fill forms**: text fields, check boxes, choices, radio buttons, plus flatten.
- Edit the document's properties (title, author, subject, keywords).
- **Undo and redo** for every change. Saves are atomic, so a document is never left half-written.

### Protect
- **AES-256 password protection** with permissions for print, copy, comment, forms and edit. Password removal, and opening protected PDFs.

### Convert and analyse
- Export to **text** (.txt), **Word** (.docx, with paragraphs, headings and bold) or **page images** (PNG in a ZIP).
- **Reduce file size**: large photos are downsampled and recompressed. Line art and transparent images are left alone.
- **Analyse a document**: pages and sizes, PDF version, fonts and whether they are embedded, images, comments, form fields, searchable-page coverage, word count, security, plus suggestions such as "12 pages have no searchable text".

## Tips for the best scans
1. Use even light. Daylight near a window works well. Use the torch only in a dark room, because it can cause glare.
2. Fill the frame with the page and hold the phone parallel to it.
3. For thick books, press the page flat near the spine. The clean-up removes the remaining shadow.
4. If you photographed two pages at once, use **Split**, or **⋮ → Split all two-page spreads**.

## Building

You need JDK 17 and the Android SDK (platform 35).

```bash
echo "sdk.dir=/path/to/Android/sdk" > local.properties
./gradlew testDebugUnitTest      # unit tests (JVM + Robolectric)
./gradlew assembleDebug          # app/build/outputs/apk/debug/
```

### Signed release

Create `keystore.properties` next to `settings.gradle.kts`. It is git-ignored, so never commit it:

```properties
storeFile=keystore/pdfcs-release.jks
storePassword=…
keyAlias=pdfcs
keyPassword=…
```

Then run `./gradlew assembleRelease`. Keep the keystore safe: Android only installs updates that are
signed with the same key.

## Project structure

PDFCS follows the module structure of [PdfCraft](https://github.com/storytold/pdfcraft), the
open-source Rust PDF workbench. See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Licence

PDFCS is free software, licensed under the **GNU General Public License v3.0 or later**. See [LICENSE](LICENSE).
It includes code ported from PdfCraft (MIT) and uses Tesseract, Leptonica, PdfBox-Android and the
Liberation fonts. See [NOTICE.md](NOTICE.md) for the third-party components and their licences.
