# PDFCS architecture

PDFCS is a native Android app (Kotlin, Jetpack Compose). Its source is organised after the crates of
[PdfCraft](https://github.com/storytold/pdfcraft), the open-source Rust PDF workbench, so each concern
lives in one place and the two code bases are easy to compare. PdfCraft itself is a desktop and web
application and does not run on Android. PDFCS ports its algorithms where they apply, uses
PdfBox-Android as its PDF object layer, and Tesseract for OCR.

| PDFCS package | PdfCraft counterpart | Responsibility |
|---|---|---|
| `core` | `geom`, shared helpers | Histograms (Otsu), quadrilaterals, page geometry (display ↔ user space for every `/Rotate`), page ranges, atomic file writes |
| `camera` | `engine/camera*.rs` | Page-edge detection (`CameraEdges`, ported), lighting clean-up (`CameraClean`, ported), the verified page finder (`PageFinder`, `PaperMask`, `PaperRegion`), straightening (`Deskew`: tilt, lean, background trim), page decode / warp / rotate |
| `ocr` | `ocr` | Tesseract engine wrapper: page hOCR, single-word re-reads, inverted reading of dark panels; language models |
| `clearscan` | (new) | Rebuilding page photos as born-digital pages: fonts, word fitting, the shape check, bold/italic, graphics extraction, PDF writing |
| `organize` | `organize` | Rotate, delete, move, reorder, duplicate, insert blank/file, extract, split, combine |
| `annot` | `annot` | Highlight/underline/strike-out, notes, ink, list and delete, with appearance streams |
| `crypt` | `crypt` | AES-256 protection with permissions, removal, security summary |
| `edit` | `edit` | Watermarks, headers/footers with page numbers, added text |
| `forms` | `forms` | AcroForm fields: list, fill, flatten |
| `export` | `export` | Text, Word (.docx) and page-image export |
| `optimize` | `optimize` | Image downsampling and recompression |
| `inspect` | `doc_info`/`doc_inspect` tools | Document report and properties |
| `engine` | `engine`, `automation` | `DocumentSession` (undoable, atomic edits) and the tool catalogue (ids follow PdfCraft's tool names) |
| `reader` | `render` | Page rendering, the display copy (comments drawn in), full-text index and search |
| `library`, `work`, `ui` | `ui-egui` | Library, scan session, background OCR job and automatic import jobs (`ImportJobs`), screens |

## Principles carried over from PdfCraft

- **Never lose the user's work.** Every edit is one step of a `DocumentSession`. It loads the file, applies the change and saves atomically (temporary file, then rename). If the change throws, nothing is written. The previous version is kept for Undo.
- **Untrusted input.** Every PDF, photo and setting may be malformed. Operations validate their inputs and report errors instead of crashing.
- **Don't drop data silently.** Page deletion removes bookmarks that would point at nothing. Combining keeps links, fields and bookmarks.
- **Tests at every zoom.** There are pure-function tests, synthetic-photo tests for the camera ports, and Robolectric tests that run real PDF operations. The emulator benchmark in `tools/benchmark` measures scan accuracy end to end.

## Finding and straightening the page (`camera`)

A photo shows the page and whatever it lies on. `PageFinder` refuses to guess:

1. **Candidates.** Straight-edge quadrilaterals from colour gradients (`CameraEdges`, the 12 best), the same line search run on a region-grown paper mask (`PaperMask`), and the bright paper region (`PaperRegion`).
2. **Verification.** Each candidate is scored as a page: mostly one paper colour inside, a colour step across every side (a side along the photo's frame counts for little), a scene unlike paper just outside, and none of the scene's colours inside.
3. **Refinement.** A clear-edged outline inside the winner replaces it when the ring between them is the scene (a box lined up with a tablecloth's squares). A larger outline wins when the extra strip is page (a coloured footer band). Sides are extended over bands the edge search missed, and the result is snapped onto the photo's strongest nearby edges.

With no convincing outline the whole photo is used. A page taken from a PDF is cropped only when the outline clearly leaves out surroundings, so a band printed along its edge is never mistaken for a desk.

After the perspective warp, `Deskew` makes the page look typeset:

- **Trim.** Strips along the edges that are made of the scene's own colours (sampled outside the outline) are cut off. It stops at the first line carrying ink.
- **Tilt.** The angle at which the text rows' projection profile is sharpest (±8°, to 0.05°) is turned level.
- **Lean.** If the line starts still drift sideways, because a corner was found a little off, the slant at which the column profile has the sharpest steps (±8%) is sheared upright.

`SceneBenchmark` (`-Dpdfcs.scenes=DIR`) measures all of this on photo scenes made by `tools/benchmark/make_set.sh`.

## The digital-text pipeline (`clearscan`)

1. **Camera page.** The page is found, warped flat, trimmed, levelled and stood upright (see above), cleaned (`CameraClean`), and enlarged if the print is small.
2. **OCR.** Tesseract reads the page into hOCR: lines, baselines, words, boxes and confidences.
3. **Typeface.** The bundled Liberation family (Serif or Sans, metric-compatible with Times and Arial) that best covers the page's ink is chosen.
4. **Fitting.** Each word's font size comes from the font's real glyph extents. It sits on the hOCR baseline and is stretched to the word's width.
5. **Weight and slant.**
   - Bold is measured as total ink darkness against the document's body text. Blur spreads ink but keeps its total.
   - Italic is measured by whether shearing the line upright sharpens its column profile.
6. **The shape check.**
   - The word is rendered and compared with the photo's ink, allowing one pixel of tolerance.
   - A word whose width doesn't fit its line's letter spacing is rejected, which catches lost decimal points and merged letters.
   - Doubtful words get a second, enlarged reading plus typographic look-alikes. Only a candidate that matches is printed.
   - Anything else keeps its original pixels, with invisible searchable text on top.
7. **Alignment.** Pieces of one printed line that the OCR split are joined again and set on one baseline at one size, sizes close to the body text become the body size, and line starts and ends are moved onto the column's margins, so lines don't step up and down or drift.
8. **Graphics.** The ink left after erasing typeset words is grouped into figures, photos, rules and logos, and cut out of the cleaned page. Dark panels are read inverted so their text is searchable. Wordless ink at the page's edge or in the outer margin, and one-character "words" read from it, are leftover background and are dropped.
9. **Page size.** The page is snapped to a standard paper or book size of the same shape, and all pages of a document share it.
10. **Writing.** Pictures are written first, then every word as text in an embedded font subset, in its colour.

## Automatic OCR on import (`work/ImportJobs`)

With *OCR imports automatically* on (the default), imports go straight into a background job with the user's default settings:

- A **scanned PDF** (most of its first pages have no text) is rendered at about 300 dpi and converted. The searchable result takes the original's place under the same name. If the job fails, the original stays.
- **Photos** ("Images to searchable PDF") have their pages found and cropped, then are converted.
- PDFs that already have text are imported as they are.
