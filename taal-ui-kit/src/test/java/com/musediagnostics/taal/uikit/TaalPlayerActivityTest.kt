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
class TaalPlayerActivityTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
    }

    @Test
    fun test_getIntent_returnsNonNullIntent() {
        val intent = TaalPlayerActivity.getIntent(context, "/sdcard/test.wav")
        assertNotNull(intent)
    }

    @Test
    fun test_getIntent_containsFilePath() {
        val intent = TaalPlayerActivity.getIntent(context, "/sdcard/test.wav")
        assertNotNull(intent.getStringExtra(TaalPlayerActivity.EXTRA_FILE_PATH))
    }

    @Test
    fun test_getIntent_withValidPath() {
        val intent = TaalPlayerActivity.getIntent(context, "/sdcard/test.wav")
        assertEquals("/sdcard/test.wav", intent.getStringExtra(TaalPlayerActivity.EXTRA_FILE_PATH))
    }

    @Test
    fun test_getIntent_withEmptyString() {
        // BUG: should validate and throw, but currently accepts empty string
        val intent = TaalPlayerActivity.getIntent(context, "")
        assertEquals("", intent.getStringExtra(TaalPlayerActivity.EXTRA_FILE_PATH))
    }

    @Test
    fun test_EXTRA_FILE_PATH_value() {
        assertEquals("filePath", TaalPlayerActivity.EXTRA_FILE_PATH)
    }
}
