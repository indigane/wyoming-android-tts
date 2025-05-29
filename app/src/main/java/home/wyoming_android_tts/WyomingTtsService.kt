package home.wyoming_android_tts

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket

class WyomingTtsService : Service() {

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val SERVER_PORT = 10300
    }

    private var serverSocket: ServerSocket? = null
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    private var serverListeningJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        AppLogger.log("Service creating...")
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
                val reader = BufferedReader(InputStreamReader(clientSocket.getInputStream()))
                var line: String?
                while (clientSocket.isConnected && reader.readLine().also { line = it } != null) {
                    AppLogger.log("RECV: $line")
                    try {
                        val jsonEvent = JSONObject(line!!)
                        when (jsonEvent.optString("type")) {
                            "synthesize" -> {
                                val text = jsonEvent.optString("text", "no text found")
                                val voice = jsonEvent.optJSONObject("voice")
                                AppLogger.log("Synthesize request received. Text: '$text'")
                                if (voice != null) AppLogger.log("Voice params: $voice")

                                // TODO: Phase 3 & 4 - TTS call and audio streaming
                            }
                            "ping" -> {
                                // A simple ping/pong can be useful for checking connection
                                AppLogger.log("Ping received, ponging back.")
                                // TODO: writer.write("{\"type\":\"pong\"}\n")
                            }
                            else -> AppLogger.log("Received unknown event type: ${jsonEvent.optString("type")}", AppLogger.LogLevel.WARN)
                        }
                    } catch (e: JSONException) {
                        AppLogger.log("Error parsing JSON from client: $line", AppLogger.LogLevel.ERROR)
                    }
                }
            } catch (e: IOException) {
                 AppLogger.log("Client connection error: ${e.message}", AppLogger.LogLevel.ERROR)
            } finally {
                try {
                    clientSocket.close()
                } catch (e: IOException) {
                    // Ignore
                }
                AppLogger.log("Client disconnected: ${clientSocket.inetAddress?.hostAddress}")
            }
        }
    }

    private fun stopServer() {
        AppLogger.log("Stopping server...")
        serviceJob.cancel()
        try {
            serverSocket?.close()
            serverSocket = null
        } catch (e: IOException) {
            AppLogger.log("Error closing server socket: ${e.message}", AppLogger.LogLevel.ERROR)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopServer()
        AppLogger.log("Service destroyed.")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
