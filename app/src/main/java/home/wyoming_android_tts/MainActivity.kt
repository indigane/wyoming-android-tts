package home.wyoming_android_tts

import android.app.Activity // Using basic Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

// Removed imports for Manifest, PackageManager, Build, ActivityResultContracts, ContextCompat, lifecycleScope, launch

class MainActivity : Activity() { // Extends basic Activity

    private lateinit var logTextView: TextView
    // private lateinit var logScrollView: ScrollView // ScrollView itself doesn't need special handling here

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The critical line we are testing for the original theme crash:
        setContentView(R.layout.activity_main)

        logTextView = findViewById(R.id.logTextView)
        // logScrollView = findViewById(R.id.logScrollView) // Not strictly needed to assign if not manipulating directly

        val startButton: Button = findViewById(R.id.buttonStartService)
        val stopButton: Button = findViewById(R.id.buttonStopService)
        val clearButton: Button = findViewById(R.id.buttonClearLogs)

        // Log display will not update automatically from AppLogger for this diagnostic version
        // because we've removed lifecycleScope and its collector.
        // We'll just log that the activity tried to create.
        logTextView.text = "Diagnostic MainActivity started.\nIn-app log updates are temporarily disabled.\nCheck system Logcat if AppLogger is used by service."
        AppLogger.log("Basic Diagnostic MainActivity onCreate completed.")


        startButton.setOnClickListener {
            AppLogger.log("Start Service button clicked (functionality limited in diagnostic mode).")
            Toast.makeText(this, "Service start temporarily simplified for diagnostic", Toast.LENGTH_LONG).show()
            // For this diagnostic, we'll directly try to start the service without permission checks.
            // This might fail on Android 13+ if notifications aren't already allowed,
            // but our main goal is to see if the Activity itself launches.
            val serviceIntent = Intent(this, WyomingTtsService::class.java)
            try {
                // startForegroundService is available on android.app.Activity from API 26
                startForegroundService(serviceIntent)
                AppLogger.log("Attempted to start service from diagnostic MainActivity.")
            } catch (e: Exception) {
                AppLogger.log("Error trying to start service from diagnostic MainActivity: ${e.message}", AppLogger.LogLevel.ERROR)
                Toast.makeText(this, "Error starting service: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }

        stopButton.setOnClickListener {
            val serviceIntent = Intent(this, WyomingTtsService::class.java)
            stopService(serviceIntent)
            AppLogger.log("Stop service request sent from UI (diagnostic Activity).")
            Toast.makeText(this, "Stop service request sent", Toast.LENGTH_SHORT).show()
        }

        clearButton.setOnClickListener {
            logTextView.text = "Logs cleared (manual display only in diagnostic).\n"
        }
    }
}
