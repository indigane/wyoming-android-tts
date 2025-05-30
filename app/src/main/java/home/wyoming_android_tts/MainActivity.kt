package home.wyoming_android_tts

import android.Manifest // Keep this import
import android.app.Activity // CHANGE THIS IMPORT
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.View
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
// import androidx.appcompat.app.AppCompatActivity // REMOVE THIS IMPORT
import androidx.core.content.ContextCompat // Keep this import
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : Activity() { // CHANGE AppCompatActivity to Activity HERE

    private lateinit var logTextView: TextView
    private lateinit var logScrollView: ScrollView

    // Activity Result Launcher for notification permission request
    // This should still work with android.app.Activity as it's part of ActivityResultCaller
    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                Toast.makeText(this, "Notification permission granted", Toast.LENGTH_SHORT).show()
                startTtsService()
            } else {
                Toast.makeText(this, "Notification permission is required to run the service", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main) // This line should still be fine

        logTextView = findViewById(R.id.logTextView)
        logScrollView = findViewById(R.id.logScrollView)
        val startButton: Button = findViewById(R.id.buttonStartService)
        val stopButton: Button = findViewById(R.id.buttonStopService)
        val clearButton: Button = findViewById(R.id.buttonClearLogs)

        logTextView.movementMethod = ScrollingMovementMethod()

        startButton.setOnClickListener {
            askForNotificationPermission()
        }

        stopButton.setOnClickListener {
            val serviceIntent = Intent(this, WyomingTtsService::class.java)
            stopService(serviceIntent)
            AppLogger.log("Stop service request sent from UI.")
        }

        clearButton.setOnClickListener {
            logTextView.text = ""
        }

        lifecycleScope.launch { // lifecycleScope is available via androidx.lifecycle:lifecycle-runtime-ktx
            AppLogger.logMessages.collect { message ->
                logTextView.append("\n$message")
                logScrollView.post { logScrollView.fullScroll(View.FOCUS_DOWN) }
            }
        }
    }

    private fun askForNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when {
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED -> {
                    startTtsService()
                }
                // shouldShowRequestPermissionRationale is a method of Activity, so it's fine
                shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) -> {
                    requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                else -> {
                    requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        } else {
            startTtsService()
        }
    }

    private fun startTtsService() {
        val serviceIntent = Intent(this, WyomingTtsService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        AppLogger.log("Start service request sent from UI.")
    }
}
