package com.musediagnostics.taal.uikit.player

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.google.android.material.textfield.TextInputEditText
import com.musediagnostics.taal.uikit.R
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Dialog for saving a new recording with a user-chosen filename.
 *
 * Pre-fills the name as "{FILTER_NAME}_{yyyyMMdd_HHmmss}".
 * On save: renames the temp WAV file and returns the final path via [onSaved].
 * On cancel: calls [onCancelled] (caller should handle discard).
 */
class TaalSaveDialog(
    private val tempFilePath: String,
    private val filterName: String,
    private val onSaved: (finalPath: String) -> Unit,
    private val onCancelled: () -> Unit
) : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val view = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_taal_save, null)

        val dialog = AlertDialog.Builder(requireContext())
            .setView(view)
            .setCancelable(false)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        // Pre-fill with {FILTER_NAME}_{yyyyMMdd_HHmmss}
        val dateStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val defaultName = "${filterName}_${dateStr}"
        view.findViewById<TextInputEditText>(R.id.fileNameInput).setText(defaultName)

        view.findViewById<Button>(R.id.saveButton).setOnClickListener {
            val name = view.findViewById<TextInputEditText>(R.id.fileNameInput)
                .text?.toString()?.trim()

            if (name.isNullOrEmpty()) {
                Toast.makeText(requireContext(), "Please enter a file name", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val tempFile = File(tempFilePath)
            if (!tempFile.exists()) {
                Toast.makeText(requireContext(), "Recording file not found", Toast.LENGTH_SHORT).show()
                dismiss()
                onCancelled()
                return@setOnClickListener
            }

            val safeName = name.replace(Regex("[/\\\\:*?\"<>|]"), "_")
            val savedDir = File(requireContext().filesDir, "saved").also { it.mkdirs() }
            val finalFile = File(savedDir, "$safeName.wav")

            if (tempFile.renameTo(finalFile)) {
                dismiss()
                onSaved(finalFile.absolutePath)
            } else {
                try {
                    tempFile.copyTo(finalFile, overwrite = true)
                    tempFile.delete()
                    dismiss()
                    onSaved(finalFile.absolutePath)
                } catch (e: Exception) {
                    Toast.makeText(requireContext(), "Failed to save file", Toast.LENGTH_SHORT).show()
                }
            }
        }

        view.findViewById<Button>(R.id.cancelButton).setOnClickListener {
            dismiss()
            onCancelled()
        }

        return dialog
    }
}
