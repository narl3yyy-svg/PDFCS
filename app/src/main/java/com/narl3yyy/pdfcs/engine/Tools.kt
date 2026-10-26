package com.narl3yyy.pdfcs.engine

/** Tool groups, in the order the All Tools panel shows them. */
enum class ToolGroup(val label: String) {
    SCAN("Scan & OCR"),
    ORGANIZE("Organize pages"),
    COMMENT("Comment"),
    EDIT("Edit"),
    PROTECT("Protect"),
    CONVERT("Convert & share"),
    ANALYZE("Analyze"),
}

/**
 * The document tool catalogue. Ids follow PdfCraft's automation tool names, so the two
 * vocabularies line up (`page_rotate`, `doc_protect`, `ocr_recognize`, …).
 */
enum class Tool(val id: String, val label: String, val group: ToolGroup, val hint: String) {
    OCR("ocr_recognize", "Make searchable (OCR)", ToolGroup.SCAN, "Read the pages and rebuild them with real text"),

    ORGANIZE("page_move", "Organize pages", ToolGroup.ORGANIZE, "Reorder, rotate, delete, duplicate or insert pages"),
    INSERT_FILE("page_insert_file", "Insert from file", ToolGroup.ORGANIZE, "Add the pages of another PDF"),
    EXTRACT("page_extract", "Extract pages", ToolGroup.ORGANIZE, "Save some pages as a new PDF"),
    SPLIT("doc_split", "Split", ToolGroup.ORGANIZE, "Divide into parts of a few pages each"),

    HIGHLIGHT("annot_highlight", "Highlight text", ToolGroup.COMMENT, "Long-press text, then choose Highlight"),
    NOTE("annot_note", "Sticky note", ToolGroup.COMMENT, "Tap where the note goes"),
    DRAW("annot_ink", "Draw", ToolGroup.COMMENT, "Freehand pen and highlighter"),
    COMMENTS("annot_list", "All comments", ToolGroup.COMMENT, "Review and delete comments"),

    ADD_TEXT("page_add_text", "Add text", ToolGroup.EDIT, "Type text onto a page"),
    FILL_FORM("form_fill", "Fill form", ToolGroup.EDIT, "Fill in the document's form fields"),
    WATERMARK("doc_watermark", "Watermark", ToolGroup.EDIT, "Stamp text across every page"),
    HEADER_FOOTER("doc_header_footer", "Page numbers & header", ToolGroup.EDIT, "Add page numbers or a line of text to every page"),
    PROPERTIES("doc_set_info", "Properties", ToolGroup.EDIT, "Title, author, subject and keywords"),

    PROTECT("doc_protect", "Protect with password", ToolGroup.PROTECT, "AES-256 encryption and permissions"),
    UNPROTECT("doc_unprotect", "Remove password", ToolGroup.PROTECT, "Remove the document's security"),

    EXPORT_TEXT("doc_export_text", "Export text (.txt)", ToolGroup.CONVERT, "All the text, page by page"),
    EXPORT_WORD("doc_export_office", "Export to Word (.docx)", ToolGroup.CONVERT, "Paragraphs, headings and bold text"),
    EXPORT_IMAGES("doc_export_images", "Export pages as images", ToolGroup.CONVERT, "Every page as a PNG, in a ZIP"),
    OPTIMIZE("doc_optimize", "Reduce file size", ToolGroup.CONVERT, "Recompress large images"),
    SHARE("doc_share", "Share", ToolGroup.CONVERT, "Send the PDF to another app"),

    INFO("doc_info", "Analyze document", ToolGroup.ANALYZE, "Pages, fonts, text coverage, security and suggestions"),
}
