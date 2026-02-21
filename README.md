# TAAL SDK - Digital Stethoscope Android Library

Clean Android SDK implementation for MUSE Diagnostics' TAAL digital stethoscope, built from scratch to replace decompiled legacy code.

## Overview

The TAAL SDK provides a comprehensive solution for capturing, processing, and playing back audio from USB-connected digital stethoscopes on Android devices.

## Features

### Core Functionality
- **USB Audio Capture**: Direct audio capture from TAAL USB stethoscope devices
- **Real-time DSP Processing**: Advanced digital signal processing with preset filters
- **WAV File Recording**: High-quality 44.1kHz mono PCM audio recording
- **Audio Playback**: Built-in player with real-time filtering

### Audio Filters
- **Preset Filters** for clinical applications:
  - Heart (20-250 Hz)
  - Lungs (100-600 Hz)
  - Bowel (100-600 Hz)
  - Pregnancy (20-250 Hz)
  - Full Body (20-600 Hz)

- **5-Band Graphic EQ** (playback only):
  - 20Hz, 50Hz, 100Hz, 200Hz, 600Hz
  - ±12dB range per band

- **Pre-Amplification**: 0-10dB gain control

### UI Components
- **RecorderActivity**: Full-featured recording interface with:
  - Real-time waveform visualization
  - Filter selection chips
  - Pre-amplification control
  - Recording timer

- **PlayerActivity**: Audio playback interface with:
  - Waveform visualization
  - 5-band graphic EQ
  - Playback controls

## Project Structure

```
taal-sdk-project/
├── taal-sdk/                     # Main SDK library module
│   └── src/main/java/com/musediagnostics/taal/
│       ├── core/                 # Core audio capture
│       │   ├── TaalAudioCapture.kt
│       │   └── RecorderState.kt
│       ├── dsp/                  # DSP filter engine
│       │   └── AudioFilterEngine.kt
│       ├── ui/                   # UI components
│       │   ├── RecorderActivity.kt
│       │   └── PlayerActivity.kt
│       ├── utils/                # Utility classes
│       │   ├── SurrUtils.kt
│       │   └── TaalConnectionBroadcastReceiver.kt
│       ├── TaalRecorder.kt       # Public recorder API
│       ├── TaalPlayer.kt         # Public player API
│       └── PreFilter.kt          # Filter enums
│
└── demo-app/                     # Demo application
    └── src/main/java/com/musediagnostics/taaldemo/
        └── MainActivity.kt
```

## Getting Started

### Prerequisites
- Android Studio Arctic Fox or later
- Minimum SDK: Android 7.0 (API 24)
- Target SDK: Android 14 (API 34)
- Kotlin 1.9.20

### Dependencies
The SDK uses the following libraries:
- AndroidX Core KTX 1.12.0
- Material Components 1.11.0
- MPAndroidChart v3.1.0 (for waveform visualization)
- Kotlinx Coroutines 1.7.3

### Building the Project

1. **Clone or open the project**:
   ```bash
   cd taal-sdk-project
   ```

2. **Sync Gradle**:
   Open in Android Studio and sync Gradle files

3. **Build the SDK**:
   ```bash
   ./gradlew :taal-sdk:build
   ```

4. **Build the demo app**:
   ```bash
   ./gradlew :demo-app:build
   ```

## Usage

### Basic Recording

```kotlin
// Create recorder instance
val recorder = TaalRecorder(context)

// Configure
recorder.setRawAudioFilePath("/path/to/output.wav")
recorder.setRecordingTime(30) // seconds
recorder.setPreFilter(PreFilter.HEART)
recorder.setPreAmplification(5) // 0-10dB

// Set listeners
recorder.onInfoListener = object : TaalRecorder.OnInfoListener {
    override fun onStateChange(state: RecorderState) {
        // Handle state changes
    }

    override fun onProgressUpdate(
        sampleRate: Int,
        bufferSize: Int,
        timeStamp: Double,
        data: FloatArray
    ) {
        // Process real-time audio data
    }
}

// Start recording
recorder.start()

// Stop when done
recorder.stop()
```

### Using RecorderActivity

```kotlin
val intent = RecorderActivity.getIntent(
    context = this,
    rawAudioFilePath = "/path/to/output.wav",
    playback = true,
    recordingTime = 30,
    preAmplification = 5,
    preFilter = PreFilter.HEART
)
startActivity(intent)
```

### Basic Playback

```kotlin
// Create player instance
val player = TaalPlayer(context)

// Configure
player.setDataSource("/path/to/recording.wav")
player.setLooping(false)

// Set graphic EQ
player.setGraphicEQ(
    AudioFilterEngine.GraphicEQState(
        band20Hz = 0f,    // ±12dB
        band50Hz = 2f,
        band100Hz = 0f,
        band200Hz = -3f,
        band600Hz = 1f
    )
)

// Prepare and start
player.prepare()
player.start()

// Stop when done
player.stop()
player.release()
```

### Using PlayerActivity

```kotlin
val intent = PlayerActivity.getIntent(
    context = this,
    filePath = "/path/to/recording.wav"
)
startActivity(intent)
```

### USB Connection Management

```kotlin
// Check connection status
val status = SurrUtils.isTaalDeviceConnected(context)
when (status) {
    ConnectionStatus.CONNECTED -> {
        // Device ready
    }
    ConnectionStatus.NOT_CONNECTED -> {
        // No USB device
    }
    ConnectionStatus.DEVICE_DOES_NOT_SUPPORT_OTG -> {
        // Device doesn't support USB OTG
    }
    ConnectionStatus.INVALID_TAAL_CONNECTED -> {
        // Wrong USB device type
    }
}

// Listen for connection events
val receiver = TaalConnectionBroadcastReceiver(object : TaalConnectionListener {
    override fun onTaalConnect() {
        // Device connected
    }

    override fun onTaalDisconnect() {
        // Device disconnected
    }
})

receiver.register(context)
// ... later ...
receiver.unregister(context)
```

## Technical Specifications

### Audio Format
- **Sample Rate**: 44,100 Hz
- **Bit Depth**: 16-bit PCM
- **Channels**: Mono
- **File Format**: WAV
- **Frequency Range**: 20 Hz - 2000 Hz (hard limit)

### DSP Architecture
- **Filter Type**: Butterworth bandpass (2nd order)
- **EQ Type**: Peaking filters (Q=1.0)
- **Processing**: Real-time, sample-by-sample biquad implementation
- **Latency**: Minimal (single-sample delay per filter stage)

## Permissions

Add to your app's `AndroidManifest.xml`:

```xml
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.USB_PERMISSION" />
<uses-feature
    android:name="android.hardware.usb.host"
    android:required="false" />
```

Request `RECORD_AUDIO` permission at runtime for Android 6.0+.

## Testing Checklist

- [ ] USB audio capture works on target device
- [ ] Waveform visualization renders correctly
- [ ] All preset filters apply correct frequency ranges
- [ ] Graphic EQ modifies audio in real-time
- [ ] Pre-amplification adjusts volume correctly
- [ ] Recording stops after configured duration
- [ ] WAV files playable in external players
- [ ] Connection status responds to USB plug/unplug

## Known Limitations

1. **USB Audio Class**: Requires Android device with USB OTG support and USB Audio Class driver
2. **Real-time Monitoring**: Playback during recording requires careful buffer management
3. **Frequency Limit**: Hard-coded 2000 Hz upper limit for clinical safety
4. **File Format**: Only WAV format supported (no MP3/AAC encoding)

## Troubleshooting

### "AudioRecord initialization failed"
- Check USB device is connected
- Verify device supports USB Audio Class
- Ensure RECORD_AUDIO permission granted

### No audio recorded
- Check USB permissions in system settings
- Verify device is not in use by another app
- Test with different USB cable

### Waveform not displaying
- Ensure MPAndroidChart dependency resolved
- Check layout inflates correctly
- Verify callbacks are on UI thread

## License

Copyright © 2024 MUSE Diagnostics

## Support

For issues, questions, or contributions, contact MUSE Diagnostics support.

---

**Built with ❤️ for better healthcare diagnostics**
