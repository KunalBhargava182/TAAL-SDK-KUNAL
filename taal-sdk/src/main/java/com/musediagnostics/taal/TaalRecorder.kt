package com.musediagnostics.taal

import android.content.Context
import com.musediagnostics.taal.core.TaalAudioCapture
import com.musediagnostics.taal.core.RecorderState
import com.musediagnostics.taal.dsp.AudioFilterEngine
import java.io.File

class TaalRecorder(private val context: Context) {

    private val audioCapture = TaalAudioCapture(context)
    private val filterEngine = AudioFilterEngine()

    private var rawAudioFilePath: String? = null
    private var recordingTime: Int = 30
    private var playbackEnabled: Boolean = false
    private var preFilter: PreFilter = PreFilter.HEART
    private var preAmplificationDb: Int = 0
    private var currentState: RecorderState = RecorderState.INITIAL

    var onInfoListener: OnInfoListener? = null
    var onLiveStreamListener: OnLiveStreamListener? = null

    init {
        audioCapture.onStateChange = { state ->
            currentState = state
            onInfoListener?.onStateChange(state)
        }

        audioCapture.onAudioData = { data, timestamp ->
            // Apply pre-amp + bandpass preset filter + graphic EQ
            val filtered = filterEngine.processBlock(data)

            onInfoListener?.onProgressUpdate(
                TaalAudioCapture.SAMPLE_RATE,
                filtered.size,
                timestamp,
                filtered
            )

            // Convert to ByteArray for live stream
            val bytes = floatArrayToBytes(filtered)
            onLiveStreamListener?.onNewStream(bytes)
        }
    }

    // Configuration methods (must be called in INITIAL state)
    fun setRawAudioFilePath(path: String) {
        rawAudioFilePath = path
    }

    fun setRecordingTime(seconds: Int) {
        recordingTime = seconds
    }

    fun setPlayback(enabled: Boolean) {
        playbackEnabled = enabled
    }

    fun setPreFilter(filter: PreFilter) {
        preFilter = filter
        filterEngine.setPresetFilter(filter.toDspFilter())
    }

    fun setPreAmplification(db: Int) {
        preAmplificationDb = db.coerceIn(0, 20)
        filterEngine.setPreAmplification(db.toFloat())
    }

    // Control methods
    fun start() {
        val filePath = rawAudioFilePath
            ?: throw IllegalStateException("Raw audio file path not set")

        if (!audioCapture.checkUsbConnection()) {
            throw TaalDisconnectedException()
        }

        val outputFile = File(filePath)
        audioCapture.startRecording(outputFile, recordingTime)
    }

    fun stop() {
        audioCapture.stopRecording()
    }

    fun reset() {
        audioCapture.stopRecording()
        currentState = RecorderState.INITIAL
        rawAudioFilePath = null
        recordingTime = 30
        playbackEnabled = false
        preFilter = PreFilter.HEART
        preAmplificationDb = 0
    }

    fun getState(): RecorderState {
        return currentState
    }

    private fun floatArrayToBytes(floats: FloatArray): ByteArray {
        val bytes = ByteArray(floats.size * 2)
        for (i in floats.indices) {
            val sample = (floats[i] * 32767).toInt().coerceIn(-32768, 32767).toShort()
            bytes[i * 2] = (sample.toInt() and 0xff).toByte()
            bytes[i * 2 + 1] = (sample.toInt() shr 8 and 0xff).toByte()
        }
        return bytes
    }

    // Interfaces
    interface OnInfoListener {
        fun onStateChange(state: RecorderState)
        fun onProgressUpdate(
            sampleRate: Int,
            bufferSize: Int,
            timeStamp: Double,
            data: FloatArray
        )
    }

    interface OnLiveStreamListener {
        fun onNewStream(stream: ByteArray)
    }
}

enum class PreFilter {
    HEART, LUNGS, BOWEL, PREGNANCY, FULL_BODY;

    fun toDspFilter(): AudioFilterEngine.PresetFilter {
        return when (this) {
            HEART -> AudioFilterEngine.PresetFilter.HEART
            LUNGS -> AudioFilterEngine.PresetFilter.LUNGS
            BOWEL -> AudioFilterEngine.PresetFilter.BOWEL
            PREGNANCY -> AudioFilterEngine.PresetFilter.PREGNANCY
            FULL_BODY -> AudioFilterEngine.PresetFilter.FULL_BODY
        }
    }
}

// Exceptions
class TaalDisconnectedException : Exception("TAAL device not connected")
class TaalNotAvailableForUseException : Exception("TAAL device is in use by another application")
