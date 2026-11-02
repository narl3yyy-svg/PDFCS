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

The perspective warp gives the page its real proportions: the camera's focal length is measured from the four corners (Zhang & He's whiteboard method), or a phone's typical one is used when the slant is too slight to measure it. Averaging side lengths instead would squash a page shot at a slant, and a squashed page could not be laid out as the sheet it is.

### The live guide (`LiveGuide`, `PageAnalyzer`)

While the camera is open, small preview frames (480 pixels) go through the same `PageFinder`. The outline is drawn over the preview, inside a page-shaped target with a centre cross, and one instruction is shown at a time, most important first: fit the whole page in (a corner at the frame's edge means part of the page may be cut off, which nothing can repair later), move closer, centre the page, hold the phone flat (opposite sides of different lengths mean it is tilted), turn it to line up, then hold still. Once the outline has been steady for 0.7 s it turns green. The camera can then shoot by itself (Settings), once per page. The outline seen at the shutter is also the crop for a photo whose own search finds nothing, and a cut-off page gets a warning.

After the perspective warp, `Deskew` makes the page look typeset:

- **Trim.** Strips along the edges that are made of the scene's own colours (sampled outside the outline) are cut off. It stops at the first line carrying ink.
- **Quarter turn.** Text running up or down the page (a portrait page photographed with the phone sideways) is recognised from the ink profiles: across text lines the ink comes and goes line by line, so that profile is far jumpier than the one along the lines. Such a page is turned 90° before the tilt is measured. Which way up it reads is settled after OCR starts (below).
- **Tilt.** The angle at which the text rows' projection profile is sharpest (±8°, to 0.05°) is turned level.
- **Lean.** If the line starts still drift sideways, because a corner was found a little off, the slant at which the column profile has the sharpest steps (±8%) is sheared upright.

`SceneBenchmark` (`-Dpdfcs.scenes=DIR`) measures all of this on photo scenes made by `tools/benchmark/make_set.sh`.

## The digital-text pipeline (`clearscan`)

1. **Camera page.** The page is found, warped flat, trimmed, levelled and stood upright (see above), cleaned (`CameraClean`), and enlarged if the print is small.
2. **Upright check.** A band across the middle of the page is read as it is and, unless it reads clearly, upside down too. The page is turned 180° when upside down reads clearly better. This catches upside-down photos and quarter turns the wrong way.
3. **OCR.** Tesseract reads the page into hOCR: lines, baselines, words, boxes and confidences.
4. **Typeface.** The bundled Liberation family (Serif or Sans, metric-compatible with Times and Arial) that best covers the ink is chosen for the whole document: each page adds its words' fit for each family, and the leader so far is used.
5. **Fitting.** Each word's font size comes from the font's real glyph extents. It sits on the hOCR baseline and is stretched to the word's width. A word read in a case print never uses ("ReqUIrements", "COMPly"; `CaseFix`) is set in each case it may really have, and the one whose glyphs match the ink best is kept.
6. **Weight and slant.**
   - Bold is measured as total ink darkness against the document's body text. Blur spreads ink but keeps its total. It is decided per line. A word of four or more letters may differ from its line only when its ink says so clearly and the other face fits its shape better, so blur on one word doesn't scatter bold words through the text.
   - Italic is measured by whether shearing the line upright sharpens its column profile.
7. **The shape check.**
   - The word is rendered and compared with the photo's ink, allowing one pixel of tolerance.
   - A word whose width doesn't fit its line's letter spacing is rejected, which catches lost decimal points and merged letters.
   - Doubtful words get a second, enlarged reading plus typographic look-alikes. Only a candidate that matches is printed.
   - A word the OCR is very sure of may fit less closely (the page's own typeface is not a bundled one). For the commonest words this is looser still. Words with digits never get this allowance: a wrong digit changes what a document says.
   - Anything else keeps its original pixels, with invisible searchable text on top. Kept pixels are cleaned to black ink on white paper.
8. **Alignment.** Pieces of one printed line that the OCR split are joined again and set on one baseline at one size. Line sizes within 8% of each other become one size, as a typeset page uses few sizes, and the one near the body text is the body size. Line starts and ends are moved onto the column's margins, so lines don't step up and down or drift. Words kept as pixels in a line of text are cut out one by one and moved and sized with their line: shifted with its margins, and set on its baseline when they are close to it. One far off stays where it is, since its OCR box may not be where its pixels are.
9. **Graphics.** The ink left after erasing typeset words is grouped into figures, photos, rules and logos, and cut out of the cleaned page. Dark panels are read inverted so their text is searchable. Wordless ink at the page's edge or in the outer margin, and one-character "words" read from it, are leftover background and are dropped.
10. **Writing.** Pictures are written first, then every word as text in an embedded font subset, in its colour, all in the photo's pixels.
11. **Layout** (`PageLayout`), once every page is read, for the whole document:
    - **Paper.** One standard size for the document: the one most photos have the shape of (a vote, so a few bad crops don't decide it).
    - **True crops.** A photo with the paper's shape and paper left around its text on every side is the sheet itself. It is laid on the paper as it is, so everything sits where the printer put it. These pages teach the document's body size and margins (medians).
    - **Other pages** (a crop that cut into the text, kept some desk, or has the wrong shape, or a body size far off the rest): body text at the document's size, the content where the document's text block starts, narrow content centred across it. A page without body text is fitted to the text block, never enlarged beyond its photo. Content too large is scaled down to fit. Turned paper is used only when a wide table would otherwise have to shrink.
    - The paper size and a transform are then set on each page.

"Original photos" PDFs get the same paper layout after Tesseract writes them (each page is otherwise the size of its photo).

## Keeping the original photos (`library/PhotoArchive`)

With "Save original photos" on, a camera scan's photos are copied, as taken, to a folder the user picked (Storage Access Framework, permission kept across restarts), before the conversion starts. Each scan gets its own subfolder ("Page 001.jpg"…) with a `pages.json` of each page's crop, turn and look, so the scan can be converted again later.

## Automatic OCR on import (`work/ImportJobs`)

With *OCR imports automatically* on (the default), imports go straight into a background job with the user's default settings:

- A **scanned PDF** (most of its first pages have no text) is rendered at about 300 dpi and converted. The searchable result takes the original's place under the same name. If the job fails, the original stays.
- **Photos** ("Images to searchable PDF") have their pages found and cropped, then are converted.
- PDFs that already have text are imported as they are.
