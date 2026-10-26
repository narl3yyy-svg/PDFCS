package com.narl3yyy.pdfcs.forms

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDCheckBox
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDChoice
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDField
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDRadioButton
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField

/** A fillable field as the Fill Form panel shows it. */
data class FormField(
    val name: String,
    val label: String,
    val kind: Kind,
    val value: String,
    val options: List<String> = emptyList(),
    val readOnly: Boolean = false,
    val multiline: Boolean = false,
) {
    enum class Kind { TEXT, CHECKBOX, CHOICE, RADIO }
}

/** Interactive forms (after pdfcraft-forms): list fields, fill them, flatten. */
object Forms {
    fun fields(doc: PDDocument): List<FormField> {
        val form = doc.documentCatalog.acroForm ?: return emptyList()
        return form.fieldTree.mapNotNull { f -> describe(f) }
    }

    private fun describe(f: PDField): FormField? {
        val label = f.alternateFieldName?.takeIf { it.isNotBlank() } ?: f.partialName ?: f.fullyQualifiedName
        return when (f) {
            is PDTextField -> FormField(f.fullyQualifiedName, label, FormField.Kind.TEXT, f.value.orEmpty(), readOnly = f.isReadOnly, multiline = f.isMultiline)
            is PDCheckBox -> FormField(f.fullyQualifiedName, label, FormField.Kind.CHECKBOX, if (f.isChecked) "on" else "off", readOnly = f.isReadOnly)
            is PDRadioButton -> FormField(f.fullyQualifiedName, label, FormField.Kind.RADIO, f.valueAsString.orEmpty(), f.onValues.toList(), f.isReadOnly)
            is PDChoice -> FormField(f.fullyQualifiedName, label, FormField.Kind.CHOICE, f.value.firstOrNull().orEmpty(), f.optionsDisplayValues, f.isReadOnly)
            else -> null
        }
    }

    /** Sets field values by fully qualified name; appearances are regenerated. */
    fun fill(doc: PDDocument, values: Map<String, String>) {
        val form = doc.documentCatalog.acroForm ?: error("This document has no form")
        for ((name, value) in values) {
            val f = form.getField(name) ?: continue
            if (f.isReadOnly) continue
            when (f) {
                is PDCheckBox -> if (value == "on") f.check() else f.unCheck()
                is PDRadioButton -> if (value in f.onValues) f.setValue(value)
                is PDChoice -> f.setValue(value)
                is PDTextField -> f.setValue(value)
                else -> Unit
            }
        }
    }

    /** Turns the fields into ordinary page content, so the values can no longer change. */
    fun flatten(doc: PDDocument) {
        val form = doc.documentCatalog.acroForm ?: error("This document has no form")
        form.flatten()
    }
}
