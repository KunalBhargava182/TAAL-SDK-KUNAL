package com.musediagnostics.taaldemo

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.musediagnostics.taal.PreFilter
import com.musediagnostics.taal.ui.PlayerActivity
import com.musediagnostics.taal.ui.RecorderActivity
import java.io.File

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_AUDIO_PERMISSION = 200
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        checkPermissions()

        findViewById<Button>(R.id.btn_record).setOnClickListener {
            openRecorder()
        }

        findViewById<Button>(R.id.btn_play).setOnClickListener {
            openPlayer()
        }
    }

    private fun checkPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                REQUEST_AUDIO_PERMISSION
            )
        }
    }

    private fun openRecorder() {
        val outputFile = File(filesDir, "recording_${System.currentTimeMillis()}.wav")

        val intent = RecorderActivity.getIntent(
            this,
            rawAudioFilePath = outputFile.absolutePath,
            playback = true,
            recordingTime = 30,
            preAmplification = 5,
            preFilter = PreFilter.HEART
        )

        startActivity(intent)
    }

    private fun openPlayer() {
        // Find most recent recording
        val recordings = filesDir.listFiles()?.filter { it.extension == "wav" }

        if (recordings.isNullOrEmpty()) {
            Toast.makeText(this, "No recordings found", Toast.LENGTH_SHORT).show()
            return
        }

        val latestRecording = recordings.maxByOrNull { it.lastModified() }!!

        val intent = PlayerActivity.getIntent(this, latestRecording.absolutePath)
        startActivity(intent)
    }
}
