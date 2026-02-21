package com.musediagnostics.taal.app.ui.player

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.renderer.XAxisRenderer
import com.github.mikephil.charting.utils.Transformer
import com.github.mikephil.charting.utils.ViewPortHandler
import com.musediagnostics.taal.InvalidFileNameException
import com.musediagnostics.taal.TaalPlayer
import com.musediagnostics.taal.app.R
import com.musediagnostics.taal.app.databinding.FragmentPlayerBinding

class PlayerFragment : Fragment() {

    private var _binding: FragmentPlayerBinding? = null
    private val binding get() = _binding!!
    private var player: TaalPlayer? = null
    private val waveformEntries = ArrayList<Entry>(MAX_VISIBLE_POINTS)
    // Persistent dataset — reused every buffer callback so MPAndroidChart never sees
    // a "no data" frame between the old dataset being discarded and the new one being set,
    // which was the cause of the waveform flickering.
    private var waveformDataSet: LineDataSet? = null
    private var peakAmplitude = 1.0f  // Y-axis half-range; set from warmup then slow-decays
    private var lastPeakUpdateTime = 0L
    private var warmupPeak = 0f      // Accumulates absolute-peak over warmup window
    private var warmupDone = false   // Latches true after WARMUP_MS of signal observed
    private var isPlaying = false

    companion object {
        private const val WINDOW_SECONDS = 10f
        private const val INPUT_SAMPLE_RATE = 44100f
        private const val DISPLAY_SAMPLE_RATE = 8000f
        private const val TARGET_POINTS_IN_WINDOW = 2000
        private const val MAX_VISIBLE_POINTS = 4000
        // V6 warmup-based scaling — identical approach to RecordingFragment.
        // Measure true signal peak over first WARMUP_MS, then lock scale with headroom.
        private const val WARMUP_MS = 1500L
        private const val HEADROOM = 1.5f
        private const val MIN_PEAK = 0.02f
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPlayerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val filePath = arguments?.getString("filePath") ?: ""

        setupWaveformChart()

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
    }

    /**
     * ECG paper grid — matches standard ECG paper specification:
     *   Small box = 0.04s (thin pink grid lines, granularity = 0.04)
     *   Large box = 0.20s (darker pink, thick — drawn by EcgXAxisRenderer overlay)
     */
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
        // Prevent MPAndroidChart's drawValues renderer from computing a visible-point
        // count when the axis range is briefly inverted (yields NegativeArraySizeException).
        chart.setMaxVisibleValueCount(0)

        // ECG paper background — classic warm pink
        chart.setDrawGridBackground(true)
        chart.setGridBackgroundColor(Color.parseColor("#FFF5F5"))

        // X axis: small ECG box = 0.04s (thin lines)
        chart.xAxis.apply {
            position = XAxis.XAxisPosition.BOTTOM
            setDrawGridLines(true)
            gridColor = Color.parseColor("#FFCCCC")   // light pink — small box
            gridLineWidth = 0.5f
            granularity = 0.04f                        // small box width = 0.04s
            textColor = Color.parseColor("#CC9999")
            textSize = 8f
            setLabelCount(6, false)                   // labels roughly every 0.20s on 10s window
            setAvoidFirstLastClipping(true)
            setDrawAxisLine(false)
            setDrawLabels(true)
        }

        // Install custom renderer: overlays thick 0.20s large-box lines on top of thin grid
        chart.setXAxisRenderer(EcgXAxisRenderer(
            chart.viewPortHandler, chart.xAxis,
            chart.getTransformer(com.github.mikephil.charting.components.YAxis.AxisDependency.LEFT)
        ))

        // Y axis: horizontal grid lines (same light pink)
        chart.axisLeft.apply {
            setDrawGridLines(true)
            gridColor = Color.parseColor("#FFCCCC")
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
     * Custom X-axis renderer: thin 0.04s lines from MPAndroidChart's built-in grid,
     * then thick 0.20s lines overlaid here (ECG large-box specification).
     */
    inner class EcgXAxisRenderer(
        viewPortHandler: ViewPortHandler,
        xAxis: com.github.mikephil.charting.components.XAxis,
        trans: Transformer
    ) : XAxisRenderer(viewPortHandler, xAxis, trans) {

        private val largePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#E89999")   // darker pink — large box
            strokeWidth = 1.5f
            style = Paint.Style.STROKE
        }

        override fun renderGridLines(c: Canvas) {
            // Draw thin small-box lines (0.04s) via parent
            super.renderGridLines(c)

            // Overlay thick large-box lines (0.20s)
            if (!mXAxis.isDrawGridLinesEnabled) return
            val range = mViewPortHandler.contentRect
            val clipRestoreCount = c.save()
            c.clipRect(range)

            val axisMin = mXAxis.axisMinimum
            val axisMax = mXAxis.axisMaximum
            val largeBox = 0.20f

            // First 0.20s boundary >= axisMin
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

    /**
     * FIX 4 — Player crash from library:
     *
     * Root causes:
     *   (a) setDataSource() throws InvalidFileNameException if:
     *       - File does not exist (path from DB may be stale)
     *       - Extension is not exactly "wav" (case-sensitive — "WAV" would crash)
     *   (b) Second play: prepare() creates a new AudioTrack without releasing the old one
     *       — this leaks resources and can cause AudioTrack state errors.
     *
     * Fix: wrap setDataSource in try-catch; show user-friendly error and return on failure.
     * Second-play is handled in togglePlayback: call release() before prepare().
     */
    private fun setupPlayer(filePath: String) {
        try {
            player = TaalPlayer(requireContext()).apply {
                setDataSource(filePath)
                onPlaybackProgress = { timestamp, data ->
                    activity?.runOnUiThread {
                        if (isAdded && _binding != null) {
                            updateWaveform(timestamp, data)
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
            Toast.makeText(
                requireContext(),
                "Cannot open recording: file not found or unsupported format",
                Toast.LENGTH_LONG
            ).show()
        } catch (e: Exception) {
            Toast.makeText(
                requireContext(),
                "Error loading recording: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun togglePlayback(filePath: String) {
        if (isPlaying) {
            player?.stop()
            isPlaying = false
            binding.actionText.text = getString(R.string.play_recording)
        } else {
            // Reset waveform state for fresh playback (restarts the warmup phase)
            waveformEntries.clear()
            waveformDataSet = null   // force dataset recreation on next updateWaveform call
            peakAmplitude = 1.0f  // Wide open until warmup locks it in
            warmupPeak = 0f
            warmupDone = false
            lastPeakUpdateTime = 0L
            binding.waveformChart.clear()
            binding.waveformChart.data = null

            // FIX: Always release old player and create a FRESH TaalPlayer for each play.
            // Re-using a released TaalPlayer risks AudioTrack state inconsistency across
            // Android versions. A fresh instance guarantees clean INITIALIZED state.
            try {
                player?.release()
            } catch (_: Exception) {}
            player = null

            setupPlayer(filePath)
            if (player == null) return  // setupPlayer failed, error Toast already shown

            try {
                player?.prepare()
                player?.start()
                isPlaying = true
                binding.actionText.text = getString(R.string.stop_recording)
            } catch (e: Exception) {
                isPlaying = false
                player?.release()
                player = null
                Toast.makeText(
                    requireContext(),
                    "Playback error: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /**
     * V6 — Warmup-based peak detection. No DISPLAY_GAIN.
     *
     * Identical two-phase approach to RecordingFragment V6:
     *
     *   Phase 1 — WARMUP (first WARMUP_MS = 1500ms):
     *     Accumulate the highest absolute sample seen. Y-axis = ±1.0 (stable).
     *
     *   Phase 2 — LOCKED (after warmup):
     *     Set peakAmplitude = warmupPeak × HEADROOM once.
     *     Then: immediate expand upward if signal exceeds scale,
     *           very slow downward drift (×0.999 per callback) otherwise.
     *     This eliminates the pulsing/inflating caused by V5's exponential-blend RMS.
     *
     * Note: TaalPlayer feeds the callback from filterEngine.processBlock() output,
     * so samples are normalized floats at realistic amplitude — no gain needed.
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

        // Adaptive downsampling (V3 formula)
        val displayStep = maxOf(1, (INPUT_SAMPLE_RATE / DISPLAY_SAMPLE_RATE).toInt())
        val budgetStep = maxOf(1, data.size / maxOf(1,
            ((bufferDuration / WINDOW_SECONDS) * TARGET_POINTS_IN_WINDOW).toInt()))
        val step = maxOf(displayStep, budgetStep)

        // Absolute peak of this buffer (max |sample|)
        var bufferPeak = 0f
        for (sample in data) {
            val abs = Math.abs(sample)
            if (abs > bufferPeak) bufferPeak = abs
        }

        // Add downsampled entries (raw samples, no gain)
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
                // Warmup complete — lock in the scale with headroom
                warmupDone = true
                peakAmplitude = (warmupPeak * HEADROOM).coerceIn(MIN_PEAK, 1.0f)
            }
            // During warmup keep axis at ±1.0 (stable, no zooming yet)
        } else {
            // Phase 2: locked scale with very slow downward drift
            val needed = bufferPeak * HEADROOM
            peakAmplitude = if (needed > peakAmplitude) {
                needed.coerceAtMost(1.0f)                          // expand instantly
            } else {
                (peakAmplitude * 0.999f).coerceAtLeast(MIN_PEAK)  // drift imperceptibly down
            }
        }

        // Trim entries that have scrolled out of the visible window.
        // Use subList().clear() instead of repeated removeAt(0) — O(n) once vs O(n²).
        val trimIndex = waveformEntries.indexOfFirst { it.x >= windowStart }
        if (trimIndex > 0) waveformEntries.subList(0, trimIndex).clear()
        if (waveformEntries.size > MAX_VISIBLE_POINTS)
            waveformEntries.subList(0, waveformEntries.size - MAX_VISIBLE_POINTS).clear()

        // Guard: only update axes when the range is valid (min < max).
        // MPAndroidChart's drawValues renderer computes (maxX - minX) * scale for an array
        // allocation; if the range is inverted even briefly it produces a negative size → crash.
        if (windowStart < windowEnd) {
            chart.xAxis.axisMinimum = windowStart
            chart.xAxis.axisMaximum = windowEnd
        }
        if (peakAmplitude > 0f) {
            chart.axisLeft.axisMinimum = -peakAmplitude
            chart.axisLeft.axisMaximum = peakAmplitude
        }

        // Reuse the persistent dataset — updating entries in-place avoids the one-frame
        // "no data" gap that occurred when chart.data was replaced entirely each callback,
        // which was the direct cause of the waveform flickering.
        val snapshot = ArrayList(waveformEntries.toList())
        val ds = waveformDataSet
        if (ds == null || chart.data == null) {
            // First call: create dataset and attach it to the chart once.
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
            // Subsequent calls: swap the entries on the existing dataset without
            // replacing chart.data, so the chart is never momentarily data-less.
            ds.values = snapshot
            chart.data?.notifyDataChanged()
        }
        chart.notifyDataSetChanged()
        chart.invalidate()

        val totalSecs = timestamp.toInt()
        binding.timerText.text = String.format(
            "%02d:%02d:%02d",
            totalSecs / 3600, (totalSecs % 3600) / 60, totalSecs % 60
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        try {
            player?.onPlaybackProgress = null
            player?.onPlaybackComplete = null
            player?.stop()
        } catch (_: Exception) {}
        try { player?.release() } catch (_: Exception) {}
        _binding = null
    }
}
