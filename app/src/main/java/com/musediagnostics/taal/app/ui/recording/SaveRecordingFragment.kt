package com.musediagnostics.taal.app.ui.recording

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.musediagnostics.taal.app.R
import com.musediagnostics.taal.app.databinding.FragmentSaveRecordingBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SaveRecordingFragment : Fragment() {

    private var _binding: FragmentSaveRecordingBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSaveRecordingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val filePath = arguments?.getString("filePath") ?: ""
        val filterName = arguments?.getString("filterName") ?: "HEART"

        // Show filter chip
        binding.filterChip.text = filterName

        // Pre-fill filename: FILTER_yyyyMMdd_HHmmss
        val dateStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val suggestedName = "${filterName}_${dateStr}"
        binding.fileNameInput.setText(suggestedName)
        binding.fileNameInput.setSelection(suggestedName.length)

        binding.backButton.setOnClickListener {
            findNavController().navigateUp()
        }

        binding.cancelButton.setOnClickListener {
            findNavController().navigateUp()
        }

        binding.saveButton.setOnClickListener {
            val name = binding.fileNameInput.text?.toString()?.trim()
            if (name.isNullOrEmpty()) {
                Toast.makeText(requireContext(), "Please enter a file name", Toast.LENGTH_SHORT)
                    .show()
                return@setOnClickListener
            }

            val savedPath = saveRecording(filePath, name)
            if (savedPath != null) {
                Toast.makeText(requireContext(), "Recording saved!", Toast.LENGTH_SHORT).show()
                // Navigate to saved recordings list, popping recording screen is NOT done —
                // user can go back to recording naturally. Pop back to recordingFragment.
                findNavController().navigate(
                    R.id.action_saveRecording_to_savedRecordings,
                    null,
                    androidx.navigation.NavOptions.Builder()
                        .setPopUpTo(R.id.recordingFragment, false).build()
                )
            } else {
                Toast.makeText(requireContext(), "Failed to save recording", Toast.LENGTH_SHORT)
                    .show()
            }
        }
    }

    private fun saveRecording(tempPath: String, name: String): String? {
        return try {
            val tempFile = File(tempPath)
            if (!tempFile.exists()) return null

            val savedDir = File(requireContext().filesDir, "saved")
            savedDir.mkdirs()

            // Sanitize filename (no slashes, no null bytes)
            val safeName = name.replace(Regex("[/\\\\:*?\"<>|]"), "_")
            val destFile = File(savedDir, "$safeName.wav")

            if (tempFile.renameTo(destFile)) {
                destFile.absolutePath
            } else {
                // Fallback: copy then delete
                tempFile.copyTo(destFile, overwrite = true)
                tempFile.delete()
                destFile.absolutePath
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
