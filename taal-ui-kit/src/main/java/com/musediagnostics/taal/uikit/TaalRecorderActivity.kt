package com.musediagnostics.taal.uikit

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.fragment.NavHostFragment
import com.musediagnostics.taal.uikit.R

/**
 * Entry point for recording a new audio file using the TAAL stethoscope.
 *
 * Usage:
 * ```kotlin
 * startActivityForResult(
 *     TaalRecorderActivity.getIntent(
 *         context = this,
 *         preFilter = "HEART",         // optional, default "HEART"
 *         preAmplification = 5,        // optional, default 5 dB
 *         recordingTimeSeconds = 300   // optional, default 300s
 *     ), REQUEST_CODE_RECORD
 * )
 *
 * override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
 *     if (requestCode == REQUEST_CODE_RECORD && resultCode == RESULT_OK) {
 *         val filePath = data?.getStringExtra(TaalRecorderActivity.RESULT_FILE_PATH)
 *     }
 * }
 * ```
 */
class TaalRecorderActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PRE_FILTER = "preFilter"
        const val EXTRA_PRE_AMPLIFICATION = "preAmplification"
        const val EXTRA_RECORDING_TIME_SECONDS = "recordingTimeSeconds"
        const val RESULT_FILE_PATH = "filePath"

        fun getIntent(
            context: Context,
            preFilter: String = "HEART",
            preAmplification: Int = 5,
            recordingTimeSeconds: Int = 300
        ): Intent {
            return Intent(context, TaalRecorderActivity::class.java).apply {
                putExtra(EXTRA_PRE_FILTER, preFilter)
                putExtra(EXTRA_PRE_AMPLIFICATION, preAmplification)
                putExtra(EXTRA_RECORDING_TIME_SECONDS, recordingTimeSeconds)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_taal_recorder)

        if (savedInstanceState == null) {
            val navHostFragment = NavHostFragment.create(R.navigation.nav_uikit)
            supportFragmentManager.beginTransaction()
                .replace(R.id.navHostRecorder, navHostFragment)
                .setPrimaryNavigationFragment(navHostFragment)
                .commit()
        }
    }

    /**
     * Sets RESULT_OK with the saved file path but does NOT finish the activity.
     * Called when a recording is saved; user stays in the app to browse saved recordings.
     * The activity delivers this result when it eventually finishes (e.g. user presses back).
     */
    fun storeResult(filePath: String) {
        val intent = Intent().putExtra(RESULT_FILE_PATH, filePath)
        setResult(RESULT_OK, intent)
    }

    /**
     * Called by PlayerFragment when the user confirms saving the recording.
     * Finishes the activity with RESULT_OK and the saved file path.
     */
    fun finishWithResult(filePath: String) {
        storeResult(filePath)
        finish()
    }

    /**
     * Called by PlayerFragment when the user discards the recording.
     * Finishes the activity with RESULT_CANCELED.
     */
    fun discardAndFinish() {
        setResult(RESULT_CANCELED)
        finish()
    }
}
