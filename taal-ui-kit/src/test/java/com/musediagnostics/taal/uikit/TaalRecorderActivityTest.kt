package com.musediagnostics.taal.uikit

import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TaalRecorderActivityTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
    }

    // ── getIntent ─────────────────────────────────────────────────────────────

    @Test
    fun test_getIntent_returnsNonNullIntent() {
        val intent = TaalRecorderActivity.getIntent(context)
        assertNotNull(intent)
    }

    @Test
    fun test_getIntent_containsPreFilter() {
        val intent = TaalRecorderActivity.getIntent(context)
        assertEquals("HEART", intent.getStringExtra(TaalRecorderActivity.EXTRA_PRE_FILTER))
    }

    @Test
    fun test_getIntent_containsPreAmplification() {
        val intent = TaalRecorderActivity.getIntent(context)
        assertEquals(5, intent.getIntExtra(TaalRecorderActivity.EXTRA_PRE_AMPLIFICATION, -1))
    }

    @Test
    fun test_getIntent_containsRecordingTimeSeconds() {
        val intent = TaalRecorderActivity.getIntent(context)
        assertEquals(300, intent.getIntExtra(TaalRecorderActivity.EXTRA_RECORDING_TIME_SECONDS, -1))
    }

    @Test
    fun test_getIntent_customValues() {
        val intent = TaalRecorderActivity.getIntent(
            context,
            preFilter = "LUNGS",
            preAmplification = 10,
            recordingTimeSeconds = 600
        )
        assertEquals("LUNGS", intent.getStringExtra(TaalRecorderActivity.EXTRA_PRE_FILTER))
        assertEquals(10, intent.getIntExtra(TaalRecorderActivity.EXTRA_PRE_AMPLIFICATION, -1))
        assertEquals(600, intent.getIntExtra(TaalRecorderActivity.EXTRA_RECORDING_TIME_SECONDS, -1))
    }

    @Test
    fun test_getIntent_defaultValues() {
        val intent = TaalRecorderActivity.getIntent(context)
        assertEquals("HEART", intent.getStringExtra(TaalRecorderActivity.EXTRA_PRE_FILTER))
        assertEquals(5, intent.getIntExtra(TaalRecorderActivity.EXTRA_PRE_AMPLIFICATION, -1))
        assertEquals(300, intent.getIntExtra(TaalRecorderActivity.EXTRA_RECORDING_TIME_SECONDS, -1))
    }

    // ── Constants ─────────────────────────────────────────────────────────────

    @Test
    fun test_EXTRA_PRE_FILTER_value() {
        assertEquals("preFilter", TaalRecorderActivity.EXTRA_PRE_FILTER)
    }

    @Test
    fun test_EXTRA_PRE_AMPLIFICATION_value() {
        assertEquals("preAmplification", TaalRecorderActivity.EXTRA_PRE_AMPLIFICATION)
    }

    @Test
    fun test_EXTRA_RECORDING_TIME_SECONDS_value() {
        assertEquals("recordingTimeSeconds", TaalRecorderActivity.EXTRA_RECORDING_TIME_SECONDS)
    }

    @Test
    fun test_RESULT_FILE_PATH_value() {
        assertEquals("filePath", TaalRecorderActivity.RESULT_FILE_PATH)
    }
}
