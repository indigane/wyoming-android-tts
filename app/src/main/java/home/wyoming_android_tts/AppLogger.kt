package home.wyoming_android_tts

import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A singleton object for centralized logging.
 * Logs to both the standard Android Logcat and a SharedFlow for in-app UI display.
 */
object AppLogger {
    private const val TAG = "WyomingApp"

    // A "hot" flow that broadcasts log messages to all collectors.
    // Replays the last 50 messages to new collectors so they get recent history.
    private val _logMessages = MutableSharedFlow<String>(replay = 50)
    val logMessages = _logMessages.asSharedFlow()

    fun log(message: String, level: LogLevel = LogLevel.INFO) {
        val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        val formattedMessage = "[$timestamp] $message"

        // Log to the standard Android Logcat for developers
        when (level) {
            LogLevel.INFO -> Log.i(TAG, message)
            LogLevel.DEBUG -> Log.d(TAG, message)
            LogLevel.WARN -> Log.w(TAG, message)
            LogLevel.ERROR -> Log.e(TAG, message)
        }

        // Emit the formatted message to the in-app console
        _logMessages.tryEmit(formattedMessage)
    }

    enum class LogLevel {
        DEBUG, INFO, WARN, ERROR
    }
}
