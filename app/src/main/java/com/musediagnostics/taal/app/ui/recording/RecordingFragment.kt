package com.musediagnostics.taal.app.ui.recording

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.musediagnostics.taal.PreFilter
import com.musediagnostics.taal.TaalRecorder
import com.musediagnostics.taal.core.RecorderState
import com.musediagnostics.taal.app.R
import com.musediagnostics.taal.app.databinding.FragmentRecordingBinding
import com.musediagnostics.taal.app.dsp.HeartBpmCalculator
import com.musediagnostics.taal.app.ui.MainActivity
import com.musediagnostics.taal.utils.TaalConnectionBroadcastReceiver
import kotlinx.coroutines.*

class RecordingFragment : Fragment() {

    private var _binding: FragmentRecordingBinding? = null
    private val binding get() = _binding!!
    private val viewModel: RecordingViewModel by viewModels()

    private var taalRecorder: TaalRecorder? = null
    private var audioTrack: AudioTrack? = null
    private val waveformEntries = ArrayList<Entry>()

    private var connectionReceiver: TaalConnectionBroadcastReceiver? = null

    // Persistent dataset — reused every callback to avoid MPAndroidChart's
    // mOffsetsCalculated=false reset that causes a blank frame each time chart.data is replaced.
    private var waveformDataSet: LineDataSet? = null
    private var peakAmplitude = 1.0f       // Y-axis half-range; set from warmup then locked
    private var lastPeakUpdateTime = 0L
    private var warmupPeak = 0f           // Accumulates absolute-peak over the warmup window
    private var warmupDone = false        // Latches true after WARMUP_MS of signal observed
    private var recordingStartTime = 0L
    private var chartInitialized = false
    private var totalSamplesProcessed = 0L  // Sample-accurate X position counter
    private val bpmCalculator = HeartBpmCalculator()
    private val bpmScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    companion object {
        private const val WINDOW_SECONDS = 10f
        private const val INPUT_SAMPLE_RATE = 44100f

        // Fixed downsample step: 44100 / 44 ≈ 1002 points/sec — good visual resolution
        private const val DOWNSAMPLE_STEP = 44

        // WARMUP: measure the signal's true peak over the first 1500ms, then lock in the Y-axis.
        private const val WARMUP_MS = 1500L

        // After warmup, Y-axis = peakAmplitude × HEADROOM so waveform fills ~65% of height.
        private const val HEADROOM = 1.5f

        // Minimum axis half-range — prevents over-zooming on near-silence
        private const val MIN_PEAK = 0.02f
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startRecording()
        } else {
            Toast.makeText(requireContext(), "Audio permission required", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentRecordingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupWaveformChart()
        setupFilterButtons()
        setupPreAmpSlider()
        setupButtons()
        observeState()
        setupConnectionReceiver()
    }


    private fun setupWaveformChart() {
        val chart = binding.waveformChart
        chart.description.isEnabled = false
        chart.legend.isEnabled = false
        chart.setTouchEnabled(false)
        chart.setDrawGridBackground(true)
        chart.setGridBackgroundColor(Color.WHITE)

        chart.xAxis.apply {
            position = XAxis.XAxisPosition.BOTTOM
            setDrawGridLines(true)
            gridColor = Color.parseColor("#F0F0F0")
            gridLineWidth = 1f
            granularity = 1f
            // REMOVED axisMinimum and axisMaximum entirely here
            setLabelCount(10, false)
            setDrawAxisLine(false)
            setDrawLabels(false)
        }

        chart.axisLeft.apply {
            setDrawGridLines(true)
            gridColor = Color.parseColor("#F0F0F0")
            gridLineWidth = 1f
            axisMinimum = -1f
            axisMaximum = 1f
            setLabelCount(5, true)
            setDrawLabels(false)
            setDrawAxisLine(false)
        }

        chart.axisRight.isEnabled = false

        // Lock viewport size to exactly 10 seconds
        chart.setVisibleXRangeMaximum(WINDOW_SECONDS)
        chart.setVisibleXRangeMinimum(WINDOW_SECONDS)

        val dummyDataSet = LineDataSet(listOf(Entry(0f, 0f), Entry(WINDOW_SECONDS, 0f)), "").apply {
            color = Color.TRANSPARENT
            setDrawCircles(false)
            setDrawValues(false)
        }
        chart.data = LineData(dummyDataSet)
        chart.invalidate()
    }

    private fun setupPreAmpSlider() {
        // Init slider to viewModel's current value (survives config changes)
        binding.ampSlider.value = (viewModel.preAmpDb.value ?: 5).toFloat()
        binding.ampLabel.text = "${viewModel.preAmpDb.value ?: 5} dB"

        binding.ampSlider.addOnChangeListener { _, value, _ ->
            val db = value.toInt()
            viewModel.setPreAmp(db)
            binding.ampLabel.text = "$db dB"
            taalRecorder?.setPreAmplification(db)
        }
    }

    private fun setupFilterButtons() {
        val filters = mapOf(
            binding.filterHeart to "HEART",
            binding.filterLungs to "LUNGS",
            binding.filterBowel to "BOWEL",
            binding.filterPregnancy to "PREGNANCY",
            binding.filterInfo to "FULL_BODY"
        )

        // Set HEART as the default selected filter
        binding.filterHeart.isSelected = true
        viewModel.setFilter("HEART")

        filters.forEach { (button, filterName) ->
            button.setOnClickListener {
                // Deselect all
                filters.keys.forEach { it.isSelected = false }
                // Select this one
                button.isSelected = true
                viewModel.setFilter(filterName)
            }
        }
    }

    private fun setupConnectionReceiver() {
        connectionReceiver = TaalConnectionBroadcastReceiver(object :
            TaalConnectionBroadcastReceiver.TaalConnectionListener {
            override fun onTaalConnect() {
                activity?.runOnUiThread {
                    // Device connected: change icon to Teal and keep it that way
                    binding.deviceIcon.setColorFilter(Color.parseColor("#128CB2"))
                }
            }

            override fun onTaalDisconnect() {
                activity?.runOnUiThread {
                    // Device disconnected: change icon back to Gray/Black
                    binding.deviceIcon.setColorFilter(Color.parseColor("#333333"))
                }
            }
        })
        connectionReceiver?.register(requireContext())
    }

    private fun checkDeviceConnectionStatus() {
        try {
            val usbManager =
                requireContext().getSystemService(android.content.Context.USB_SERVICE) as android.hardware.usb.UsbManager

            // If the device list is not empty, a USB device (your TAAL stethoscope) is connected
            if (usbManager.deviceList.isNotEmpty()) {
                binding.deviceIcon.setColorFilter(Color.parseColor("#128CB2")) // Teal
            } else {
                binding.deviceIcon.setColorFilter(Color.parseColor("#333333")) // Black/Gray
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun setupButtons() {
        binding.menuButton.setOnClickListener {
            (activity as? MainActivity)?.openDrawer()
        }

        binding.recordButton.setOnClickListener {
            when (viewModel.uiState.value) {
                RecordingUiState.IDLE -> {
                    checkPermissionAndRecord()
                }

                RecordingUiState.RECORDING -> {
                    stopRecording()
                }

                // RecordingUiState.STOPPED -> {
                //     showSaveDialog()
                // }

                else -> {
                    resetToIdle()
                }
            }
        }

        // binding.saveCheckButton.setOnClickListener { showSaveDialog() }
        // binding.trashButton.setOnClickListener {
        //     resetToIdle()
        //     Toast.makeText(requireContext(), "Recording discarded", Toast.LENGTH_SHORT).show()
        // }

        binding.playPauseButton.setOnClickListener {
            // Navigate to player with current recording
            if (viewModel.currentRecordingPath.isNotEmpty()) {
                val bundle = Bundle().apply {
                    putString("filePath", viewModel.currentRecordingPath)
                    putString("filterName", viewModel.currentFilter.value ?: "HEART")
                }
                findNavController().navigate(R.id.action_recording_to_player, bundle)
            }
        }

        binding.folderButton.setOnClickListener {
            findNavController().navigate(R.id.action_recording_to_savedRecordings)
        }

        binding.settingsButton.setOnClickListener {
            findNavController().navigate(R.id.action_recording_to_newRecording)
        }
    }

    /** Reset everything back to initial idle state */
    private fun resetToIdle() {
        viewModel.setUiState(RecordingUiState.IDLE)
        viewModel.currentRecordingPath = ""
        waveformEntries.clear()
        waveformDataSet = null
        chartInitialized = false
        peakAmplitude = 1.0f
        warmupPeak = 0f
        warmupDone = false
        lastPeakUpdateTime = 0L
        totalSamplesProcessed = 0L

        // Re-apply dummy data so the perfect grid stays visible on reset
        val dummyDataSet = LineDataSet(listOf(Entry(0f, 0f), Entry(10f, 0f)), "").apply {
            color = Color.TRANSPARENT
            setDrawCircles(false)
            setDrawValues(false)
        }
        binding.waveformChart.data = LineData(dummyDataSet)
        binding.waveformChart.moveViewToX(0f)
        binding.waveformChart.invalidate()

        binding.bpmText.text = "-- BPM"
    }

    private fun observeState() {
        viewModel.uiState.observe(viewLifecycleOwner) { state ->
            when (state) {
                RecordingUiState.IDLE -> {
                    binding.actionText.text = getString(R.string.start_recording)
                    binding.recordButton.visibility = View.VISIBLE
                    binding.bottomBar.visibility = View.VISIBLE
                    binding.preRecordingButtons.visibility = View.VISIBLE
                    binding.recordingButtons.visibility = View.GONE
                    binding.recordButton.setImageResource(R.drawable.ic_recording_start1)
                    binding.timerText.text = getString(R.string.timer_default)
                    // Unlock amp controls
                    binding.ampSlider.isEnabled = true
                    binding.ampSliderContainer.alpha = 1f
                }

                RecordingUiState.RECORDING -> {
                    binding.actionText.text = getString(R.string.stop_recording)
                    binding.recordButton.visibility = View.VISIBLE
                    // Hide bottom bar so chart expands to fill the freed space
                    binding.bottomBar.visibility = View.GONE
                    binding.preRecordingButtons.visibility = View.GONE
                    binding.recordingButtons.visibility = View.GONE
                    binding.recordButton.setImageResource(R.drawable.ic_recording_stop)
                    // Lock amp controls
                    binding.ampSlider.isEnabled = false
                    binding.ampSliderContainer.alpha = 0.55f
                }

                // RecordingUiState.STOPPED -> {
                //     binding.actionText.text = "Save or Discard"
                //     binding.recordButton.visibility = View.GONE
                //     binding.preRecordingButtons.visibility = View.GONE
                //     binding.recordingButtons.visibility = View.VISIBLE
                //     // binding.recordingIndicator.visibility = View.GONE  (Removed)
                //     binding.waveformChart.apply {
                //         setTouchEnabled(true)
                //         isDragEnabled = true
                //         setScaleEnabled(true)
                //         setPinchZoom(true)
                //     }
                // }

                else -> {}
            }
        }

        viewModel.timerSeconds.observe(viewLifecycleOwner) { seconds ->
            binding.timerText.text = viewModel.formatTimer(seconds)
        }

        viewModel.bpm.observe(viewLifecycleOwner) { bpm ->
            binding.bpmText.text = if (bpm > 0) getString(R.string.bpm_format, bpm) else "-- BPM"
        }
    }

    private fun checkPermissionAndRecord() {
        if (ContextCompat.checkSelfPermission(
                requireContext(), Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            startRecording()
        } else {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startRecording() {
        try {
            val filePath =
                "${requireContext().filesDir}/recording_${System.currentTimeMillis()}.wav"
            viewModel.currentRecordingPath = filePath

            val filterName = viewModel.currentFilter.value ?: "HEART"
            val preFilter = PreFilter.valueOf(filterName)

            taalRecorder = TaalRecorder(requireContext()).apply {
                setRawAudioFilePath(filePath)
                setRecordingTime(300) // 5 minutes max
                setPlayback(false)
                setPreAmplification(viewModel.preAmpDb.value ?: 5)
                setPreFilter(preFilter)

                onInfoListener = object : TaalRecorder.OnInfoListener {
                    override fun onStateChange(state: RecorderState) {
                        activity?.runOnUiThread {
                            when (state) {
                                RecorderState.RECORDING -> {
                                    viewModel.setUiState(RecordingUiState.RECORDING)
                                }

                                RecorderState.STOPPED -> {
                                    viewModel.setUiState(RecordingUiState.STOPPED)
                                }

                                else -> {}
                            }
                        }
                    }

                    override fun onProgressUpdate(
                        sampleRate: Int, bufferSize: Int, timeStamp: Double, data: FloatArray
                    ) {
                        // Live audio monitoring — write PCM directly on the IO thread
                        audioTrack?.let { track ->
                            val pcm = ShortArray(data.size) { i ->
                                (data[i] * 32767f).toInt().coerceIn(-32768, 32767).toShort()
                            }
                            track.write(pcm, 0, pcm.size)
                        }

                        // Feed BPM calculator (fast - just buffer copy)
                        val shouldCompute = bpmCalculator.addSamples(data)
                        if (shouldCompute) {
                            bpmScope.launch {
                                val bpm = bpmCalculator.computeBpm()
                                if (bpm > 0) {
                                    withContext(Dispatchers.Main) {
                                        if (isAdded && _binding != null) {
                                            viewModel.setBpm(bpm)
                                        }
                                    }
                                }
                            }
                        }

                        activity?.runOnUiThread {
                            if (isAdded && _binding != null) {
                                updateWaveform(timeStamp, data)
                                val elapsed = timeStamp.toInt()
                                viewModel.updateTimer(elapsed)
                            }
                        }
                    }
                }
            }

            recordingStartTime = System.currentTimeMillis()
            waveformEntries.clear()
            waveformDataSet = null
            chartInitialized = false
            peakAmplitude = 1.0f
            warmupPeak = 0f
            warmupDone = false
            lastPeakUpdateTime = 0L
            totalSamplesProcessed = 0L
            bpmCalculator.reset()
            binding.waveformChart.data = LineData()
            binding.waveformChart.moveViewToX(0f)
            startAudioMonitor()
            taalRecorder?.start()

        } catch (e: Exception) {
            // Centered dialog for modern Android versions
            com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle("Recording Error").setMessage(e.message)
                .setPositiveButton("OKAY") { dialog, _ ->
                    dialog.dismiss()
                }.show()
            viewModel.setUiState(RecordingUiState.IDLE)
        }
    }

    private fun startAudioMonitor() {
        val minBuf = AudioTrack.getMinBufferSize(
            44100, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        audioTrack = AudioTrack(
            AudioManager.STREAM_MUSIC,
            44100,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuf * 2,
            AudioTrack.MODE_STREAM
        ).apply { play() }
    }

    private fun stopAudioMonitor() {
        try {
            audioTrack?.stop()
        } catch (_: Exception) {
        }
        try {
            audioTrack?.release()
        } catch (_: Exception) {
        }
        audioTrack = null
    }

    private fun stopRecording() {
        stopAudioMonitor()
        try {
            taalRecorder?.stop()
        } catch (_: Exception) {
        }
        taalRecorder = null
        // Navigate directly to player instead of showing Save/Discard dialog
        val path = viewModel.currentRecordingPath
        if (path.isNotEmpty()) {
            val bundle = Bundle().apply {
                putString("filePath", path)
                putBoolean("isNewRecording", true)
                putString("filterName", viewModel.currentFilter.value ?: "HEART")
            }

            findNavController().navigate(R.id.action_recording_to_player, bundle)
        }
        // viewModel.setUiState(RecordingUiState.STOPPED) // replaced by direct navigation
    }

    /**
     * V7 — Sample-accurate X positioning + stable Y-axis.
     *
     * X-axis: use a monotonically incrementing sample counter (totalSamplesProcessed)
     * divided by the sample rate to get jitter-free X positions. The chart has a fixed
     * 0–300s range pre-drawn; we pan it with moveViewToX() for smooth scrolling.
     *
     * Y-axis (two-phase):
     *   Phase 1 — WARMUP (first 1500ms): accumulate true peak; axis stays ±1.0.
     *   Phase 2 — LOCKED: axis only expands when signal exceeds current scale.
     *   The Y-axis is only written when peakAmplitude actually changes, eliminating
     *   the constant min/max updates that caused the grid to flicker.
     */
    private fun updateWaveform(timestamp: Double, data: FloatArray) {
        if (_binding == null || !isAdded) return
        val chart = binding.waveformChart

        var bufferPeak = 0f
        for (sample in data) {
            val abs = Math.abs(sample)
            if (abs > bufferPeak) bufferPeak = abs
        }

        // CONTINUOUS X LOGIC: X grows infinitely.
        val step = DOWNSAMPLE_STEP
        for (i in 0 until data.size step step) {
            val currentX = totalSamplesProcessed.toFloat() / INPUT_SAMPLE_RATE
            waveformEntries.add(Entry(currentX, data[i]))
            totalSamplesProcessed += step
        }

        val latestX = totalSamplesProcessed.toFloat() / INPUT_SAMPLE_RATE

        // PAGE CALCULATION: Determine which 10-second block we are currently in
        val currentPage = (latestX / WINDOW_SECONDS).toInt()
        val currentViewX = currentPage * WINDOW_SECONDS

        // MEMORY CLEANUP: Keep data for the current page and the immediate previous page.
        val minXToKeep = (currentPage - 1) * WINDOW_SECONDS
        if (minXToKeep > 0) {
            val iterator = waveformEntries.iterator()
            while (iterator.hasNext()) {
                if (iterator.next().x < minXToKeep) {
                    iterator.remove()
                } else {
                    break // Stop iterating once we reach visible data
                }
            }
        }

        val now = System.currentTimeMillis()
        if (!warmupDone) {
            if (bufferPeak > warmupPeak) warmupPeak = bufferPeak
            if (lastPeakUpdateTime == 0L) lastPeakUpdateTime = now
            if (now - lastPeakUpdateTime >= WARMUP_MS) {
                warmupDone = true
                peakAmplitude = (warmupPeak * HEADROOM).coerceIn(MIN_PEAK, 1.0f)
                chart.axisLeft.axisMinimum = -peakAmplitude
                chart.axisLeft.axisMaximum = peakAmplitude
            }
        } else {
            val needed = bufferPeak * HEADROOM
            if (needed > peakAmplitude) {
                peakAmplitude = needed.coerceAtMost(1.0f)
                chart.axisLeft.axisMinimum = -peakAmplitude
                chart.axisLeft.axisMaximum = peakAmplitude
            }
        }

        val snapshot = ArrayList(waveformEntries)
        val ds = waveformDataSet

        if (ds == null || chart.data == null) {
            waveformDataSet = LineDataSet(snapshot, "Waveform").apply {
                color = ContextCompat.getColor(requireContext(), R.color.waveform_blue)
                setDrawCircles(false)
                setDrawValues(false)
                lineWidth = 1.5f
                mode = LineDataSet.Mode.LINEAR
                setDrawHighlightIndicators(false)
            }
            chart.data = LineData(waveformDataSet)
        } else {
            ds.values = snapshot
            chart.data?.notifyDataChanged()
        }

        // Notify the chart that the underlying data arrays have changed
        chart.notifyDataSetChanged()

        // Re-enforce the strict 10-second view width
        chart.setVisibleXRangeMaximum(WINDOW_SECONDS)
        chart.setVisibleXRangeMinimum(WINDOW_SECONDS)

        // PUSH CAMERA: Snap the view to the start of the current 10-second page
        chart.moveViewToX(currentViewX)

        // CRITICAL FIX: Force the chart to physically redraw the new pixels on the screen!
        chart.invalidate()
    }
    // private fun showSaveDialog() {
    //     val dialog = SaveAlertDialog { saveType ->
    //         when (saveType) {
    //             SaveAlertDialog.SaveType.SAVE      -> navigateToAddPatient()
    //             SaveAlertDialog.SaveType.EMERGENCY -> showEmergencySaveDialog()
    //             SaveAlertDialog.SaveType.DISCARD   -> resetToIdle()
    //         }
    //     }
    //     dialog.show(parentFragmentManager, "save_alert")
    // }

    // private fun showEmergencySaveDialog() {
    //     val dialog = EmergencySaveDialog { fileName ->
    //         if (fileName != null) {
    //             viewModel.currentRecordingPath.let { path ->
    //                 if (path.isNotEmpty()) {
    //                     val file = java.io.File(path)
    //                     val newFile = java.io.File(file.parentFile, "EMERGENCY_${fileName}_${System.currentTimeMillis()}.wav")
    //                     file.renameTo(newFile)
    //                     viewModel.currentRecordingPath = newFile.absolutePath
    //                 }
    //             }
    //             Toast.makeText(requireContext(), getString(R.string.recording_saved), Toast.LENGTH_SHORT).show()
    //             resetToIdle()
    //         }
    //     }
    //     dialog.show(parentFragmentManager, "emergency_save")
    // }

    // private fun navigateToAddPatient() {
    //     val bundle = Bundle().apply { putString("recordingFilePath", viewModel.currentRecordingPath) }
    //     findNavController().navigate(R.id.action_recording_to_addPatient, bundle)
    // }

    override fun onResume() {
        super.onResume()

        checkDeviceConnectionStatus()
        if (taalRecorder == null) {
            resetToIdle()
        }
        // Reset pre-amp slider to default 5dB on every resume
        viewModel.setPreAmp(5)
        binding.ampSlider.value = 5f
        binding.ampLabel.text = "5 dB"
        // Recorder is null but state is RECORDING → we stopped and navigated to PlayerFragment,
        // then the user pressed Discard and popped back here. Reset to idle.
//        if (taalRecorder == null && viewModel.uiState.value == RecordingUiState.RECORDING) {
//            resetToIdle()
//        }
        // Commented out: old Save/Discard flow via AddPatientFragment
        // if (viewModel.uiState.value == RecordingUiState.STOPPED) {
        //     resetToIdle()
        // }

    }

    override fun onDestroyView() {
        super.onDestroyView()
        connectionReceiver?.unregister(requireContext())
        _binding = null
    }

    override fun onDestroy() {
        super.onDestroy()
        bpmScope.cancel()
        stopAudioMonitor()
        try {
            taalRecorder?.stop()
        } catch (_: Exception) {
        }
    }
}
