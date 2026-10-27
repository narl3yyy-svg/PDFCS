# Changelog

All notable changes to PDFCS. Versions follow [semantic versioning](https://semver.org/).

## [1.2.0] — 2026-10-26

### Added
- **Automatic OCR on import** (on by default, Settings → *OCR imports automatically*). Scanned PDFs and photos you import are made searchable in the background with your default settings, with no review step. A scanned PDF is replaced by its searchable version under the same name. PDFs that already have text are imported as they are.
- "Make searchable" in the library and the reader converts directly in the background.
- A scene benchmark (`SceneBenchmark`, `tools/benchmark/scene.py`, `make_set.sh`): book and datasheet pages photographed on wood, light and white desks, dark cloth and a checked tablecloth, with a hold-out set, measuring page finding, tilt, lean and leftover background.

### Changed
- **Background removal.** The page finder now checks every candidate outline against what a photographed page looks like: one paper colour inside, a real edge on every side, and a different scene outside. A box lined up with a tablecloth's pattern no longer wins over the page, and a coloured band printed along the page's edge is kept. Leftover strips of desk or cloth along the edges are trimmed using the colours actually seen around the page.
- **Straight pages.** Pages are levelled to 0.05° from their text lines, and text columns that lean (line starts drifting sideways) are sheared upright.
- **Aligned text.** In the digital rebuild, each printed line has one baseline and one size, even where the OCR cut it into pieces. Body text has one size throughout, and lines sit flush on the column margins.
- Leftover background in the margins (cloth stripes read as "|" characters, slivers of pattern) is no longer drawn or made searchable.
- Corners uncovered by straightening are filled with the paper's colour, so the lighting clean-up no longer shades them.

### Fixed
- Opening a PDF from another app (Files, Share) could cancel its own import.
- Datasheet footer bands at the page's edge were sometimes cut off by the automatic crop.
- Pages imported from a PDF could be cropped to a printed band.

## [1.1.0] — 2026-10-26

### Added
- **Digital text output** (now the default): scanned pages are rebuilt as born-digital PDF pages, with real selectable text in matching fonts, bold and italic, original colours, cut-out figures and white paper.
- **Every word is verified against the page** before it is printed. Doubtful words get a second reading and typographic look-alikes, and anything unverified keeps its original pixels.
- White text on dark bands (footers, table headers) is read inverted and made searchable.
- Rebuilt pages are snapped to standard paper or book sizes and are uniform across a document.
- Page-edge detection and lighting clean-up ported from PdfCraft: line-based sheet detection, and per-channel colour-cast removal with speck clean-up.
- **Reader**: text selection and copy, highlight, underline, strike-out, sticky notes and freehand drawing. Undo and redo. An "All tools" panel.
- **Organise pages**: rotate, delete, move, duplicate, insert blank pages, reverse, extract.
- Insert pages from another PDF, and split into parts.
- **Protect**: AES-256 passwords with permissions, password removal, and opening protected documents.
- **Edit**: add text, watermark, page numbers and header/footer, fill and flatten forms, and edit properties.
- **Convert**: export to text, Word (.docx) and page images, plus reduce file size.
- **Analyse**: a document report with fonts, images, text coverage, security and suggestions.
- Unit tests (JVM and Robolectric) and an end-to-end scan accuracy benchmark (`tools/benchmark`).

### Changed
- The app is now called **PDFCS** (package `com.narl3yyy.pdfcs`), with a new signing key. Install it fresh: it does not update the previous build.
- The code is reorganised after PdfCraft's module structure (see `docs/ARCHITECTURE.md`).
- The reader draws comments and filled-in form fields, which Android's built-in renderer leaves out.

### Fixed
- Cancelling a long conversion now stops it.
- Deleting pages and reversing page order no longer use a Java 21 API that crashes on Android 14 and older.
- A finished job's title no longer carries over to the next job in the queue.
- Volume-key capture uses the public key-event API.

## [1.0.0] — 2026-10-21

### Added
- Book scanning with the camera: volume-key and auto-capture, automatic page detection, cropping, rotation, splitting two-page spreads, and lighting filters.
- On-device OCR (Tesseract, `tessdata_best`) into one searchable PDF with an invisible text layer, built in the background.
- Reader with zoom and highlighted full-text search.
- Library with import, merge, share, rename and "make searchable".
- About 30 downloadable OCR languages.
