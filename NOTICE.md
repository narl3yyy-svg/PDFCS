# Third-party notices

PDFCS is licensed under the GNU General Public License v3.0 or later (see `LICENSE`). It includes or
uses the following components, each under its own licence. Full licence texts are in `LICENSES/`.

| Component | Use in PDFCS | Licence |
|---|---|---|
| [PdfCraft](https://github.com/storytold/pdfcraft) — © 2026 ArtCraft Team and the PdfCraft contributors | Page-edge detection (`camera/CameraEdges.kt`) and lighting clean-up (`camera/CameraClean.kt`) are ported from `engine/src/camera_edges.rs` and `engine/src/camera_clean.rs`; quadrilateral ordering from `geom`; the module structure and tool names | MIT OR Apache-2.0 (used under MIT, `LICENSES/PdfCraft-MIT.txt`) |
| [Tesseract OCR](https://github.com/tesseract-ocr/tesseract) and the [tessdata_best](https://github.com/tesseract-ocr/tessdata_best) models | Text recognition | Apache-2.0 |
| [Tesseract4Android](https://github.com/adaptech-cz/Tesseract4Android) | Tesseract and Leptonica for Android | Apache-2.0 |
| [Leptonica](http://www.leptonica.org/) | Image handling inside Tesseract | BSD-2-Clause |
| [PdfBox-Android](https://github.com/TomRoush/PdfBox-Android) | PDF reading, writing, editing and text extraction | Apache-2.0 |
| [Liberation fonts](https://github.com/liberationfonts/liberation-fonts) 2.x | Fonts of rebuilt pages and added text (`app/src/main/assets/fonts`) | SIL Open Font License 1.1 |
| AndroidX, Jetpack Compose, CameraX, WorkManager | App framework | Apache-2.0 |

PDFCS does not include PdfCraft's ArtCraft name or logos, which are not open source.
