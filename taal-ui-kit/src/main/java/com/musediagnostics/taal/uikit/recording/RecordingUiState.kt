package com.musediagnostics.taal.uikit.recording

enum class RecordingUiState {
    IDLE,       // Pre-recording
    RECORDING,  // Actively recording
    STOPPED     // Recording finished, showing save options
}
