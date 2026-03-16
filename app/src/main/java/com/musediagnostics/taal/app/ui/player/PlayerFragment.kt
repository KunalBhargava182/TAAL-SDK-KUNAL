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
        val filterName = arguments?.getString("filterName") ?: "HEART"

        binding.saveDiscardBar.visibility = if (isNewRecording) View.VISIBLE else View.GONE

        setupWaveformChart()
        setupAmpSlider()

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
            if (isNewRecording) {
                val bundle = Bundle().apply {
                    putString("filePath", filePath)
                    putString("filterName", filterName)
                }
                findNavController().navigate(R.id.action_player_to_saveRecording, bundle)
            } else {
                showSaveDiscardDialog(filePath)
            }
        }

        binding.discardButton.setOnClickListener {
            if (isNewRecording) {
                showDiscardConfirmation(filePath)
            } else {
                showSaveDiscardDialog(filePath)
            }
        }
    }

    private fun setupAmpSlider() {
        binding.ampSlider.addOnChangeListener { _, value, _ ->
            val db = value.toInt()
            binding.ampLabel.text = "$db dB"
            player?.setPreAmplification(db.toFloat())
        }
    }

    private fun setupWaveformChart() {
        val chart = binding.waveformChart
        chart.description.isEnabled = false
        chart.legend.isEnabled = false
        chart.setDrawGridBackground(true)
        chart.setGridBackgroundColor(Color.WHITE)

        chart.xAxis.apply {
            position = XAxis.XAxisPosition.BOTTOM
            setDrawGridLines(true)
            gridColor = Color.parseColor("#F0F0F0")
            gridLineWidth = 1f
            setDrawAxisLine(false)
            setDrawLabels(false)
        }

        chart.axisLeft.apply {
            setDrawGridLines(true)
            gridColor = Color.parseColor("#F0F0F0")
            gridLineWidth = 1f

            axisMinimum = -0.5f
            axisMaximum = 0.5f

            setDrawLabels(false)
            setDrawAxisLine(false)
        }

        chart.axisRight.isEnabled = false
    }

    private fun loadFullWaveform(filePath: String) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val file = File(filePath)
                if (!file.exists()) return@launch

                val bytes = file.readBytes()
                val dataSize = bytes.size - 44
                val totalSamples = dataSize / 2
                val totalDurationSeconds = (totalSamples / INPUT_SAMPLE_RATE).toInt()

                val sampleStep = maxOf(1, totalSamples / 3000)
                val entries = ArrayList<Entry>()
                var sampleIndex = 0

                for (i in 44 until bytes.size - 1 step sampleStep * 2) {
                    val low = bytes[i].toInt() and 0xFF
                    val high = bytes[i + 1].toInt() shl 8
                    val sample = (high or low).toShort().toFloat() / 32768f
                    entries.add(Entry((sampleIndex.toFloat() / INPUT_SAMPLE_RATE), sample))
                    sampleIndex += sampleStep
                }

                withContext(Dispatchers.Main) {
                    if (_binding == null) return@withContext

                    binding.timerText.text = String.format(
                        "%02d:%02d", totalDurationSeconds / 60, totalDurationSeconds % 60
                    )

                    val dataSet = LineDataSet(entries, "Waveform").apply {
                        color = Color.parseColor("#2D7DD2")
                        setDrawCircles(false)
                        setDrawValues(false)
                        lineWidth = 2.5f
                        mode = LineDataSet.Mode.LINEAR
                    }
                    binding.waveformChart.apply {
                        data = LineData(dataSet)

                        setVisibleXRangeMaximum(4f)

                        // FIX 1: Initial load centers the view at the start without jumping to the top
                        centerViewTo(
                            2f,
                            0f,
                            com.github.mikephil.charting.components.YAxis.AxisDependency.LEFT
                        )

                        setTouchEnabled(true)
                        isDragEnabled = true

                        // TIP: If you want to stop the user from messing up the vertical scale and only allow horizontal zooming,
                        // change setScaleEnabled(true) to setScaleXEnabled(true) and setScaleYEnabled(false).
                        setScaleEnabled(true)
                        invalidate()
                    }
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
                            val totalSecs = timestamp.toInt()
                            binding.timerText.text = String.format(
                                "%02d:%02d", totalSecs / 60, totalSecs % 60
                            )

                            // FIX 2: Dynamic centering that strictly anchors Y to 0f
                            val chart = binding.waveformChart
                            val currentVisibleRange = chart.visibleXRange
                            val halfRange = currentVisibleRange / 2f

                            // If we are at the very beginning, keep the left edge at 0
                            val centerX =
                                if (timestamp.toFloat() < halfRange) halfRange else timestamp.toFloat()

                            chart.centerViewTo(
                                centerX,
                                0f,
                                com.github.mikephil.charting.components.YAxis.AxisDependency.LEFT
                            )
                        }
                    }
                }
                onPlaybackComplete = {
                    activity?.runOnUiThread {
                        if (isAdded && _binding != null) {
                            isPlaying = false
                            binding.actionText.text = getString(R.string.play_recording)
                            binding.playButton.setImageResource(R.drawable.ic_play_circle)

                            // FIX 3: Snap graph back to the beginning gracefully
                            val chart = binding.waveformChart
                            chart.centerViewTo(
                                chart.visibleXRange / 2f,
                                0f,
                                com.github.mikephil.charting.components.YAxis.AxisDependency.LEFT
                            )
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

            // FIX 4: Snap graph back to the beginning gracefully when manually stopped
            val chart = binding.waveformChart
            chart.centerViewTo(
                chart.visibleXRange / 2f,
                0f,
                com.github.mikephil.charting.components.YAxis.AxisDependency.LEFT
            )
        } else {
            try {
                // RESET GRAPH: Snap back to the beginning gracefully when played
                val chart = binding.waveformChart
                chart.centerViewTo(
                    chart.visibleXRange / 2f,
                    0f,
                    com.github.mikephil.charting.components.YAxis.AxisDependency.LEFT
                )

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

    private fun showDiscardConfirmation(filePath: String) {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle("Discard Recording")
            .setMessage("Are you sure you want to discard this recording? It will be permanently deleted.")
            .setPositiveButton("Discard") { _, _ ->
                try {
                    java.io.File(filePath).delete()
                } catch (_: Exception) {
                }
                findNavController().navigateUp()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun showSaveDiscardDialog(filePath: String) {
        PlayerSaveDiscardDialog { action ->
            when (action) {
                PlayerSaveDiscardDialog.Action.SAVE -> {
                    val bundle = Bundle().apply { putString("recordingFilePath", filePath) }
                    findNavController().navigate(R.id.action_player_to_addPatient, bundle)
                }

                PlayerSaveDiscardDialog.Action.DISCARD -> {
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