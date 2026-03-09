package com.musediagnostics.taal.app.ui.player

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter
import com.musediagnostics.taal.InvalidFileNameException
import com.musediagnostics.taal.TaalPlayer
import com.musediagnostics.taal.app.R
import com.musediagnostics.taal.app.databinding.FragmentPlayerBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class PlayerFragment : Fragment() {

    private var _binding: FragmentPlayerBinding? = null
    private val binding get() = _binding!!
    private var player: TaalPlayer? = null
    private var isPlaying = false

    companion object {
        private const val INPUT_SAMPLE_RATE = 44100f
        private const val HEADROOM = 1.5f
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPlayerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val filePath = arguments?.getString("filePath") ?: ""
        val isNewRecording = arguments?.getBoolean("isNewRecording", false) ?: false

        // Save/Discard bar is only relevant for a freshly made recording
        binding.saveDiscardBar.visibility = if (isNewRecording) View.VISIBLE else View.GONE

        setupWaveformChart()

        // 1. Load the full waveform the moment the screen opens
        if (filePath.isNotEmpty()) {
            loadFullWaveform(filePath)
            setupPlayer(filePath)
        }

        binding.backButton.setOnClickListener {
            findNavController().navigateUp()
        }

        binding.eqButton.setOnClickListener {
            val bundle = Bundle().apply {
                putString("filePath", filePath)
            }
            findNavController().navigate(R.id.action_player_to_equalizer, bundle)
        }

        binding.playButton.setOnClickListener {
            if (filePath.isEmpty()) {
                Toast.makeText(requireContext(), "No recording to play", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            togglePlayback(filePath)
        }

        binding.saveButton.setOnClickListener {
            showSaveDiscardDialog(filePath)
        }

        binding.discardButton.setOnClickListener {
            showSaveDiscardDialog(filePath)
        }
    }

    private fun setupWaveformChart() {
        val chart = binding.waveformChart
        chart.description.isEnabled = false
        chart.legend.isEnabled = false
        chart.setNoDataText("Loading waveform...")
        chart.isAutoScaleMinMaxEnabled = false
        chart.setDrawMarkers(false)
        chart.setMaxVisibleValueCount(0)
        chart.setDrawGridBackground(false)

        chart.xAxis.apply {
            position = XAxis.XAxisPosition.BOTTOM
            setDrawGridLines(true)
            gridColor = Color.parseColor("#EAEAEA")
            gridLineWidth = 1f
            granularity = 1f
            textColor = Color.parseColor("#999999")
            textSize = 10f
            setDrawAxisLine(false)
            setDrawLabels(true)

            valueFormatter = object : ValueFormatter() {
                override fun getFormattedValue(value: Float): String {
                    val totalSeconds = value.toInt()
                    if (totalSeconds < 0) return ""
                    val m = totalSeconds / 60
                    val s = totalSeconds % 60
                    return String.format("%02d:%02d", m, s)
                }
            }
        }

        chart.axisLeft.apply {
            setDrawGridLines(true)
            gridColor = Color.parseColor("#EAEAEA")
            gridLineWidth = 1f
            axisMinimum = -1f
            axisMaximum = 1f
            setLabelCount(5, true)
            setDrawLabels(false)
            setDrawAxisLine(false)
        }

        chart.axisRight.isEnabled = false
        chart.setExtraOffsets(2f, 4f, 2f, 4f)
    }

    /**
     * Reads the .wav file in a background thread and plots the entire graph instantly.
     */
    private fun loadFullWaveform(filePath: String) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val file = File(filePath)
                if (!file.exists()) return@launch

                val bytes = file.readBytes()
                val headerSize = 44 // Standard WAV header size
                if (bytes.size <= headerSize) return@launch

                val dataSize = bytes.size - headerSize
                val totalSamples = dataSize / 2 // 16-bit PCM = 2 bytes per sample

                // Downsample to ~3000 points to ensure the chart renders instantly and smoothly
                val pointsToDraw = 3000
                val sampleStep = maxOf(1, totalSamples / pointsToDraw)
                val byteStep = sampleStep * 2

                var maxAmplitude = 0.02f
                val entries = ArrayList<Entry>()

                var sampleIndex = 0
                for (i in headerSize until bytes.size - 1 step byteStep) {
                    val low = bytes[i].toInt() and 0xFF
                    val high = bytes[i + 1].toInt() shl 8
                    val sample = (high or low).toShort().toFloat() / 32768f

                    val absSample = kotlin.math.abs(sample)
                    if (absSample > maxAmplitude) {
                        maxAmplitude = absSample
                    }

                    val timeInSeconds = sampleIndex.toFloat() / INPUT_SAMPLE_RATE
                    entries.add(Entry(timeInSeconds, sample))
                    sampleIndex += sampleStep
                }

                withContext(Dispatchers.Main) {
                    if (_binding == null) return@withContext

                    val dataSet = LineDataSet(entries, "Waveform").apply {
                        color = Color.parseColor("#128CB2") // Clean Teal color
                        setDrawCircles(false)
                        setDrawValues(false)
                        lineWidth = 1.0f
                        mode = LineDataSet.Mode.LINEAR
                        setDrawHighlightIndicators(false)
                    }

                    val chart = binding.waveformChart
                    chart.data = LineData(dataSet)

                    val bound = (maxAmplitude * HEADROOM).coerceAtMost(1.0f)
                    chart.axisLeft.axisMinimum = -bound
                    chart.axisLeft.axisMaximum = bound

                    // Unlock zoom, drag, and pinch controls completely
                    chart.setVisibleXRangeMaximum(Float.MAX_VALUE)
                    chart.setTouchEnabled(true)
                    chart.isDragEnabled = true
                    chart.setScaleEnabled(true)
                    chart.setPinchZoom(true)

                    chart.fitScreen()
                    chart.invalidate()

                    // Set initial timer text to total duration
                    val totalDurationSecs = totalSamples / INPUT_SAMPLE_RATE.toInt()
                    binding.timerText.text = String.format(
                        "%02d:%02d", totalDurationSecs / 60, totalDurationSecs % 60
                    )
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun setupPlayer(filePath: String) {
        try {
            player = TaalPlayer(requireContext()).apply {
                setDataSource(filePath)
                onPlaybackProgress = { timestamp, _ ->
                    activity?.runOnUiThread {
                        if (isAdded && _binding != null) {
                            // Update timer during playback
                            val totalSecs = timestamp.toInt()
                            binding.timerText.text = String.format(
                                "%02d:%02d", totalSecs / 60, totalSecs % 60
                            )
                        }
                    }
                }
                onPlaybackComplete = {
                    activity?.runOnUiThread {
                        if (isAdded && _binding != null) {
                            isPlaying = false
                            binding.actionText.text = getString(R.string.play_recording)
                        }
                    }
                }
            }
        } catch (e: InvalidFileNameException) {
            Toast.makeText(requireContext(), "Cannot open recording", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun togglePlayback(filePath: String) {
        if (isPlaying) {
            player?.stop()
            isPlaying = false
            binding.actionText.text = getString(R.string.play_recording)
            binding.playButton.setImageResource(R.drawable.ic_play_circle)
        } else {
            try {
                player?.prepare()
                player?.start()
                isPlaying = true
                binding.actionText.text = getString(R.string.stop_recording)
                binding.playButton.setImageResource(R.drawable.ic_recording_stop)
            } catch (e: Exception) {
                isPlaying = false
                Toast.makeText(requireContext(), "Playback error: ${e.message}", Toast.LENGTH_LONG)
                    .show()
            }
        }
    }

    private fun showSaveDiscardDialog(filePath: String) {
        PlayerSaveDiscardDialog { action ->
            when (action) {
                PlayerSaveDiscardDialog.Action.SAVE -> {
                    val bundle = Bundle().apply { putString("recordingFilePath", filePath) }
                    findNavController().navigate(R.id.action_player_to_addPatient, bundle)
                }

                PlayerSaveDiscardDialog.Action.DISCARD -> {
                    // Delete the temp recording file and go back to the recording screen
                    try {
                        java.io.File(filePath).delete()
                    } catch (_: Exception) {
                    }
                    findNavController().navigateUp()
                }
            }
        }.show(parentFragmentManager, "save_discard")
    }

    override fun onDestroyView() {
        super.onDestroyView()
        try {
            player?.onPlaybackProgress = null
            player?.onPlaybackComplete = null
            player?.stop()
            player?.release()
        } catch (_: Exception) {
        }
        _binding = null
    }
}