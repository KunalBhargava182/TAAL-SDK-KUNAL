package com.musediagnostics.taal.app.ui.recording

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.musediagnostics.taal.app.R
import com.musediagnostics.taal.app.databinding.FragmentSaveRecordingBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

        val filePath = arguments?.getString("filePath") ?: ""       // filtered temp
        val rawFilePath = arguments?.getString("rawFilePath") ?: ""  // raw temp

        // Hide the filter chip entirely since we are no longer using filterName
        binding.filterChip.visibility = View.GONE

        // Pre-fill filename: yyyyMMdd_HHmmss (Removed filterName)
        val timeStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val suggestedName = "$timeStr"
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

            binding.saveButton.isEnabled = false
            val ctx = requireContext()

            lifecycleScope.launch(Dispatchers.IO) {
                val savedPath = saveRecording(ctx, filePath, rawFilePath, name)
                withContext(Dispatchers.Main) {
                    if (_binding == null) return@withContext
                    if (savedPath != null) {
                        Toast.makeText(ctx, "Recording saved!", Toast.LENGTH_SHORT).show()
                        findNavController().navigate(
                            R.id.action_saveRecording_to_savedRecordings,
                            null,
                            androidx.navigation.NavOptions.Builder()
                                .setPopUpTo(R.id.recordingFragment, false).build()
                        )
                    } else {
                        binding.saveButton.isEnabled = true
                        Toast.makeText(ctx, "Failed to save recording", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun saveRecording(ctx: android.content.Context, filteredTempPath: String, rawTempPath: String, name: String): String? {
        return try {
            val savedDir = File(ctx.filesDir, "saved")
            savedDir.mkdirs()
            val safeName = name.replace(Regex("[/\\\\:*?\"<>|]"), "_")

            // Rename filtered temp → saved filtered file
            val filteredTemp = File(filteredTempPath)
            val filteredSaved = File(savedDir, "${safeName}_filtered.wav")
            if (filteredTemp.exists()) filteredTemp.renameTo(filteredSaved)

            // Rename raw temp → saved raw file
            val rawTemp = File(rawTempPath)
            val rawSaved = File(savedDir, "${safeName}_raw.wav")
            if (rawTemp.exists()) rawTemp.renameTo(rawSaved)

            filteredSaved.absolutePath
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