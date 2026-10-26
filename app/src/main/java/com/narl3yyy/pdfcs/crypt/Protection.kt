package com.narl3yyy.pdfcs.crypt

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy

/** What a protected document still allows without the owner password. */
data class Permissions(
    val print: Boolean = true,
    val copy: Boolean = true,
    val modify: Boolean = false,
    val annotate: Boolean = true,
    val fillForms: Boolean = true,
)

/** Document security (after pdfcraft-crypt): AES-256 password protection, removal, status. */
object Protection {
    /**
     * Protects [doc] with AES-256 (PDF 2.0 security handler, revision 6). [userPassword] is needed to
     * open the document (blank = anyone may open it); [ownerPassword] unlocks every permission.
     */
    fun protect(doc: PDDocument, userPassword: String, ownerPassword: String, permissions: Permissions) {
        require(ownerPassword.isNotEmpty()) { "Choose a permissions (owner) password" }
        val ap = AccessPermission().apply {
            setCanPrint(permissions.print)
            setCanPrintFaithful(permissions.print)
            setCanExtractContent(permissions.copy)
            setCanExtractForAccessibility(true) // screen readers always
            setCanModify(permissions.modify)
            setCanAssembleDocument(permissions.modify)
            setCanModifyAnnotations(permissions.annotate)
            setCanFillInForm(permissions.fillForms)
        }
        val policy = StandardProtectionPolicy(ownerPassword, userPassword, ap).apply { encryptionKeyLength = 256 }
        doc.protect(policy)
    }

    /** Removes all security from a document that was opened with its password. */
    fun unprotect(doc: PDDocument) {
        doc.isAllSecurityToBeRemoved = true
    }

    /** A human-readable summary of the document's security. */
    fun describe(doc: PDDocument): String {
        if (!doc.isEncrypted) return "Not protected"
        val enc = doc.encryption
        val method = when {
            enc.length >= 256 -> "AES-256"
            enc.version >= 4 -> "AES-128 or RC4-128"
            else -> "RC4-${enc.length}"
        }
        val p = doc.currentAccessPermission
        val allowed = buildList {
            if (p.canPrint()) add("print")
            if (p.canExtractContent()) add("copy text")
            if (p.canModify()) add("edit")
            if (p.canModifyAnnotations()) add("comment")
            if (p.canFillInForm()) add("fill forms")
        }
        return "Protected ($method). Allowed: ${allowed.joinToString().ifEmpty { "nothing without the owner password" }}"
    }
}
