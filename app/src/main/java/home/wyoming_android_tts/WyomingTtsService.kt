package home.wyoming_android_tts

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.*
import kotlin.collections.set

class WyomingTtsService : Service(), TextToSpeech.OnInitListener {

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val SERVER_PORT = 10300
    }

    private var serverSocket: ServerSocket? = null
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private var serverListeningJob: Job? = null
    private var tts: TextToSpeech? = null
    private var ttsInitialized = false
    private val ttsUtteranceRequests = mutableMapOf<String, CompletableDeferred<File?>>()
    private data class WavInfo(val sampleRate: Int, val channels: Int, val bitsPerSample: Int)


    private suspend fun handleClient(clientSocket: Socket) {
        withContext(Dispatchers.IO) {
            try {
                val outputStream = clientSocket.getOutputStream()
                clientSocket.getInputStream().bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        AppLogger.log("RECV: $line")
                        try {
                            val jsonEvent = JSONObject(line)
                            when (jsonEvent.optString("type")) {
                                "describe" -> {
                                    // NEW: Handle the describe event from Home Assistant
                                    val describeResponse = getTtsDescribeJson()
                                    writeJsonEvent(outputStream, describeResponse)
                                    AppLogger.log("Responded to 'describe' request.")
                                }
                                "synthesize" -> {
                                    val textToSynthesize = jsonEvent.optString("text", "")
                                    if (textToSynthesize.isNotEmpty()) {
                                        val utteranceId = UUID.randomUUID().toString()
                                        val deferred = CompletableDeferred<File?>()
                                        ttsUtteranceRequests[utteranceId] = deferred

                                        synthesizeTextToFile(textToSynthesize, utteranceId)
                                        val resultFile = deferred.await()

                                        if (resultFile != null) {
                                            streamWavFile(clientSocket, resultFile)
                                        } else {
                                            AppLogger.log("TTS failed to produce a file for utteranceId $utteranceId", AppLogger.LogLevel.ERROR)
                                        }
                                    } else {
                                        AppLogger.log("Synthesize request with empty text.", AppLogger.LogLevel.WARN)
                                    }
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
                } catch (e: IOException) { /* Ignore */ }
                AppLogger.log("Client disconnected and socket closed: ${clientSocket.inetAddress?.hostAddress}")
            }
        }
    }

    private fun getTtsDescribeJson(): JSONObject {
        val ttsServiceInfo = JSONObject()
        ttsServiceInfo.put("type", "tts")

        val voicesArray = JSONArray()
        if (ttsInitialized && tts != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                for (voice in tts!!.voices) {
                    val voiceInfo = JSONObject()
                    voiceInfo.put("id", voice.name) // Use the unique voice name as ID
                    voiceInfo.put("name", voice.name)
                    voiceInfo.put("language", voice.locale.toLanguageTag()) // e.g., "en-US"

                    val attributes = JSONObject()
                    attributes.put("gender", "UNKNOWN") // Android Voice API doesn't provide gender
                    attributes.put("piper_voice", false)
                    voiceInfo.put("attributes", attributes)
                    
                    voicesArray.put(voiceInfo)
                }
            } catch (e: Exception) {
                AppLogger.log("Failed to get TTS voices: ${e.message}", AppLogger.LogLevel.ERROR)
            }
        }
        ttsServiceInfo.put("voices", voicesArray)

        val servicesArray = JSONArray()
        servicesArray.put(ttsServiceInfo)

        val describeResponse = JSONObject()
        describeResponse.put("type", "describe")
        describeResponse.put("services", servicesArray)

        return describeResponse
    }

    private suspend fun writeJsonEvent(stream: OutputStream, json: JSONObject) {
        val jsonString = json.toString() + "\n"
        stream.write(jsonString.toByteArray())
        stream.flush()
        AppLogger.log("SENT: $jsonString", AppLogger.LogLevel.DEBUG)
    }
    
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
                    AppLogger.log("[TTS] onStart for utteranceId: $utteranceId", AppLogger.LogLevel.INFO)
                }

                override fun onDone(utteranceId: String?) {
                    AppLogger.log("[TTS] onDone: Speech synthesis completed for $utteranceId.", AppLogger.LogLevel.INFO)
                    val deferred = ttsUtteranceRequests.remove(utteranceId)
                    val file = File(cacheDir, "$utteranceId.wav")
                    if (file.exists()) {
                        deferred?.complete(file)
                    } else {
                        deferred?.complete(null)
                    }
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    AppLogger.log("[TTS] onError: Speech synthesis failed for $utteranceId, errorCode: $errorCode", AppLogger.LogLevel.ERROR)
                    val deferred = ttsUtteranceRequests.remove(utteranceId)
                    deferred?.complete(null) // Signal failure by completing with null
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    onError(utteranceId, -1) // Forward to the modern onError
                }
            })
        } catch (e: Exception) {
            AppLogger.log("[TTS] Exception during TTS setup: ${e.message}", AppLogger.LogLevel.ERROR)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.US)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                AppLogger.log("[TTS] Language (US English) is not supported or missing data.", AppLogger.LogLevel.WARN)
            } else {
                AppLogger.log("[TTS] Engine initialized successfully.", AppLogger.LogLevel.INFO)
            }
            ttsInitialized = true
        } else {
            AppLogger.log("[TTS] Engine initialization failed. Status: $status", AppLogger.LogLevel.ERROR)
            ttsInitialized = false
        }
    }

    private fun synthesizeTextToFile(text: String, utteranceId: String) {
        if (!ttsInitialized || tts == null) {
            AppLogger.log("[TTS] Not initialized, cannot synthesize.", AppLogger.LogLevel.ERROR)
            val deferred = ttsUtteranceRequests.remove(utteranceId)
            deferred?.complete(null) // Immediately signal failure
            return
        }

        val outputFile = File(cacheDir, "$utteranceId.wav")
        val params = Bundle()
        val result = tts?.synthesizeToFile(text, params, outputFile, utteranceId)

        if (result != TextToSpeech.SUCCESS) {
            AppLogger.log("[TTS] synthesizeToFile call failed immediately for $utteranceId. Result code: $result", AppLogger.LogLevel.ERROR)
            val deferred = ttsUtteranceRequests.remove(utteranceId)
            deferred?.complete(null) // Signal failure
        } else {
            AppLogger.log("[TTS] synthesizeToFile call successful for $utteranceId. Waiting for onDone/onError callback.", AppLogger.LogLevel.INFO)
        }
    }

    private suspend fun streamWavFile(socket: Socket, wavFile: File) {
        var fileInputStream: FileInputStream? = null
        try {
            fileInputStream = FileInputStream(wavFile)
            val wavInfo = parseWavHeader(fileInputStream)

            if (wavInfo == null) {
                AppLogger.log("Failed to parse WAV header for ${wavFile.name}", AppLogger.LogLevel.ERROR)
                return
            }
            AppLogger.log("[TTS] Parsed WAV: ${wavInfo.sampleRate} Hz, ${wavInfo.bitsPerSample}-bit, ${wavInfo.channels} channels", AppLogger.LogLevel.INFO)

            val outputStream = socket.getOutputStream()
            
            // 1. Send audio-start event
            writeAudioStart(outputStream, wavInfo.sampleRate, wavInfo.bitsPerSample / 8, wavInfo.channels)

            // 2. Send audio-chunk events
            val buffer = ByteArray(4096)
            var bytesRead: Int
            while (fileInputStream.read(buffer).also { bytesRead = it } != -1) {
                writeAudioChunk(outputStream, buffer, bytesRead)
            }
            // Send final empty chunk to signal end of audio data within the stream
            writeAudioChunk(outputStream, ByteArray(0), 0)

            // 3. Send audio-stop event
            writeAudioStop(outputStream)
            
            AppLogger.log("Finished streaming ${wavFile.name}", AppLogger.LogLevel.INFO)

        } catch (e: IOException) {
            AppLogger.log("Error streaming WAV file: ${e.message}", AppLogger.LogLevel.ERROR)
        } finally {
            fileInputStream?.close()
            // Clean up the temporary file
            if (wavFile.exists()) {
                wavFile.delete()
                AppLogger.log("Deleted temporary TTS file: ${wavFile.name}", AppLogger.LogLevel.DEBUG)
            }
        }
    }

    private fun parseWavHeader(stream: FileInputStream): WavInfo? {
        try {
            val header = ByteArray(44)
            if (stream.read(header, 0, 44) < 44) return null

            val bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)

            if (String(header, 0, 4) != "RIFF" || String(header, 8, 4) != "WAVE") {
                return null // Not a valid WAVE file
            }

            val channels = bb.getShort(22).toInt()
            val sampleRate = bb.getInt(24)
            val bitsPerSample = bb.getShort(34).toInt()

            return WavInfo(sampleRate, channels, bitsPerSample)
        } catch (e: Exception) {
            AppLogger.log("Could not parse WAV header: ${e.message}", AppLogger.LogLevel.ERROR)
            return null
        }
    }

    private suspend fun writeAudioStart(stream: OutputStream, rate: Int, width: Int, channels: Int) {
        val json = JSONObject().apply {
            put("type", "audio-start")
            put("rate", rate)
            put("width", width)
            put("channels", channels)
        }.toString() + "\n"
        stream.write(json.toByteArray())
        stream.flush()
        AppLogger.log("SENT: ${json.trim()}", AppLogger.LogLevel.DEBUG)
    }

    private suspend fun writeAudioChunk(stream: OutputStream, data: ByteArray, length: Int) {
        // For the final chunk, length will be 0
        if (length == 0) {
            val json = JSONObject().apply {
                put("type", "audio-chunk")
                put("payload_length", 0)
            }.toString() + "\n"
            stream.write(json.toByteArray())
            stream.flush()
            AppLogger.log("SENT: Final empty audio-chunk", AppLogger.LogLevel.DEBUG)
            return
        }

        val json = JSONObject().apply {
            put("type", "audio-chunk")
            put("payload_length", length)
        }.toString() + "\n"

        stream.write(json.toByteArray())
        stream.write(data, 0, length)
        stream.flush()
    }

    private suspend fun writeAudioStop(stream: OutputStream) {
        val json = JSONObject().apply {
            put("type", "audio-stop")
        }.toString() + "\n"
        stream.write(json.toByteArray())
        stream.flush()
        AppLogger.log("SENT: ${json.trim()}", AppLogger.LogLevel.DEBUG)
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

    private fun stopServer() {
        AppLogger.log("Stopping server...")
        serverListeningJob?.cancel()
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
        AppLogger.log("Service destroying...")
        stopServer()
        if (tts != null) {
            AppLogger.log("[TTS] Shutting down TextToSpeech engine.", AppLogger.LogLevel.INFO)
            tts?.stop()
            tts?.shutdown()
            tts = null
            ttsInitialized = false
        }
        AppLogger.log("Service destroyed.")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
