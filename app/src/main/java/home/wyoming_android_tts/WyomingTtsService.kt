package home.wyoming_android_tts

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.os.Bundle // For TTS params
import android.os.IBinder
import android.speech.tts.TextToSpeech // TTS Import
import android.speech.tts.UtteranceProgressListener // TTS Import
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import org.json.JSONException
import org.json.JSONObject
import java.io.File // For File operations
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.util.* // For Locale and UUID

class WyomingTtsService : Service(), TextToSpeech.OnInitListener { // Implement OnInitListener

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val SERVER_PORT = 10300
        // Using the AppLogger's TAG might be better for consistency if AppLogger uses a specific one
        private const val TTS_TAG = "WyomingTtsEngine"
    }

    private var serverSocket: ServerSocket? = null
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private var serverListeningJob: Job? = null

    // TextToSpeech Engine
    private var tts: TextToSpeech? = null
    private var ttsInitialized = false
    private val ttsUtteranceRequests = mutableMapOf<String, Socket>() // To map utteranceId to client later

    override fun onCreate() {
        super.onCreate()
        AppLogger.log("Service creating...")
        setupTextToSpeech()
    }

    private fun setupTextToSpeech() {
        AppLogger.log("Initializing TextToSpeech engine...", AppLogger.LogLevel.DEBUG, TTS_TAG)
        try {
            tts = TextToSpeech(this, this)
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    AppLogger.log("TTS onStart for utteranceId: $utteranceId", AppLogger.LogLevel.INFO, TTS_TAG)
                }

                override fun onDone(utteranceId: String?) {
                    AppLogger.log("TTS onDone: Speech synthesis completed for $utteranceId. File is ready.", AppLogger.LogLevel.INFO, TTS_TAG)
                    // TODO: Phase 4 - Read this file and send audio chunks to the client
                    // val clientSocket = ttsUtteranceRequests.remove(utteranceId)
                    // if (clientSocket != null) { /* Send data */ }
                    // For now, clean up the temp file
                    val tempFile = File(cacheDir, "$utteranceId.wav")
                    if (tempFile.exists()) {
                        AppLogger.log("Deleting temporary TTS file: ${tempFile.path}", AppLogger.LogLevel.DEBUG, TTS_TAG)
                        // tempFile.delete() // Let's keep it for now for easy inspection in Phase 3 testing
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    AppLogger.log("TTS onError: Speech synthesis failed for $utteranceId", AppLogger.LogLevel.ERROR, TTS_TAG)
                    ttsUtteranceRequests.remove(utteranceId)
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    AppLogger.log("TTS onError: Speech synthesis failed for $utteranceId, errorCode: $errorCode", AppLogger.LogLevel.ERROR, TTS_TAG)
                    // Log more descriptive error messages
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
                    AppLogger.log("TTS Error details: $errorMsg", AppLogger.LogLevel.ERROR, TTS_TAG)
                    ttsUtteranceRequests.remove(utteranceId)
                }
            })
        } catch (e: Exception) {
            AppLogger.log("Exception during TTS setup: ${e.message}", AppLogger.LogLevel.ERROR, TTS_TAG)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            // Set language if needed, default is usually device language
            val result = tts?.setLanguage(Locale.US) // Example: Set to US English
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                AppLogger.log("TTS language (US English) is not supported or missing data.", AppLogger.LogLevel.WARN, TTS_TAG)
                // Potentially try another language or proceed with device default
            } else {
                AppLogger.log("TTS engine initialized successfully. Language set to US English (or default).", AppLogger.LogLevel.INFO, TTS_TAG)
            }
            ttsInitialized = true
        } else {
            AppLogger.log("TTS engine initialization failed. Status: $status", AppLogger.LogLevel.ERROR, TTS_TAG)
            ttsInitialized = false
        }
    }

    private fun synthesizeTextToFile(text: String, utteranceId: String): File? {
        if (!ttsInitialized || tts == null) {
            AppLogger.log("TTS not initialized, cannot synthesize.", AppLogger.LogLevel.ERROR, TTS_TAG)
            return null
        }

        val outputFile = File(cacheDir, "$utteranceId.wav")
        AppLogger.log("Attempting to synthesize to file: ${outputFile.absolutePath}", AppLogger.LogLevel.DEBUG, TTS_TAG)

        val params = Bundle()
        // Note: Some engines might ignore KEY_PARAM_VOLUME or other params for synthesizeToFile.
        // params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)

        val result = tts?.synthesizeToFile(text, params, outputFile, utteranceId)

        if (result == TextToSpeech.SUCCESS) {
            AppLogger.log("TTS synthesizeToFile call successful for utteranceId: $utteranceId. Waiting for onDone/onError.", AppLogger.LogLevel.INFO, TTS_TAG)
            return outputFile
        } else {
            AppLogger.log("TTS synthesizeToFile call failed for utteranceId: $utteranceId. Result code: $result", AppLogger.LogLevel.ERROR, TTS_TAG)
            return null
        }
    }


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
                while (isActive) { // Loop to accept multiple client connections sequentially
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
                        AppLogger.log("RECV: $line")
                        try {
                            val jsonEvent = JSONObject(line)
                            when (jsonEvent.optString("type")) {
                                "synthesize" -> {
                                    val textToSynthesize = jsonEvent.optString("text", "no text found")
                                    val voiceParams = jsonEvent.optJSONObject("voice") // Optional
                                    AppLogger.log("Synthesize request received. Text: '$textToSynthesize'")
                                    if (voiceParams != null) AppLogger.log("Voice params: $voiceParams")

                                    if (textToSynthesize.isNotEmpty()) {
                                        val utteranceId = UUID.randomUUID().toString()
                                        // Store client for response later (more relevant for Phase 4)
                                        // ttsUtteranceRequests[utteranceId] = clientSocket
                                        synthesizeTextToFile(textToSynthesize, utteranceId)
                                    } else {
                                        AppLogger.log("Received synthesize request with empty text.", AppLogger.LogLevel.WARN)
                                    }
                                }
                                "ping" -> { // Example handler for a ping
                                    AppLogger.log("Ping received.")
                                    // TODO: Send pong back to clientSocket if needed
                                }
                                else -> AppLogger.log("Received unknown event type: ${jsonEvent.optString("type")}", AppLogger.LogLevel.WARN)
                            }
                        } catch (e: JSONException) {
                            AppLogger.log("Error parsing JSON from client: $line", AppLogger.LogLevel.ERROR)
                        }
                    }
                }
            } catch (e: IOException) {
                 AppLogger.log("Client connection error: ${e.message}", AppLogger.LogLevel.ERROR)
            } finally {
                try {
                    clientSocket.close()
                } catch (e: IOException) {
                    // Ignore, already closing
                }
                AppLogger.log("Client disconnected: ${clientSocket.inetAddress?.hostAddress}")
            }
        }
    }

    private fun stopServer() {
        AppLogger.log("Stopping server...")
        serverListeningJob?.cancel() // Cancel the server listening loop first
        serviceJob.cancel() // Then cancel the parent scope for any client handlers
        try {
            serverSocket?.close()
            serverSocket = null
        } catch (e: IOException) {
            AppLogger.log("Error closing server socket: ${e.message}", AppLogger.LogLevel.ERROR)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        AppLogger.log("Service destroying...")
        stopServer()
        if (tts != null) {
            AppLogger.log("Shutting down TTS engine.", AppLogger.LogLevel.INFO, TTS_TAG)
            tts!!.stop()
            tts!!.shutdown()
            tts = null
            ttsInitialized = false
        }
        AppLogger.log("Service destroyed.")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
