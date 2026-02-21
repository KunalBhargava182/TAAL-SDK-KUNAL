package com.musediagnostics.taal.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.musediagnostics.taal.R
import com.musediagnostics.taal.TaalPlayer
import com.musediagnostics.taal.dsp.AudioFilterEngine

class PlayerActivity : AppCompatActivity() {

    private lateinit var player: TaalPlayer
    private lateinit var waveformChart: LineChart
    private lateinit var playButton: Button
    private lateinit var timerText: TextView

    // Graphic EQ sliders
    private lateinit var eq20HzSlider: SeekBar
    private lateinit var eq50HzSlider: SeekBar
    private lateinit var eq100HzSlider: SeekBar
    private lateinit var eq200HzSlider: SeekBar
    private lateinit var eq600HzSlider: SeekBar

    private val waveformEntries = mutableListOf<Entry>()
    private var peakAmplitude = 0.01f // Track peak amplitude for auto-scaling

    companion object {
        private const val EXTRA_FILE_PATH = "file_path"

        fun getIntent(context: Context, filePath: String): Intent {
            return Intent(context, PlayerActivity::class.java).apply {
                putExtra(EXTRA_FILE_PATH, filePath)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        initializeViews()
        setupPlayer()
        setupGraphicEQ()
        setupControls()
    }

    private fun initializeViews() {
        waveformChart = findViewById(R.id.waveform_chart)
        playButton = findViewById(R.id.play_button)
        timerText = findViewById(R.id.timer_text)

        // Graphic EQ sliders
        eq20HzSlider = findViewById(R.id.eq_20hz_slider)
        eq50HzSlider = findViewById(R.id.eq_50hz_slider)
        eq100HzSlider = findViewById(R.id.eq_100hz_slider)
        eq200HzSlider = findViewById(R.id.eq_200hz_slider)
        eq600HzSlider = findViewById(R.id.eq_600hz_slider)
    }

    private fun setupPlayer() {
        player = TaalPlayer(this)

        val filePath = intent.getStringExtra(EXTRA_FILE_PATH)!!
        player.setDataSource(filePath)
        player.prepare()

        player.onPlaybackProgress = { timestamp, data ->
            runOnUiThread {
                updateWaveform(timestamp, data)
                updateTimer(timestamp)
            }
        }

        player.onPlaybackComplete = {
            playButton.text = "Play"
        }

        setupWaveformChart()
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

    private fun setupGraphicEQ() {
        val seekbars = listOf(
            eq20HzSlider, eq50HzSlider, eq100HzSlider,
            eq200HzSlider, eq600HzSlider
        )

        seekbars.forEach { seekbar ->
            seekbar.max = 240 // -12dB to +12dB in 0.1dB steps
            seekbar.progress = 120 // 0dB center

            seekbar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    updateEQState()
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
    }

    private fun updateEQState() {
        val eqState = AudioFilterEngine.GraphicEQState(
            band20Hz = (eq20HzSlider.progress - 120) / 10f,
            band50Hz = (eq50HzSlider.progress - 120) / 10f,
            band100Hz = (eq100HzSlider.progress - 120) / 10f,
            band200Hz = (eq200HzSlider.progress - 120) / 10f,
            band600Hz = (eq600HzSlider.progress - 120) / 10f
        )

        player.setGraphicEQ(eqState)
    }

    private fun setupControls() {
        playButton.setOnClickListener {
            if (playButton.text == "Play") {
                player.start()
                playButton.text = "Pause"
            } else {
                player.stop()
                playButton.text = "Play"
            }
        }
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

        // Fix the X axis to always show a 10-second window
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
        player.release()
    }
}
