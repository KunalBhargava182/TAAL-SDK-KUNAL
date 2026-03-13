package com.musediagnostics.taal.uikit

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.fragment.NavHostFragment
import com.musediagnostics.taal.uikit.R

/**
 * Standalone player activity for reviewing and playing an existing WAV file.
 *
 * Usage:
 * ```kotlin
 * startActivity(
 *     TaalPlayerActivity.getIntent(context, filePath = "/path/to/file.wav")
 * )
 * ```
 */
class TaalPlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_FILE_PATH = "filePath"

        fun getIntent(context: Context, filePath: String): Intent {
            return Intent(context, TaalPlayerActivity::class.java).apply {
                putExtra(EXTRA_FILE_PATH, filePath)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_taal_player)

        if (savedInstanceState != null) return

        val filePath = intent.getStringExtra(EXTRA_FILE_PATH) ?: ""

        // Create NavHostFragment without a nav graph (we'll set it manually)
        val navHostFragment = NavHostFragment()
        supportFragmentManager.beginTransaction()
            .replace(R.id.navHostPlayer, navHostFragment)
            .setPrimaryNavigationFragment(navHostFragment)
            .commitNow()

        // Inflate the nav graph, change start destination to playerFragment, set args
        val navController = navHostFragment.navController
        val graph = navController.navInflater.inflate(R.navigation.nav_uikit)
        graph.setStartDestination(R.id.playerFragment)

        val bundle = Bundle().apply {
            putString("filePath", filePath)
            putBoolean("isNewRecording", false)
        }
        navController.setGraph(graph, bundle)
    }
}
