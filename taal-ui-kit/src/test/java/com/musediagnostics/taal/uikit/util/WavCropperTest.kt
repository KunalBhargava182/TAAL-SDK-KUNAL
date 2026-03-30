package com.musediagnostics.taal.uikit.util

import com.musediagnostics.taal.uikit.TestWavHelper
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WavCropperTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    // ── getDurationSeconds ────────────────────────────────────────────────────

    @Test
    fun test_getDurationSeconds_knownDuration() {
        val file = tempFolder.newFile("one_second.wav")
        TestWavHelper.createWavFile(file, sampleRate = 44100, durationSeconds = 1.0f)
        val duration = WavCropper.getDurationSeconds(file.absolutePath)
        assertEquals(1.0f, duration, 0.01f)
    }

    @Test
    fun test_getDurationSeconds_emptyFile() {
        val file = tempFolder.newFile("header_only.wav")
        TestWavHelper.createHeaderOnlyWavFile(file)
        val duration = WavCropper.getDurationSeconds(file.absolutePath)
        assertEquals(0f, duration, 0.001f)
    }

    @Test
    fun test_getDurationSeconds_nonExistentFile() {
        val duration = WavCropper.getDurationSeconds("/nonexistent/path/file.wav")
        assertEquals(0f, duration, 0.001f)
    }

    // ── getWaveformData ───────────────────────────────────────────────────────

    @Test
    fun test_getWaveformData_validFile_returnsData() {
        val file = tempFolder.newFile("waveform.wav")
        TestWavHelper.createWavFile(file, durationSeconds = 1.0f)
        val data = WavCropper.getWaveformData(file.absolutePath)
        assertTrue("Expected non-empty waveform data", data.isNotEmpty())
    }

    @Test
    fun test_getWaveformData_validFile_valuesInRange() {
        val file = tempFolder.newFile("waveform_range.wav")
        TestWavHelper.createWavFile(file, durationSeconds = 0.5f)
        val data = WavCropper.getWaveformData(file.absolutePath)
        for (v in data) {
            assertTrue("Value $v out of range [-1, 1]", v >= -1f && v <= 1f)
        }
    }

    @Test
    fun test_getWaveformData_maxPoints_respected() {
        val file = tempFolder.newFile("waveform_points.wav")
        TestWavHelper.createWavFile(file, durationSeconds = 5.0f)
        val maxPoints = 500
        val data = WavCropper.getWaveformData(file.absolutePath, maxPoints)
        assertTrue("Result size ${data.size} should be <= maxPoints $maxPoints", data.size <= maxPoints)
    }

    @Test
    fun test_getWaveformData_nonExistentFile_returnsEmptyArray() {
        val data = WavCropper.getWaveformData("/nonexistent/file.wav")
        assertEquals(0, data.size)
    }

    @Test
    fun test_getWaveformData_emptyDataWav_returnsEmptyArray() {
        val file = tempFolder.newFile("header_only2.wav")
        TestWavHelper.createHeaderOnlyWavFile(file)
        val data = WavCropper.getWaveformData(file.absolutePath)
        assertEquals(0, data.size)
    }

    // ── cropWav ───────────────────────────────────────────────────────────────

    @Test
    fun test_cropWav_validCrop_returnsTrue() {
        val input = tempFolder.newFile("input_2s.wav")
        val output = tempFolder.newFile("output_crop.wav")
        TestWavHelper.createWavFile(input, durationSeconds = 2.0f)
        val result = WavCropper.cropWav(input.absolutePath, output.absolutePath, 0.5f, 1.5f)
        assertTrue("Crop should return true", result)
    }

    @Test
    fun test_cropWav_outputFileCreated() {
        val input = tempFolder.newFile("input_2s_b.wav")
        val output = tempFolder.newFile("output_b.wav")
        TestWavHelper.createWavFile(input, durationSeconds = 2.0f)
        WavCropper.cropWav(input.absolutePath, output.absolutePath, 0.5f, 1.5f)
        assertTrue("Output file should exist after crop", output.exists())
        assertTrue("Output file should not be empty", output.length() > 44)
    }

    @Test
    fun test_cropWav_outputDuration_correct() {
        val input = tempFolder.newFile("input_2s_c.wav")
        val output = tempFolder.newFile("output_c.wav")
        TestWavHelper.createWavFile(input, durationSeconds = 2.0f)
        WavCropper.cropWav(input.absolutePath, output.absolutePath, 0.5f, 1.5f)
        val duration = WavCropper.getDurationSeconds(output.absolutePath)
        assertEquals(1.0f, duration, 0.02f)
    }

    @Test
    fun test_cropWav_nonExistentInput_returnsFalse() {
        val output = tempFolder.newFile("output_nonexist.wav")
        val result = WavCropper.cropWav("/nonexistent/input.wav", output.absolutePath, 0f, 1f)
        assertFalse("Crop of non-existent file should return false", result)
    }

    @Test
    fun test_cropWav_invalidRange_returnsFalse() {
        val input = tempFolder.newFile("input_inv.wav")
        val output = tempFolder.newFile("output_inv.wav")
        TestWavHelper.createWavFile(input, durationSeconds = 2.0f)
        // startSeconds > endSeconds
        val result = WavCropper.cropWav(input.absolutePath, output.absolutePath, 1.5f, 0.5f)
        assertFalse("Inverted range should return false", result)
    }

    @Test
    fun test_cropWav_zeroRange_returnsFalse() {
        val input = tempFolder.newFile("input_zero.wav")
        val output = tempFolder.newFile("output_zero.wav")
        TestWavHelper.createWavFile(input, durationSeconds = 2.0f)
        // startSeconds == endSeconds → 0 samples
        val result = WavCropper.cropWav(input.absolutePath, output.absolutePath, 1.0f, 1.0f)
        assertFalse("Zero-range crop should return false", result)
    }
}
