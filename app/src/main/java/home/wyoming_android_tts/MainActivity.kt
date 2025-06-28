package home.wyoming_android_tts

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var logTextView: TextView
    private lateinit var logScrollView: ScrollView

    // Activity Result Launcher for notification permission request
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
        setContentView(R.layout.activity_main)

        logTextView = findViewById(R.id.logTextView)
        logScrollView = findViewById(R.id.logScrollView)
        val startButton: Button = findViewById(R.id.buttonStartService)
        val stopButton: Button = findViewById(R.id.buttonStopService)
        val clearButton: Button = findViewById(R.id.buttonClearLogs)

        // Make the TextView scrollable
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

        // Collect log messages from the AppLogger and display them
        lifecycleScope.launch {
            AppLogger.logMessages.collect { message ->
                // Append message and scroll to the bottom
                logTextView.append("\n$message")
                logScrollView.post { logScrollView.fullScroll(View.FOCUS_DOWN) }
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
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
