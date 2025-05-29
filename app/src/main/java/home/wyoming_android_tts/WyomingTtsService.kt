package home.wyoming_android_tts

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
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
        private const val TAG = "WyomingTtsService"
        private const val NOTIFICATION_ID = 1
        private const val SERVER_PORT = 10300 // Default Wyoming port for TTS is often in this range
    }

    private var serverSocket: ServerSocket? = null
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob) // Scope for background tasks

    private var serverListeningJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate")
        // Server will be started in onStartCommand to ensure foreground promotion first
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service onStartCommand")

        val notification: Notification = NotificationCompat.Builder(this, WyomingTtsApp.CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_message_listening, SERVER_PORT))
            .setSmallIcon(R.mipmap.ic_launcher) // Replace with your actual app icon
            .build()

        startForeground(NOTIFICATION_ID, notification)

        startServer()

        return START_STICKY
    }

    private fun startServer() {
        if (serverListeningJob?.isActive == true) {
            Log.i(TAG, "Server already running on port $SERVER_PORT")
            return
        }

        serverListeningJob = serviceScope.launch {
            try {
                serverSocket = ServerSocket(SERVER_PORT)
                Log.i(TAG, "Wyoming TTS Server started, listening on port $SERVER_PORT")

                while (isActive) { // Loop to accept multiple client connections sequentially
                    try {
                        val clientSocket = serverSocket!!.accept() // Blocks until a connection is made
                        Log.i(TAG, "Client connected: ${clientSocket.inetAddress.hostAddress}")
                        // Handle each client in its own coroutine for non-blocking operations
                        launch { handleClient(clientSocket) }
                    } catch (e: IOException) {
                        if (isActive) { // Only log error if the scope is still active (not during shutdown)
                            Log.e(TAG, "Error accepting client connection", e)
                        }
                        // Potentially break or add a delay before retrying accept, depending on error type
                    }
                }
            } catch (e: IOException) {
                Log.e(TAG, "Could not start server on port $SERVER_PORT", e)
                // Consider stopping the service or notifying the user if the port is critical
            } finally {
                Log.i(TAG, "Server listening loop ended.")
                serverSocket?.close() // Ensure socket is closed when loop ends
            }
        }
        Log.i(TAG, "Wyoming TTS Server initiated.")
    }


    private suspend fun handleClient(clientSocket: Socket) {
        withContext(Dispatchers.IO) { // Ensure this runs on an IO thread
            try {
                Log.d(TAG, "Handling client: ${clientSocket.inetAddress.hostAddress}")
                val reader = BufferedReader(InputStreamReader(clientSocket.getInputStream()))
                // val writer = BufferedWriter(OutputStreamWriter(clientSocket.getOutputStream())) // For sending responses

                var line: String?
                while (clientSocket.isConnected && reader.readLine().also { line = it } != null) {
                    Log.d(TAG, "Received from client: $line")
                    try {
                        val jsonEvent = JSONObject(line!!)
                        val eventType = jsonEvent.optString("type")

                        if (eventType == "synthesize") {
                            val textToSynthesize = jsonEvent.optString("text")
                            val voiceParams = jsonEvent.optJSONObject("voice") // Optional voice parameters

                            Log.i(TAG, "Synthesize request received. Text: '$textToSynthesize'")
                            if (voiceParams != null) {
                                Log.i(TAG, "Voice params: ${voiceParams.toString(2)}")
                            }

                            // TODO: Phase 3 & 4 - Call Android TTS and stream audio back
                            // For now, just acknowledge by logging.
                            // Example of sending a simple text response (Wyoming is binary/JSONL):
                            // writer.write("{\"type\":\"acknowledgment\", \"message\":\"Synthesize request received for: $textToSynthesize\"}\n")
                            // writer.flush()

                        } else if (eventType.isNotEmpty()) {
                            Log.w(TAG, "Received unknown event type: $eventType")
                        } else if (line!!.trim().isNotEmpty()) {
                            Log.w(TAG, "Received non-JSON line or empty type: $line")
                        }

                    } catch (e: JSONException) {
                        Log.e(TAG, "Error parsing JSON from client: $line", e)
                    }
                }
            } catch (e: IOException) {
                Log.e(TAG, "IOException in client handler", e)
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error in client handler", e)
            } finally {
                try {
                    clientSocket.close()
                } catch (e: IOException) {
                    Log.e(TAG, "Error closing client socket", e)
                }
                Log.i(TAG, "Client disconnected: ${clientSocket.inetAddress?.hostAddress}")
            }
        }
    }

    private fun stopServer() {
        Log.i(TAG, "Stopping server...")
        serviceJob.cancel() // Cancel all coroutines started by serviceScope
        try {
            serverSocket?.close() // Close the server socket
            serverSocket = null
            Log.i(TAG, "Server socket closed.")
        } catch (e: IOException) {
            Log.e(TAG, "Error closing server socket", e)
        }
        Log.i(TAG, "Server stopped.")
    }

    override fun onDestroy() {
        super.onDestroy()
        stopServer()
        Log.d(TAG, "Service onDestroy")
        // stopForeground(STOP_FOREGROUND_REMOVE) is implicitly called by super.onDestroy() if service was started with startForeground
    }

    override fun onBind(intent: Intent?): IBinder? {
        Log.d(TAG, "Service onBind")
        return null // Not binding
    }
}
