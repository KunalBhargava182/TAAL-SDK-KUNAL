package com.musediagnostics.taal.uikit.recording

import org.junit.Assert.*
import org.junit.Test

class RecordingUiStateTest {

    @Test
    fun test_allValues_exist() {
        val values = RecordingUiState.values()
        assertTrue(values.contains(RecordingUiState.IDLE))
        assertTrue(values.contains(RecordingUiState.RECORDING))
        assertTrue(values.contains(RecordingUiState.STOPPED))
    }

    @Test
    fun test_valueCount() {
        assertEquals(3, RecordingUiState.values().size)
    }

    @Test
    fun test_valueOf_IDLE() {
        assertEquals(RecordingUiState.IDLE, RecordingUiState.valueOf("IDLE"))
    }

    @Test
    fun test_valueOf_RECORDING() {
        assertEquals(RecordingUiState.RECORDING, RecordingUiState.valueOf("RECORDING"))
    }

    @Test
    fun test_valueOf_STOPPED() {
        assertEquals(RecordingUiState.STOPPED, RecordingUiState.valueOf("STOPPED"))
    }
}
