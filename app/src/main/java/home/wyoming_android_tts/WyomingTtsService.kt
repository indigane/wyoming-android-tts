package home.wyoming_android_tts

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.util.*

class WyomingTtsService : Service(), TextToSpeech.OnInitListener {

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val SERVER_PORT = 10300
        // No longer need TTS_TAG here if AppLogger handles its own tagging or we include context in messages
    }

    private var serverSocket: ServerSocket? = null
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private var serverListeningJob: Job? = null

    private var tts: TextToSpeech? = null
    private var ttsInitialized = false
    // private val ttsUtteranceRequests = mutableMapOf<String, Socket>() // For Phase 4

    override fun onCreate() {
        super.onCreate()
        AppLogger.log("Service creating...")
        setupTextToSpeech()
    }

    private fun setupTextToSpeech() {
        AppLogger.log("[TTS] Initializing TextToSpeech engine...", AppLogger.LogLevel.DEBUG)
        try {
            tts = TextToSpeech(this, this)
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    // Corrected call:
                    AppLogger.log("[TTS] onStart for utteranceId: $utteranceId", AppLogger.LogLevel.INFO)
                }

                override fun onDone(utteranceId: String?) {
                    // Corrected call:
                    AppLogger.log("[TTS] onDone: Speech synthesis completed for $utteranceId. File is ready.", AppLogger.LogLevel.INFO)
                    val tempFile = File(cacheDir, "$utteranceId.wav")
                    if (tempFile.exists()) {
                        // Corrected call:
                        AppLogger.log("[TTS] Temporary file created: ${tempFile.path}", AppLogger.LogLevel.DEBUG)
                        // For Phase 3 testing, we keep the file.
                        // tempFile.delete()
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    // Corrected call:
                    AppLogger.log("[TTS] onError (deprecated): Speech synthesis failed for $utteranceId", AppLogger.LogLevel.ERROR)
                    // ttsUtteranceRequests.remove(utteranceId) // For Phase 4
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    // Corrected call:
                    AppLogger.log("[TTS] onError: Speech synthesis failed for $utteranceId, errorCode: $errorCode", AppLogger.LogLevel.ERROR)
                    val errorMsg = when (errorCode) {
                        TextToSpeech.ERROR_SYNTHESIS -> "ERROR_SYNTHESIS"
                        TextToSpeech.ERROR_SERVICE -> "ERROR_SERVICE"
                        TextToSpeech.ERROR_OUTPUT -> "ERROR_OUTPUT"
                        TextToSpeech.ERROR_NETWORK -> "ERROR_NETWORK"
                        TextToSpeech.ERROR_NETWORK_TIMEOUT -> "ERROR_NETWORK_TIMEOUT"
                        TextToSpeech.ERROR_INVALID_REQUEST -> "ERROR_INVALID_REQUEST"
                        TextToSpeech.ERROR_NOT_INSTALLED_YET -> "ERROR_NOT_INSTALLED_YET"
                        else -> "Unknown TTS Error"
                    }
                    // Corrected call:
                    AppLogger.log("[TTS] Error details: $errorMsg", AppLogger.LogLevel.ERROR)
                    // ttsUtteranceRequests.remove(utteranceId) // For Phase 4
                }
            })
        } catch (e: Exception) {
            // Corrected call:
            AppLogger.log("[TTS] Exception during TTS setup: ${e.message}", AppLogger.LogLevel.ERROR)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.US)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                // Corrected call:
                AppLogger.log("[TTS] Language (US English) is not supported or missing data.", AppLogger.LogLevel.WARN)
            } else {
                // Corrected call:
                AppLogger.log("[TTS] Engine initialized successfully. Language set.", AppLogger.LogLevel.INFO)
            }
            ttsInitialized = true
        } else {
            // Corrected call:
            AppLogger.log("[TTS] Engine initialization failed. Status: $status", AppLogger.LogLevel.ERROR)
            ttsInitialized = false
        }
    }

    private fun synthesizeTextToFile(text: String, utteranceId: String): File? {
        if (!ttsInitialized || tts == null) {
            // Corrected call:
            AppLogger.log("[TTS] Not initialized, cannot synthesize.", AppLogger.LogLevel.ERROR)
            return null
        }

        val outputFile = File(cacheDir, "$utteranceId.wav")
        // Corrected call:
        AppLogger.log("[TTS] Attempting to synthesize to file: ${outputFile.absolutePath}", AppLogger.LogLevel.DEBUG)

        val params = Bundle()
        // params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f) // Example

        val result = tts?.synthesizeToFile(text, params, outputFile, utteranceId)

        if (result == TextToSpeech.SUCCESS) {
            // Corrected call:
            AppLogger.log("[TTS] synthesizeToFile call successful for utteranceId: $utteranceId. Waiting for onDone/onError.", AppLogger.LogLevel.INFO)
            return outputFile
        } else {
            // Corrected call:
            AppLogger.log("[TTS] synthesizeToFile call failed for utteranceId: $utteranceId. Result code: $result", AppLogger.LogLevel.ERROR)
            return null
        }
    }

    // ... (onStartCommand, startServer, handleClient, stopServer remain mostly the same,
    // just ensure AppLogger calls in them are also correct if they had the extra tag)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AppLogger.log("Service starting...")
        val notification: Notification = NotificationCompat.Builder(this, WyomingTtsApp.CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_message_listening, SERVER_PORT))
            .setSmallIcon(R.mipmap.ic_launcher)
            .build()
        startForeground(NOTIFICATION_ID, notification)
        startServer()
        return START_STICKY
    }

    private fun startServer() {
        if (serverListeningJob?.isActive == true) {
            AppLogger.log("Server already running on port $SERVER_PORT")
            return
        }
        serverListeningJob = serviceScope.launch {
            try {
                serverSocket = ServerSocket(SERVER_PORT)
                AppLogger.log("Server started, listening on port $SERVER_PORT")
                while (isActive) {
                    try {
                        val clientSocket = serverSocket!!.accept()
                        AppLogger.log("Client connected: ${clientSocket.inetAddress.hostAddress}")
                        launch { handleClient(clientSocket) }
                    } catch (e: IOException) {
                        if (isActive) {
                            AppLogger.log("Error accepting client connection: ${e.message}", AppLogger.LogLevel.ERROR)
                        }
                    }
                }
            } catch (e: IOException) {
                AppLogger.log("Could not start server on port $SERVER_PORT: ${e.message}", AppLogger.LogLevel.ERROR)
            } finally {
                AppLogger.log("Server listening loop ended.", AppLogger.LogLevel.WARN)
                serverSocket?.close()
            }
        }
    }

    private suspend fun handleClient(clientSocket: Socket) {
        withContext(Dispatchers.IO) {
            try {
                clientSocket.getInputStream().bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        AppLogger.log("RECV: $line") // This call was already correct
                        try {
                            val jsonEvent = JSONObject(line)
                            when (jsonEvent.optString("type")) {
                                "synthesize" -> {
                                    val textToSynthesize = jsonEvent.optString("text", "no text found")
                                    val voiceParams = jsonEvent.optJSONObject("voice")
                                    AppLogger.log("Synthesize request received. Text: '$textToSynthesize'") // Correct
                                    if (voiceParams != null) AppLogger.log("Voice params: $voiceParams") // Correct

                                    if (textToSynthesize.isNotEmpty()) {
                                        val utteranceId = UUID.randomUUID().toString()
                                        synthesizeTextToFile(textToSynthesize, utteranceId)
                                    } else {
                                        AppLogger.log("Received synthesize request with empty text.", AppLogger.LogLevel.WARN) // Correct
                                    }
                                }
                                "ping" -> {
                                    AppLogger.log("Ping received.") // Correct
                                }
                                else -> AppLogger.log("Received unknown event type: ${jsonEvent.optString("type")}", AppLogger.LogLevel.WARN) // Correct
                            }
                        } catch (e: JSONException) {
                            AppLogger.log("Error parsing JSON from client: $line", AppLogger.LogLevel.ERROR) // Correct
                        }
                    }
                }
            } catch (e: IOException) {
                 AppLogger.log("Client connection error: ${e.message}", AppLogger.LogLevel.ERROR) // Correct
            } finally {
                try {
                    clientSocket.close()
                } catch (e: IOException) { /* Ignore */ }
                AppLogger.log("Client disconnected: ${clientSocket.inetAddress?.hostAddress}") // Correct
            }
        }
    }

    private fun stopServer() {
        AppLogger.log("Stopping server...") // Correct
        serverListeningJob?.cancel()
        serviceJob.cancel()
        try {
            serverSocket?.close()
            serverSocket = null
        } catch (e: IOException) {
            AppLogger.log("Error closing server socket: ${e.message}", AppLogger.LogLevel.ERROR) // Correct
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        AppLogger.log("Service destroying...") // Correct
        stopServer()
        if (tts != null) {
            // Corrected calls:
            AppLogger.log("[TTS] Shutting down TextToSpeech engine.", AppLogger.LogLevel.INFO)
            tts?.stop()
            tts?.shutdown()
            tts = null
            ttsInitialized = false
        }
        AppLogger.log("Service destroyed.") // Correct
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
