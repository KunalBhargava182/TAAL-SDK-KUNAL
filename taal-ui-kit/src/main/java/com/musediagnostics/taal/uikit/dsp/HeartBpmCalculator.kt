package com.musediagnostics.taal.uikit.dsp

import kotlin.math.*

/**
 * Heart sound BPM calculator using autocorrelation on PCG data.
 * Processes audio buffers to estimate heart rate from S1/S2 heart sounds.
 *
 * All heavy computation is designed to run on a background thread.
 * The caller should invoke addSamples on the UI thread (fast buffer copy only)
 * and computeBpm on a background thread.
 */
class HeartBpmCalculator(private val sampleRate: Int = 44100) {

    private val analysisRate = 1000
    private val downsampleFactor = sampleRate / analysisRate

    private val bufferDurationSec = 4.0
    private val bufferSize = (analysisRate * bufferDurationSec).toInt()
    private val audioBuffer = FloatArray(bufferSize)
    private var writePos = 0
    private var samplesCollected = 0

    private var dsAccum = 0f
    private var dsCount = 0

    @Volatile
    var lastBpm = 0
        private set

    @Volatile
    private var lastUpdateTime = 0L

    @Volatile
    private var computing = false

    /**
     * Feed new audio data from the UI thread. This is very fast — just accumulates samples.
     * Returns true if enough data has been collected and it's time to compute BPM.
     */
    fun addSamples(data: FloatArray): Boolean {
        for (sample in data) {
            dsAccum += abs(sample)
            dsCount++
            if (dsCount >= downsampleFactor) {
                audioBuffer[writePos] = dsAccum / dsCount
                writePos = (writePos + 1) % bufferSize
                samplesCollected++
                dsAccum = 0f
                dsCount = 0
            }
        }

        val now = System.currentTimeMillis()
        return !computing &&
               now - lastUpdateTime >= 5000L &&
               samplesCollected >= analysisRate * 3
    }

    /**
     * Run the actual BPM computation. MUST be called on a background thread.
     * Returns the computed BPM or 0 if invalid.
     */
    fun computeBpm(): Int {
        if (computing) return lastBpm
        computing = true
        lastUpdateTime = System.currentTimeMillis()

        try {
            val length = minOf(samplesCollected, bufferSize)
            val analysis = FloatArray(length)
            val startPos = (writePos - length + bufferSize) % bufferSize
            for (i in 0 until length) {
                analysis[i] = audioBuffer[(startPos + i) % bufferSize]
            }

            val bpm = calculateBpm(analysis)
            if (bpm > 0) {
                lastBpm = bpm
            }
            return lastBpm
        } catch (_: Exception) {
            return lastBpm
        } finally {
            computing = false
        }
    }

    private fun calculateBpm(envelope: FloatArray): Int {
        if (envelope.size < analysisRate * 2) return 0

        val maxVal = envelope.max()
        if (maxVal < 0.0001f) return 0
        val normalized = FloatArray(envelope.size) { envelope[it] / maxVal }

        val windowSize = 30
        val smoothed = FloatArray(normalized.size)
        var runningSum = 0f
        for (i in 0 until minOf(windowSize, normalized.size)) {
            runningSum += normalized[i]
        }
        for (i in normalized.indices) {
            val wEnd = i + windowSize
            if (wEnd < normalized.size) {
                runningSum += normalized[wEnd]
            }
            if (i > 0 && i - 1 < normalized.size) {
                runningSum -= normalized[i - 1]
            }
            val cnt = minOf(windowSize, normalized.size - i)
            smoothed[i] = if (cnt > 0) runningSum / cnt else 0f
        }

        val minLag = (0.4 * analysisRate).toInt()
        val maxLag = (1.5 * analysisRate).toInt()
        val n = smoothed.size

        if (maxLag >= n) return 0

        var maxCorr = -1f
        var bestLag = 0

        for (lag in minLag..minOf(maxLag, n - 1)) {
            var sum = 0f
            val limit = n - lag
            for (t in 0 until limit) {
                sum += smoothed[t] * smoothed[t + lag]
            }
            val corr = sum / limit
            if (corr > maxCorr) {
                maxCorr = corr
                bestLag = lag
            }
        }

        var autoZero = 0f
        for (t in smoothed.indices) {
            autoZero += smoothed[t] * smoothed[t]
        }
        autoZero /= n
        val normalizedCorr = if (autoZero > 0) maxCorr / autoZero else 0f

        if (normalizedCorr < 0.3f) return 0
        if (bestLag == 0) return 0

        val period = bestLag.toFloat() / analysisRate
        val bpm = (60f / period).roundToInt()

        return if (bpm in 40..200) bpm else 0
    }

    fun reset() {
        audioBuffer.fill(0f)
        writePos = 0
        samplesCollected = 0
        lastBpm = 0
        lastUpdateTime = 0L
        dsAccum = 0f
        dsCount = 0
        computing = false
    }
}
