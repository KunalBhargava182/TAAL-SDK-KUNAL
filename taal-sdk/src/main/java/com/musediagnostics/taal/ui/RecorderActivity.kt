package com.musediagnostics.taal.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.google.android.material.chip.ChipGroup
import com.musediagnostics.taal.PreFilter
import com.musediagnostics.taal.R
import com.musediagnostics.taal.TaalRecorder
import com.musediagnostics.taal.core.RecorderState

class RecorderActivity : AppCompatActivity() {

    private lateinit var recorder: TaalRecorder
    private lateinit var waveformChart: LineChart
    private lateinit var filterChipGroup: ChipGroup
    private lateinit var preAmpSeekBar: SeekBar
    private lateinit var preAmpValue: TextView
    private lateinit var recordButton: Button
    private lateinit var timerText: TextView

    private val waveformEntries = mutableListOf<Entry>()
    private var recordingStartTime = 0L
    private var peakAmplitude = 0.01f // Track peak amplitude for auto-scaling

    companion object {
        private const val EXTRA_RAW_FILE_PATH = "raw_file_path"
        private const val EXTRA_RECORDING_TIME = "recording_time"
        private const val EXTRA_PLAYBACK = "playback"
        private const val EXTRA_PRE_AMP = "pre_amp"
        private const val EXTRA_PRE_FILTER = "pre_filter"

        fun getIntent(
            context: Context,
            rawAudioFilePath: String,
            playback: Boolean = true,
            recordingTime: Int = 30,
            preAmplification: Int = 5,
            preFilter: PreFilter = PreFilter.HEART
        ): Intent {
            return Intent(context, RecorderActivity::class.java).apply {
                putExtra(EXTRA_RAW_FILE_PATH, rawAudioFilePath)
                putExtra(EXTRA_RECORDING_TIME, recordingTime)
                putExtra(EXTRA_PLAYBACK, playback)
                putExtra(EXTRA_PRE_AMP, preAmplification)
                putExtra(EXTRA_PRE_FILTER, preFilter.name)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_recorder)

        initializeViews()
        setupRecorder()
        setupWaveformChart()
        setupControls()
    }

    private fun initializeViews() {
        waveformChart = findViewById(R.id.waveform_chart)
        filterChipGroup = findViewById(R.id.filter_chip_group)
        preAmpSeekBar = findViewById(R.id.pre_amp_seekbar)
        preAmpValue = findViewById(R.id.pre_amp_value)
        recordButton = findViewById(R.id.record_button)
        timerText = findViewById(R.id.timer_text)
    }

    private fun setupRecorder() {
        recorder = TaalRecorder(this)

        val rawFilePath = intent.getStringExtra(EXTRA_RAW_FILE_PATH)!!
        val recordingTime = intent.getIntExtra(EXTRA_RECORDING_TIME, 30)
        val playback = intent.getBooleanExtra(EXTRA_PLAYBACK, true)
        val preAmp = intent.getIntExtra(EXTRA_PRE_AMP, 5)
        val preFilterName = intent.getStringExtra(EXTRA_PRE_FILTER) ?: "HEART"
        val preFilter = PreFilter.valueOf(preFilterName)

        recorder.setRawAudioFilePath(rawFilePath)
        recorder.setRecordingTime(recordingTime)
        recorder.setPlayback(playback)
        recorder.setPreAmplification(preAmp)
        recorder.setPreFilter(preFilter)

        recorder.onInfoListener = object : TaalRecorder.OnInfoListener {
            override fun onStateChange(state: RecorderState) {
                runOnUiThread {
                    when (state) {
                        RecorderState.RECORDING -> {
                            recordButton.text = "Stop"
                            recordingStartTime = System.currentTimeMillis()
                        }
                        RecorderState.STOPPED -> {
                            recordButton.text = "Start Recording"
                            Toast.makeText(
                                this@RecorderActivity,
                                "Recording saved",
                                Toast.LENGTH_SHORT
                            ).show()
                            setResult(Activity.RESULT_OK)
                            finish()
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
                runOnUiThread {
                    updateWaveform(timeStamp, data)
                    updateTimer(timeStamp)
                }
            }
        }
    }

    private fun setupWaveformChart() {
        waveformChart.apply {
            description.isEnabled = false
            setTouchEnabled(false)
            isDragEnabled = false
            setScaleEnabled(false)
            setPinchZoom(false)
            setDrawGridBackground(true)

            axisLeft.apply {
                axisMinimum = -1f
                axisMaximum = 1f
                setDrawGridLines(true)
                gridColor = android.graphics.Color.LTGRAY
            }

            axisRight.isEnabled = false

            xAxis.apply {
                setDrawGridLines(true)
                gridColor = android.graphics.Color.LTGRAY
                position = com.github.mikephil.charting.components.XAxis.XAxisPosition.BOTTOM
            }

            legend.isEnabled = false
        }
    }

    private fun setupControls() {
        recordButton.setOnClickListener {
            when (recorder.getState()) {
                RecorderState.INITIAL -> {
                    try {
                        recorder.start()
                    } catch (e: Exception) {
                        Toast.makeText(this, e.message, Toast.LENGTH_SHORT).show()
                    }
                }
                RecorderState.RECORDING -> {
                    recorder.stop()
                }
                else -> {}
            }
        }

        // Filter selection
        filterChipGroup.setOnCheckedChangeListener { _, checkedId ->
            val filter = when (checkedId) {
                R.id.chip_heart -> PreFilter.HEART
                R.id.chip_lungs -> PreFilter.LUNGS
                R.id.chip_bowel -> PreFilter.BOWEL
                R.id.chip_pregnancy -> PreFilter.PREGNANCY
                R.id.chip_full_body -> PreFilter.FULL_BODY
                else -> PreFilter.HEART
            }
            recorder.setPreFilter(filter)
        }

        // Pre-amplification
        preAmpSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                preAmpValue.text = progress.toString()
                recorder.setPreAmplification(progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun updateWaveform(timestamp: Double, data: FloatArray) {
        // Downsample for visualization
        val downsampleFactor = maxOf(1, data.size / 500)

        for (i in data.indices step downsampleFactor) {
            val x = timestamp.toFloat() + (i.toFloat() / 44100f)
            waveformEntries.add(Entry(x, data[i]))
        }

        // Track peak amplitude with slow decay for auto-scaling
        var bufferMax = 0f
        for (sample in data) {
            val abs = kotlin.math.abs(sample)
            if (abs > bufferMax) bufferMax = abs
        }
        if (bufferMax > peakAmplitude) {
            peakAmplitude = bufferMax
        } else {
            // Slow decay so the scale doesn't jump around
            peakAmplitude *= 0.999f
            if (peakAmplitude < 0.01f) peakAmplitude = 0.01f
        }

        // Scale Y axis so RMS peaks reach ~60% of graph height
        val yRange = peakAmplitude / 0.6f

        // Keep only last 10 seconds visible
        val windowStart = timestamp.toFloat() - 10f
        while (waveformEntries.isNotEmpty() && waveformEntries.first().x < windowStart) {
            waveformEntries.removeAt(0)
        }

        // Fix the X axis to always show a 10-second window (prevents compression)
        waveformChart.xAxis.axisMinimum = windowStart
        waveformChart.xAxis.axisMaximum = timestamp.toFloat()

        // Auto-scale Y axis based on peak amplitude
        waveformChart.axisLeft.axisMinimum = -yRange
        waveformChart.axisLeft.axisMaximum = yRange

        val dataSet = LineDataSet(waveformEntries, "Audio").apply {
            color = android.graphics.Color.parseColor("#6200EE")
            setDrawCircles(false)
            setDrawValues(false)
            lineWidth = 1.5f
        }

        waveformChart.data = LineData(dataSet)
        waveformChart.notifyDataSetChanged()
        waveformChart.invalidate()
    }

    private fun updateTimer(timestamp: Double) {
        val minutes = (timestamp / 60).toInt()
        val seconds = (timestamp % 60).toInt()
        timerText.text = String.format("%02d:%02d", minutes, seconds)
    }

    override fun onDestroy() {
        super.onDestroy()
        recorder.reset()
    }
}
