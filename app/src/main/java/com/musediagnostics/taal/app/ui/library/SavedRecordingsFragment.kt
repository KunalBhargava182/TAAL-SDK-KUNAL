package com.musediagnostics.taal.app.ui.library

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.musediagnostics.taal.app.R
import com.musediagnostics.taal.app.databinding.FragmentSavedRecordingsBinding
import java.io.File

class SavedRecordingsFragment : Fragment() {

    private var _binding: FragmentSavedRecordingsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSavedRecordingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.backButton.setOnClickListener {
            findNavController().navigateUp()
        }

        binding.recordingsList.layoutManager = LinearLayoutManager(requireContext())
        loadRecordings()
    }

    override fun onResume() {
        super.onResume()
        loadRecordings()
    }

    private fun loadRecordings() {
        val savedDir = File(requireContext().filesDir, "saved")
        val files = savedDir.listFiles { f -> f.extension == "wav" }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

        if (files.isEmpty()) {
            binding.emptyState.visibility = View.VISIBLE
            binding.recordingsList.visibility = View.GONE
        } else {
            binding.emptyState.visibility = View.GONE
            binding.recordingsList.visibility = View.VISIBLE
            binding.recordingsList.adapter = SavedRecordingAdapter(files) { file ->
                val bundle = Bundle().apply {
                    putString("filePath", file.absolutePath)
                    putBoolean("isNewRecording", false)
                }
                findNavController().navigate(R.id.action_savedRecordings_to_player, bundle)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
