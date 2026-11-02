# Changelog

All notable changes to PDFCS. Versions follow [semantic versioning](https://semver.org/).

## [1.4.0] — 2026-11-02

Learned from "001J STD IPC (searchable)": the 93-page IPC scan converted again with 1.3.0, whose pages still mixed typefaces, sizes and baselines, had misread capitals ("ReqUIrements", "COMPly"), and in places shrank the page into the top corner.

### Added
- **Live alignment guide** in the camera. It shows a page-shaped target with a centre cross, and the page's outline as the camera sees it: amber while you adjust, red when part of the page is outside the picture, green when it is ready. One instruction at a time tells you what to fix: fit the whole page in, move closer, centre it, hold the phone flat, line it up, hold still.
- **Shoot automatically when lined up** (Settings): the camera takes the photo by itself once the page is centred, square and still, once per page.
- **Save original photos** (Settings): every camera scan's photos are copied to a folder you choose, one subfolder per scan ("Page 001.jpg"…), before the conversion starts.
- **Photo folder to searchable PDF** (Library → Add): converts a folder of page photos in page order, such as one saved above, with the latest page finder and rebuild.

### Changed
- **Pages laid out as a real document.** The layout is decided once every page has been read, for the whole document:
  - one standard paper size, voted by the shape of the photos;
  - a page photographed whole sits exactly as printed;
  - a badly cropped page (cut into the text, desk left in) gets the document's body size and margins.
  
  Before, every page's content was placed in the largest frame seen so far, so later pages shrank into the top-left corner.
- **True page proportions.** A page photographed at a slant is straightened to its real shape, using the camera's focal length, instead of being squashed or stretched.
- **Printed edge bands are kept.** A page that fills the photo with a coloured band along its edge (a datasheet footer) was cropped at the band's edge, losing the band and its text. The outline now reaches the photo's edge when the strip beyond it is printing.
- **One typeface, one size, one baseline.** One font family is used for the whole document. Each printed line has one baseline and one size, and a page uses only a few type sizes.
- **Kept words move with their line.** Words kept as their original pixels are cut out one by one and moved and sized with their line, and always cleaned to black ink on white paper (small word crops used to be taken for photos and kept grey).
- **Fewer kept words.** Words the OCR is very sure of are typeset even when the page's typeface differs a little from the bundled ones. Numbers still have to match the ink closely, so a date or dimension is never printed wrong.
- **Correct capitals.** Words read in a case print never uses ("ReqUIrements", "THis") are set in lower case, Capitalised or ALL CAPS, whichever matches the ink. Real mixed-case words such as PTHs and SnPb are kept, and units and values (MHz, pF, VRM=5.25V) are left exactly as read.
- **No stray bold words.** Bold is decided per line, and a single word is made bold only when its ink clearly says so and the bold face fits its shape better.
- **Even line starts.** Line starts snap to the column's margins from further away, so lettered lists and paragraphs line up.
- **"Original photos" PDFs** now also have one paper size for the whole document, instead of each page being its photo's size.

### Fixed
- Save to device and the reader's exports could leave the end of a longer old file after the new one, corrupting it, when saving over an existing file.
- An import that failed partway left a broken copy in the library.
- Word (.docx) exports opened on a blank page.
- Whole-file tools (Optimize) kept undo snapshots without limit.
- The viewer left a file open when a PDF could not be displayed.
- The temporary copy made by "Make a PDF searchable" was left behind when it failed.

## [1.3.0] — 2026-11-01

Learned from a real 93-page scan of IPC J-STD-001J, in which more than half the pages came out sideways and unsearchable.

### Added
- **Sideways and upside-down pages are turned upright.** A page photographed with the phone held sideways (text running up or down the page) is detected from its ink pattern and given a quarter turn, then the page is read both ways up and turned if upside down reads clearly better. Before, such pages kept their sideways text, the OCR read nothing, and the page was kept as a blurry picture.

### Changed
- **Real-document layout.** Every rebuilt page is laid out on one standard paper size for the whole document, with body text at one size and the content in the same frame with the same margins on every page. Before, each page was as big as its photo's crop, so margins ranged from nothing to an inch, and headers and page numbers sat on the very edge.
- **Words kept as pixels look like print.** Words and line art that are kept as the original pixels (because they could not be verified) are cleaned to black ink on white paper, instead of camera-grey on grey boxes.
- Lines that mix typeset words with words kept as pixels are no longer shifted sideways, so the two can no longer run into each other.

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
