package com.musediagnostics.taal.core

import android.content.Context
import android.hardware.usb.UsbManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

class TaalAudioCapture(private val context: Context) {

    companion object {
        const val SAMPLE_RATE = 44100
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        const val MAX_FREQUENCY_HZ = 2000 // Hard limit
    }

    private var audioRecord: AudioRecord? = null
    private var captureJob: Job? = null
    @Volatile private var isRecording = false
    @Volatile private var isStopped = false

    private val bufferSize = AudioRecord.getMinBufferSize(
        SAMPLE_RATE,
        CHANNEL_CONFIG,
        AUDIO_FORMAT
    ) * 2 // Double for safety

    // Callbacks
    var onAudioData: ((FloatArray, Double) -> Unit)? = null
    var onStateChange: ((RecorderState) -> Unit)? = null

    fun startRecording(outputFile: File, durationSeconds: Int) {
        if (isRecording) return

        isStopped = false

        val record = AudioRecord(
            MediaRecorder.AudioSource.DEFAULT,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            bufferSize
        )

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IllegalStateException("AudioRecord initialization failed - check USB connection")
        }

        audioRecord = record
        record.startRecording()
        isRecording = true
        onStateChange?.invoke(RecorderState.RECORDING)

        captureJob = CoroutineScope(Dispatchers.IO).launch {
            captureAudioToFile(outputFile, durationSeconds)
        }
    }

    private suspend fun captureAudioToFile(outputFile: File, durationSeconds: Int) {
        val buffer = ByteArray(bufferSize)
        val floatBuffer = FloatArray(bufferSize / 2) // 16-bit = 2 bytes per sample
        var totalBytesWritten = 0

        FileOutputStream(outputFile).use { fos ->
            // Write WAV header (placeholder, we'll update after recording)
            writeWavHeader(fos, 0, SAMPLE_RATE, 1, 16)

            val startTime = System.currentTimeMillis()
            val endTime = startTime + (durationSeconds * 1000)

            while (isRecording && System.currentTimeMillis() < endTime) {
                val bytesRead = audioRecord?.read(buffer, 0, buffer.size) ?: 0

                if (bytesRead > 0) {
                    // Convert to float for callbacks
                    convertBytesToFloat(buffer, floatBuffer, bytesRead)

                    // Stream to listeners
                    val timestamp = (System.currentTimeMillis() - startTime) / 1000.0
                    onAudioData?.invoke(floatBuffer.copyOf(bytesRead / 2), timestamp)

                    // Write raw data to file
                    fos.write(buffer, 0, bytesRead)
                    totalBytesWritten += bytesRead
                }
            }
        }

        // Update WAV header with actual size using RandomAccessFile
        try {
            RandomAccessFile(outputFile, "rw").use { raf ->
                updateWavHeader(raf, totalBytesWritten)
            }
        } catch (_: Exception) {
            // File may already be closed if Activity was destroyed
        }

        // Release audio resources from the IO thread
        releaseAudioRecord()

        // Notify state change on main thread
        kotlinx.coroutines.withContext(Dispatchers.Main) {
            if (!isStopped) {
                isStopped = true
                onStateChange?.invoke(RecorderState.STOPPED)
            }
        }
    }

    private fun releaseAudioRecord() {
        try {
            audioRecord?.stop()
        } catch (_: Exception) {
            // Already stopped
        }
        try {
            audioRecord?.release()
        } catch (_: Exception) {
            // Already released
        }
        audioRecord = null
    }

    fun stopRecording() {
        if (!isRecording && isStopped) return
        isRecording = false
        captureJob?.cancel()
        releaseAudioRecord()
        if (!isStopped) {
            isStopped = true
            onStateChange?.invoke(RecorderState.STOPPED)
        }
    }

    private fun convertBytesToFloat(bytes: ByteArray, floats: FloatArray, bytesRead: Int) {
        val buffer = ByteBuffer.wrap(bytes, 0, bytesRead).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until bytesRead / 2) {
            floats[i] = buffer.short.toFloat() / 32768f // Normalize to [-1, 1]
        }
    }

    private fun writeWavHeader(
        fos: FileOutputStream,
        dataSize: Int,
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int
    ) {
        val header = ByteArray(44)
        val byteRate = sampleRate * channels * bitsPerSample / 8

        // RIFF header
        header[0] = 'R'.code.toByte()
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()

        // File size - 8
        val fileSize = dataSize + 36
        header[4] = (fileSize and 0xff).toByte()
        header[5] = (fileSize shr 8 and 0xff).toByte()
        header[6] = (fileSize shr 16 and 0xff).toByte()
        header[7] = (fileSize shr 24 and 0xff).toByte()

        // WAVE header
        header[8] = 'W'.code.toByte()
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()

        // fmt subchunk
        header[12] = 'f'.code.toByte()
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()

        // Subchunk1 size (16 for PCM)
        header[16] = 16
        header[17] = 0
        header[18] = 0
        header[19] = 0

        // Audio format (1 = PCM)
        header[20] = 1
        header[21] = 0

        // Number of channels
        header[22] = channels.toByte()
        header[23] = 0

        // Sample rate
        header[24] = (sampleRate and 0xff).toByte()
        header[25] = (sampleRate shr 8 and 0xff).toByte()
        header[26] = (sampleRate shr 16 and 0xff).toByte()
        header[27] = (sampleRate shr 24 and 0xff).toByte()

        // Byte rate
        header[28] = (byteRate and 0xff).toByte()
        header[29] = (byteRate shr 8 and 0xff).toByte()
        header[30] = (byteRate shr 16 and 0xff).toByte()
        header[31] = (byteRate shr 24 and 0xff).toByte()

        // Block align
        val blockAlign = channels * bitsPerSample / 8
        header[32] = blockAlign.toByte()
        header[33] = 0

        // Bits per sample
        header[34] = bitsPerSample.toByte()
        header[35] = 0

        // data subchunk
        header[36] = 'd'.code.toByte()
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()

        // Data size
        header[40] = (dataSize and 0xff).toByte()
        header[41] = (dataSize shr 8 and 0xff).toByte()
        header[42] = (dataSize shr 16 and 0xff).toByte()
        header[43] = (dataSize shr 24 and 0xff).toByte()

        fos.write(header)
    }

    private fun updateWavHeader(raf: RandomAccessFile, dataSize: Int) {
        val fileSize = dataSize + 36
        // Update RIFF chunk size at byte 4
        raf.seek(4)
        raf.write(fileSize and 0xff)
        raf.write(fileSize shr 8 and 0xff)
        raf.write(fileSize shr 16 and 0xff)
        raf.write(fileSize shr 24 and 0xff)
        // Update data subchunk size at byte 40
        raf.seek(40)
        raf.write(dataSize and 0xff)
        raf.write(dataSize shr 8 and 0xff)
        raf.write(dataSize shr 16 and 0xff)
        raf.write(dataSize shr 24 and 0xff)
    }

    fun checkUsbConnection(): Boolean {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
            ?: return false
        val deviceList = usbManager.deviceList

        // Check for USB audio class devices at both device and interface level.
        // Most USB audio devices (including composite devices like TAAL) report
        // class 0 at the device level and USB_CLASS_AUDIO only on their interfaces.
        return deviceList.values.any { device ->
            if (device.deviceClass == android.hardware.usb.UsbConstants.USB_CLASS_AUDIO) {
                return@any true
            }
            for (i in 0 until device.interfaceCount) {
                if (device.getInterface(i).interfaceClass == android.hardware.usb.UsbConstants.USB_CLASS_AUDIO) {
                    return@any true
                }
            }
            false
        }
    }
}

enum class RecorderState {
    INITIAL, RECORDING, STOPPED
}
