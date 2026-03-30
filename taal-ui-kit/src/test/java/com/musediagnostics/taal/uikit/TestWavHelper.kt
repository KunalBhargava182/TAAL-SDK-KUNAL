package com.musediagnostics.taal.uikit

import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sin

/**
 * Helper to create valid WAV files for unit testing.
 * Creates 16-bit PCM mono WAV files with proper headers.
 */
object TestWavHelper {

    fun createWavFile(
        file: File,
        sampleRate: Int = 44100,
        durationSeconds: Float = 1.0f,
        frequency: Float = 440f
    ) {
        val totalSamples = (sampleRate * durationSeconds).toInt()
        val samples = ShortArray(totalSamples) { i ->
            (Short.MAX_VALUE * sin(2.0 * Math.PI * frequency * i / sampleRate)).toInt().toShort()
        }
        writeWavFile(file, sampleRate, samples)
    }

    fun createSilentWavFile(
        file: File,
        sampleRate: Int = 44100,
        durationSeconds: Float = 1.0f
    ) {
        val totalSamples = (sampleRate * durationSeconds).toInt()
        writeWavFile(file, sampleRate, ShortArray(totalSamples))
    }

    fun createHeaderOnlyWavFile(file: File, sampleRate: Int = 44100) {
        writeWavFile(file, sampleRate, ShortArray(0))
    }

    fun createWavFileWithSamples(file: File, sampleRate: Int = 44100, samples: ShortArray) {
        writeWavFile(file, sampleRate, samples)
    }

    private fun writeWavFile(file: File, sampleRate: Int, samples: ShortArray) {
        val dataSize = samples.size * 2
        val totalSize = dataSize + 36
        val byteRate = sampleRate * 2
        val blockAlign = 2

        FileOutputStream(file).use { fos ->
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray())
            header.putInt(totalSize)
            header.put("WAVE".toByteArray())
            header.put("fmt ".toByteArray())
            header.putInt(16)
            header.putShort(1)
            header.putShort(1)
            header.putInt(sampleRate)
            header.putInt(byteRate)
            header.putShort(blockAlign.toShort())
            header.putShort(16)
            header.put("data".toByteArray())
            header.putInt(dataSize)
            fos.write(header.array())

            val dataBuffer = ByteBuffer.allocate(dataSize).order(ByteOrder.LITTLE_ENDIAN)
            for (sample in samples) dataBuffer.putShort(sample)
            fos.write(dataBuffer.array())
        }
    }
}
