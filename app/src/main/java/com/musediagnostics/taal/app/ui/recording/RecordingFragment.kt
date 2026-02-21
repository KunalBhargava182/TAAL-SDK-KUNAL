package com.musediagnostics.taal.app.ui.recording

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
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
import android.graphics.Canvas
import android.graphics.Paint
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.renderer.XAxisRenderer
import com.github.mikephil.charting.utils.Transformer
import com.github.mikephil.charting.utils.ViewPortHandler
import com.musediagnostics.taal.PreFilter
import com.musediagnostics.taal.TaalRecorder
import com.musediagnostics.taal.core.RecorderState
import com.musediagnostics.taal.app.R
import com.musediagnostics.taal.app.databinding.FragmentRecordingBinding
import com.musediagnostics.taal.app.dsp.HeartBpmCalculator
import com.musediagnostics.taal.app.ui.MainActivity
import kotlinx.coroutines.*

class RecordingFragment : Fragment() {

    private var _binding: FragmentRecordingBinding? = null
    private val binding get() = _binding!!
    private val viewModel: RecordingViewModel by viewModels()

    private var taalRecorder: TaalRecorder? = null
    private val waveformEntries = ArrayList<Entry>(MAX_VISIBLE_POINTS)
    // Persistent dataset — reused every callback to avoid MPAndroidChart's
    // mOffsetsCalculated=false reset that causes a blank frame each time chart.data is replaced.
    private var waveformDataSet: LineDataSet? = null
    private var peakAmplitude = 1.0f       // Y-axis half-range; set from warmup then slow-decays
    private var lastPeakUpdateTime = 0L
    private var warmupPeak = 0f           // Accumulates absolute-peak over the warmup window
    private var warmupDone = false        // Latches true after WARMUP_MS of signal observed
    private var recordingStartTime = 0L
    private var chartInitialized = false
    private val bpmCalculator = HeartBpmCalculator()
    private val bpmScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    companion object {
        private const val WINDOW_SECONDS = 10f
        private const val INPUT_SAMPLE_RATE = 44100f
        // Display at 8kHz — covers 0-4kHz which captures all cardiac/pulmonary sounds.
        private const val DISPLAY_SAMPLE_RATE = 8000f
        private const val TARGET_POINTS_IN_WINDOW = 2000
        private const val MAX_VISIBLE_POINTS = 4000
        // WARMUP: measure the signal's true peak over the first 1500ms, then lock in the
        // Y-axis scale. After warmup, scale only drifts DOWN at 0.1% per callback (very slow),
        // preventing the pulsing/inflating the previous exponential-blend approach caused.
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
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentRecordingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupWaveformChart()
        setupFilterButtons()
        setupButtons()
        observeState()
    }

    private fun setupWaveformChart() {
        val chart = binding.waveformChart
        chart.description.isEnabled = false
        chart.legend.isEnabled = false
        chart.setTouchEnabled(false)
        chart.setNoDataText("")
        chart.isAutoScaleMinMaxEnabled = false
        chart.isDragEnabled = false
        chart.setScaleEnabled(false)
        chart.setPinchZoom(false)
        chart.setDrawMarkers(false)
        chart.setMaxVisibleValueCount(0)

        // ECG paper background — classic warm pink
        chart.setDrawGridBackground(true)
        chart.setGridBackgroundColor(Color.parseColor("#FFF5F5"))

        // X axis: labels at every 0.2s (large ECG box = 5 small boxes)
        chart.xAxis.apply {
            position = XAxis.XAxisPosition.BOTTOM
            // Draw MPAndroidChart's built-in grid at 0.04s intervals (small ECG boxes)
            setDrawGridLines(true)
            gridColor = Color.parseColor("#FFCCCC")   // light — small box color
            gridLineWidth = 0.5f
            granularity = 0.04f                        // small box = 0.04s
            textColor = Color.parseColor("#CC9999")
            textSize = 8f
            // Only draw text labels at every 0.20s (large box boundary)
            // We achieve this by setting labelCount to match the window / 0.2s
            setLabelCount(6, false)                   // ~0.2s apart on 10s window
            setAvoidFirstLastClipping(true)
            setDrawAxisLine(false)
            setDrawLabels(true)
        }

        // Install custom renderer that overlays thick lines at 0.20s boundaries
        chart.setXAxisRenderer(EcgXAxisRenderer(
            chart.viewPortHandler, chart.xAxis, chart.getTransformer(com.github.mikephil.charting.components.YAxis.AxisDependency.LEFT)
        ))

        // Y axis: horizontal grid — 5 large ECG box rows
        chart.axisLeft.apply {
            setDrawGridLines(true)
            gridColor = Color.parseColor("#FFCCCC")   // light — small box color
            gridLineWidth = 0.5f
            setLabelCount(5, true)
            setDrawLabels(false)
            setDrawAxisLine(false)
        }

        chart.axisRight.isEnabled = false
        chart.setExtraOffsets(2f, 4f, 2f, 4f)
        chart.setLayerType(View.LAYER_TYPE_NONE, null)
    }

    /**
     * Custom X-axis renderer that draws two grid densities:
     *   - Thin lines every 0.04s  (small ECG box — built into MPAndroidChart's xAxis)
     *   - Thick lines every 0.20s (large ECG box — drawn here, 5× heavier)
     * This matches standard ECG paper where 5 small boxes = 1 large box.
     */
    inner class EcgXAxisRenderer(
        viewPortHandler: ViewPortHandler,
        xAxis: com.github.mikephil.charting.components.XAxis,
        trans: Transformer
    ) : XAxisRenderer(viewPortHandler, xAxis, trans) {

        private val largePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#E89999")   // darker pink — large box color
            strokeWidth = 1.5f
            style = Paint.Style.STROKE
        }

        override fun renderGridLines(c: Canvas) {
            // Let the parent draw the thin small-box lines (0.04s interval)
            super.renderGridLines(c)

            // Then overlay thick large-box lines at every 0.20s boundary
            if (!mXAxis.isDrawGridLinesEnabled) return
            val range = mViewPortHandler.contentRect
            val clipRestoreCount = c.save()
            c.clipRect(range)

            val axisMin = mXAxis.axisMinimum
            val axisMax = mXAxis.axisMaximum

            // Find the first 0.20s boundary >= axisMin
            val largeBox = 0.20f
            var x = Math.ceil((axisMin / largeBox).toDouble()).toFloat() * largeBox

            val pts = FloatArray(4)
            while (x <= axisMax) {
                pts[0] = x; pts[1] = 0f; pts[2] = x; pts[3] = 1f
                mTrans.pointValuesToPixel(pts)
                c.drawLine(pts[0], range.top, pts[0], range.bottom, largePaint)
                x += largeBox
            }
            c.restoreToCount(clipRestoreCount)
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
                RecordingUiState.STOPPED -> {
                    // In STOPPED state, the record button should force save/discard dialog
                    showSaveDialog()
                }
                else -> {
                    resetToIdle()
                }
            }
        }

        binding.saveCheckButton.setOnClickListener {
            showSaveDialog()
        }

        binding.trashButton.setOnClickListener {
            resetToIdle()
            Toast.makeText(requireContext(), "Recording discarded", Toast.LENGTH_SHORT).show()
        }

        binding.playPauseButton.setOnClickListener {
            // Navigate to player with current recording
            if (viewModel.currentRecordingPath.isNotEmpty()) {
                val bundle = Bundle().apply {
                    putString("filePath", viewModel.currentRecordingPath)
                }
                findNavController().navigate(R.id.action_recording_to_player, bundle)
            }
        }

        binding.folderButton.setOnClickListener {
            findNavController().navigate(R.id.action_recording_to_library)
        }

        binding.settingsButton.setOnClickListener {
            Toast.makeText(requireContext(), "Settings coming soon", Toast.LENGTH_SHORT).show()
        }
    }

    /** Reset everything back to initial idle state */
    private fun resetToIdle() {
        viewModel.setUiState(RecordingUiState.IDLE)
        viewModel.currentRecordingPath = ""
        waveformEntries.clear()
        waveformDataSet = null
        chartInitialized = false
        peakAmplitude = 1.0f  // Wide open until warmup locks it in
        warmupPeak = 0f
        warmupDone = false
        lastPeakUpdateTime = 0L
        binding.waveformChart.clear()
        binding.waveformChart.data = null
        binding.waveformChart.invalidate()
        binding.bpmText.text = "-- BPM"
    }

    private fun observeState() {
        viewModel.uiState.observe(viewLifecycleOwner) { state ->
            when (state) {
                RecordingUiState.IDLE -> {
                    binding.actionText.text = getString(R.string.start_recording)
                    binding.recordButton.visibility = View.VISIBLE
                    binding.preRecordingButtons.visibility = View.VISIBLE
                    binding.recordingButtons.visibility = View.GONE
                    binding.recordingIndicator.visibility = View.GONE
                    binding.timerText.text = getString(R.string.timer_default)
                }
                RecordingUiState.RECORDING -> {
                    binding.actionText.text = getString(R.string.stop_recording)
                    binding.recordButton.visibility = View.VISIBLE
                    binding.preRecordingButtons.visibility = View.GONE
                    binding.recordingButtons.visibility = View.GONE
                    binding.recordingIndicator.visibility = View.VISIBLE
                }
                RecordingUiState.STOPPED -> {
                    binding.actionText.text = "Save or Discard"
                    binding.recordButton.visibility = View.GONE
                    binding.preRecordingButtons.visibility = View.GONE
                    binding.recordingButtons.visibility = View.VISIBLE
                    binding.recordingIndicator.visibility = View.GONE
                }
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
            val filePath = "${requireContext().filesDir}/recording_${System.currentTimeMillis()}.wav"
            viewModel.currentRecordingPath = filePath

            val filterName = viewModel.currentFilter.value ?: "HEART"
            val preFilter = PreFilter.valueOf(filterName)

            taalRecorder = TaalRecorder(requireContext()).apply {
                setRawAudioFilePath(filePath)
                setRecordingTime(300) // 5 minutes max
                setPlayback(false)
                setPreAmplification(5)
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
                        sampleRate: Int,
                        bufferSize: Int,
                        timeStamp: Double,
                        data: FloatArray
                    ) {
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
            peakAmplitude = 1.0f  // Wide open until warmup locks it in
            warmupPeak = 0f
            warmupDone = false
            lastPeakUpdateTime = 0L
            bpmCalculator.reset()
            binding.waveformChart.clear()
            binding.waveformChart.data = null
            taalRecorder?.start()

        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Error: ${e.message}", Toast.LENGTH_LONG).show()
            viewModel.setUiState(RecordingUiState.IDLE)
        }
    }

    private fun stopRecording() {
        try {
            taalRecorder?.stop()
        } catch (_: Exception) {}
        taalRecorder = null  // Null out so onResume can detect return from AddPatient
        viewModel.setUiState(RecordingUiState.STOPPED)
    }

    /**
     * RED-TEAM V6 — Warmup-based peak detection. No DISPLAY_GAIN.
     *
     * PROBLEM WITH V5 (DISPLAY_GAIN=100):
     *   Assumed signal was quiet (RMS ~0.001). Phone mic via AudioRecord.DEFAULT delivers
     *   normal mic levels (RMS 0.05–0.3). Multiplying by 100 clips everything to ±1.0,
     *   producing a square wave and a Y-axis permanently stuck at ±1.0.
     *   The exponential-blend RMS chasing caused rapid pulsing (inflating/deflating).
     *
     * NEW APPROACH — two-phase scaling:
     *
     *   Phase 1 — WARMUP (first WARMUP_MS = 1500ms):
     *     Accumulate the highest absolute sample seen across ALL buffers in this window.
     *     Y-axis = ±1.0 (default, stable) while measuring.
     *     No updates to peakAmplitude yet.
     *
     *   Phase 2 — LOCKED (after warmup):
     *     Set peakAmplitude = warmupPeak × HEADROOM once, at warmup completion.
     *     Then allow ONLY slow downward drift: multiply by 0.999 per callback.
     *     If a new buffer's peak exceeds current peakAmplitude, immediately expand upward.
     *     This gives a stable axis that auto-zooms in after 1.5s and never pulses.
     */
    private fun updateWaveform(timestamp: Double, data: FloatArray) {
        if (_binding == null || !isAdded) return
        val chart = binding.waveformChart

        val bufferDuration = data.size.toFloat() / INPUT_SAMPLE_RATE

        // Scrolling window: grow from 0..10s first, then slide.
        // Without coerceAtLeast, windowEnd stays near 0 for the first 10s and the waveform
        // renders as a tiny sliver in the far-right corner of a huge negative-X range.
        val windowEnd = maxOf(timestamp.toFloat() + bufferDuration, WINDOW_SECONDS)
        val windowStart = windowEnd - WINDOW_SECONDS

        // Adaptive downsampling (V3 formula — verified correct)
        val displayStep = maxOf(1, (INPUT_SAMPLE_RATE / DISPLAY_SAMPLE_RATE).toInt())
        val budgetStep = maxOf(1, data.size / maxOf(1,
            ((bufferDuration / WINDOW_SECONDS) * TARGET_POINTS_IN_WINDOW).toInt()))
        val step = maxOf(displayStep, budgetStep)

        // Absolute peak of this buffer (max |sample|) — more stable than RMS for axis scaling
        var bufferPeak = 0f
        for (sample in data) {
            val abs = Math.abs(sample)
            if (abs > bufferPeak) bufferPeak = abs
        }

        // Add downsampled entries (raw samples, no gain — data is already at correct level)
        for (i in 0 until data.size step step) {
            val x = timestamp.toFloat() + (i.toFloat() / INPUT_SAMPLE_RATE)
            waveformEntries.add(Entry(x, data[i]))
        }

        val now = System.currentTimeMillis()

        if (!warmupDone) {
            // Phase 1: accumulate peak during warmup window
            if (bufferPeak > warmupPeak) warmupPeak = bufferPeak
            if (lastPeakUpdateTime == 0L) lastPeakUpdateTime = now

            if (now - lastPeakUpdateTime >= WARMUP_MS) {
                // Warmup complete — lock in the scale
                warmupDone = true
                peakAmplitude = (warmupPeak * HEADROOM).coerceIn(MIN_PEAK, 1.0f)
            }
            // During warmup keep axis at ±1.0 — stable, no zooming in yet
        } else {
            // Phase 2: axis is locked. Allow ONLY:
            //   • Immediate upward expansion if signal exceeds current scale
            //   • Very slow downward drift (0.1% per callback ≈ 2% per second at 20 callbacks/s)
            val needed = bufferPeak * HEADROOM
            peakAmplitude = if (needed > peakAmplitude) {
                needed.coerceAtMost(1.0f)          // expand instantly
            } else {
                (peakAmplitude * 0.999f).coerceAtLeast(MIN_PEAK)  // drift down imperceptibly
            }
        }

        // Trim entries that have scrolled out of the visible window.
        // Use subList().clear() instead of repeated removeAt(0) — O(n) once vs O(n²).
        val trimIndex = waveformEntries.indexOfFirst { it.x >= windowStart }
        if (trimIndex > 0) waveformEntries.subList(0, trimIndex).clear()
        if (waveformEntries.size > MAX_VISIBLE_POINTS)
            waveformEntries.subList(0, waveformEntries.size - MAX_VISIBLE_POINTS).clear()

        // Guard: only update axes when range is valid
        if (windowStart < windowEnd) {
            chart.xAxis.axisMinimum = windowStart
            chart.xAxis.axisMaximum = windowEnd
        }
        if (peakAmplitude > 0f) {
            chart.axisLeft.axisMinimum = -peakAmplitude
            chart.axisLeft.axisMaximum = peakAmplitude
        }

        // Reuse the persistent dataset to avoid MPAndroidChart resetting its transformer
        // state (mOffsetsCalculated=false) on every callback, which caused a blank frame
        // each time chart.data was replaced with a new LineData object.
        val snapshot = ArrayList(waveformEntries.toList())
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
        chart.notifyDataSetChanged()
        chart.invalidate()
    }

    private fun showSaveDialog() {
        val dialog = SaveAlertDialog { saveType ->
            when (saveType) {
                SaveAlertDialog.SaveType.SAVE -> {
                    navigateToAddPatient()
                }
                SaveAlertDialog.SaveType.EMERGENCY -> {
                    showEmergencySaveDialog()
                }
                SaveAlertDialog.SaveType.DISCARD -> {
                    resetToIdle()
                }
            }
        }
        dialog.show(parentFragmentManager, "save_alert")
    }

    private fun showEmergencySaveDialog() {
        val dialog = EmergencySaveDialog { fileName ->
            if (fileName != null) {
                viewModel.currentRecordingPath.let { path ->
                    if (path.isNotEmpty()) {
                        val file = java.io.File(path)
                        val dir = file.parentFile
                        val newFile = java.io.File(dir, "EMERGENCY_${fileName}_${System.currentTimeMillis()}.wav")
                        file.renameTo(newFile)
                        viewModel.currentRecordingPath = newFile.absolutePath
                    }
                }
                Toast.makeText(requireContext(), getString(R.string.recording_saved), Toast.LENGTH_SHORT).show()
                resetToIdle()
            }
        }
        dialog.show(parentFragmentManager, "emergency_save")
    }

    private fun navigateToAddPatient() {
        val bundle = Bundle().apply {
            putString("recordingFilePath", viewModel.currentRecordingPath)
        }
        findNavController().navigate(R.id.action_recording_to_addPatient, bundle)
    }

    override fun onResume() {
        super.onResume()
        // If we're coming back from AddPatient (save flow), reset to idle.
        // After stopRecording(), taalRecorder is null. If state is still STOPPED
        // when onResume fires, it means we navigated away (to AddPatient) and came back.
        if (viewModel.uiState.value == RecordingUiState.STOPPED) {
            resetToIdle()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onDestroy() {
        super.onDestroy()
        bpmScope.cancel()
        try {
            taalRecorder?.stop()
        } catch (_: Exception) {}
    }
}
