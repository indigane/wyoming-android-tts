package home.wyoming_android_tts

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.*
import kotlin.collections.set


class WyomingTtsService : Service(), TextToSpeech.OnInitListener {

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val SERVER_PORT = 10300
        private const val WYOMING_PROTOCOL_VERSION = "1.6.0"
        private const val APP_VERSION = "1.0.0"
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
            val clientAddress = clientSocket.inetAddress?.hostAddress ?: "unknown"
            AppLogger.log("Handling client: $clientAddress")

            try {
                // Set a read timeout on the socket (e.g., 30 seconds)
                // If no data is received from the client within this period, readLine() will throw SocketTimeoutException.
                clientSocket.soTimeout = 30000 // 30 seconds

                val inputStream = clientSocket.getInputStream()
                val reader = BufferedReader(InputStreamReader(inputStream, StandardCharsets.UTF_8))
                val outputStream = clientSocket.getOutputStream()

                // Loop to read multiple events from the same client
                while (isActive && clientSocket.isConnected && !clientSocket.isClosed) {
                    val line = try {
                        reader.readLine() // This will throw SocketTimeoutException if timeout occurs
                    } catch (e: java.net.SocketTimeoutException) {
                        AppLogger.log("Client $clientAddress timed out (idle for 30s). Closing connection.", AppLogger.LogLevel.WARN)
                        break // Exit the loop to close the connection
                    }

                    if (line == null) {
                        AppLogger.log("Client $clientAddress closed connection (readLine returned null).", AppLogger.LogLevel.INFO)
                        break // End of stream, client closed connection
                    }

                    AppLogger.log("RECV LINE from $clientAddress: $line")
                    try {
                        val headerJson = JSONObject(line)
                        val eventType = headerJson.optString("type")
                        // data_length in the header indicates a subsequent data payload
                        val dataLength = headerJson.optInt("data_length", 0)

                        var eventDataJson = headerJson // Assume data is in header if no separate data payload initially

                        if (dataLength > 0) {
                            AppLogger.log("Data payload expected from $clientAddress, length: $dataLength bytes.")
                            val dataChars = CharArray(dataLength)
                            var totalCharsRead = 0
                            var charsReadThisTurn: Int

                            // Loop to ensure all dataLength characters are read, as reader.read might return less
                            while (totalCharsRead < dataLength) {
                                charsReadThisTurn = reader.read(dataChars, totalCharsRead, dataLength - totalCharsRead)
                                if (charsReadThisTurn == -1) {
                                    throw IOException("Unexpected end of stream while reading data payload. Expected $dataLength, got $totalCharsRead")
                                }
                                totalCharsRead += charsReadThisTurn
                            }

                            val dataPayloadString = String(dataChars, 0, totalCharsRead)
                            AppLogger.log("RECV DATA PAYLOAD from $clientAddress ($totalCharsRead chars): $dataPayloadString")
                            try {
                                eventDataJson = JSONObject(dataPayloadString)
                            } catch (e: JSONException) {
                                AppLogger.log("Failed to parse data payload from $clientAddress as JSON: '$dataPayloadString', error: ${e.message}", AppLogger.LogLevel.ERROR)
                                // If payload is critical and unparseable, we might not be able to process the event.
                                // Depending on protocol, might send an error back or just log and continue/break.
                                // For now, let's try to skip to the next line/event.
                                 continue
                            }
                        }

                        // Now use eventDataJson for processing the actual content of the event
                        when (eventType) {
                            "describe" -> {
                                // For 'describe', the main info is usually not in a separate data payload,
                                // but our response generation (getTtsInfoDataJson) creates a data payload to be sent.
                                val infoResponseData = getTtsInfoDataJson()
                                val infoDataBytes = infoResponseData.toString().toByteArray(StandardCharsets.UTF_8)
                                val infoHeader = JSONObject().apply {
                                    put("type", "info")
                                    put("version", WYOMING_PROTOCOL_VERSION)
                                    put("data_length", infoDataBytes.size)
                                }
                                writeInfoEvent(outputStream, infoHeader, infoDataBytes)
                                AppLogger.log("Responded to 'describe' request from $clientAddress with 'info' event.")
                            }
                            "synthesize" -> {
                                val textToSynthesize = eventDataJson.optString("text", "")
                                val voiceInfo = eventDataJson.optJSONObject("voice")
                                val voiceName = voiceInfo?.optString("name")

                                AppLogger.log("Processing Synthesize from $clientAddress: Text='${textToSynthesize}', Voice='${voiceName ?: "default"}'")

                                if (textToSynthesize.isNotEmpty()) {
                                    val utteranceId = UUID.randomUUID().toString()
                                    val deferred = CompletableDeferred<File?>()
                                    ttsUtteranceRequests[utteranceId] = deferred

                                    synthesizeTextToFile(textToSynthesize, utteranceId, voiceName)
                                    val resultFile = deferred.await()

                                    if (resultFile != null) {
                                        streamWavFile(clientSocket, resultFile) // Pass clientSocket
                                    } else {
                                        AppLogger.log("TTS failed to produce a file for $clientAddress, utteranceId $utteranceId", AppLogger.LogLevel.ERROR)
                                        // TODO: Consider sending an error event back to client if protocol supports
                                    }
                                } else {
                                    AppLogger.log("Synthesize request from $clientAddress resulted in empty text.", AppLogger.LogLevel.WARN)
                                }
                            }
                            else -> {
                                if (!eventType.isNullOrEmpty()) {
                                   AppLogger.log("Received unhandled event type '$eventType' from $clientAddress. Full header: $line", AppLogger.LogLevel.WARN)
                                } else if (line.isNotBlank()) { // Client sent something that wasn't JSON or was empty type
                                    AppLogger.log("Received non-empty, non-JSON, or empty-type line from $clientAddress: $line", AppLogger.LogLevel.WARN)
                                }
                            }
                        }
                    } catch (e: JSONException) {
                        AppLogger.log("Error parsing header JSON from $clientAddress (line: '$line'): ${e.message}", AppLogger.LogLevel.ERROR)
                    } catch (e: IOException) {
                        AppLogger.log("IOException processing client data for $clientAddress (line '$line'): ${e.message}", AppLogger.LogLevel.ERROR)
                        break // Break the while loop on significant IO error for this client
                    }
                } // End of while loop reading lines
            } catch (e: java.net.SocketTimeoutException) { // Catch timeout specifically for the outer readLine attempts
                AppLogger.log("Client $clientAddress connection timed out due to inactivity (outer loop). Closing connection.", AppLogger.LogLevel.WARN)
            } catch (e: IOException) {
                if (e.message?.contains("Socket closed", ignoreCase = true) == true ||
                    e.message?.contains("Connection reset", ignoreCase = true) == true ||
                    e.message?.contains("Software caused connection abort", ignoreCase = true) == true ||
                    e.message == null) {
                    AppLogger.log("Client $clientAddress connection closed or stream ended.", AppLogger.LogLevel.INFO)
                } else {
                    AppLogger.log("Outer client $clientAddress connection IO error: ${e.message}", AppLogger.LogLevel.ERROR)
                }
            } finally {
                try {
                    if (!clientSocket.isClosed) {
                        clientSocket.close()
                    }
                } catch (e: IOException) {
                    AppLogger.log("Error closing client socket for $clientAddress in finally: ${e.message}", AppLogger.LogLevel.DEBUG)
                }
                AppLogger.log("Client $clientAddress disconnected and socket closed.")
            }
        }
    }

    private fun getTtsInfoDataJson(): JSONObject {
        // This function now creates the large "data" part of the info event
        val ttsImplInfo = JSONObject()
        ttsImplInfo.put("name", "android_tts")
        ttsImplInfo.put("description", "A local TTS service using the Android OS engine")
        ttsImplInfo.put("attribution", JSONObject().apply {
            put("name", "indigane/wyoming-android-tts")
            put("url", "https://github.com/indigane/wyoming-android-tts")
        })
        ttsImplInfo.put("installed", true)
        ttsImplInfo.put("version", APP_VERSION)

        val voicesArray = JSONArray()
        if (ttsInitialized && tts != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                val availableVoices = tts?.voices?.toList() ?: emptyList()
                for (voice in availableVoices) {
                    val voiceInfo = JSONObject()
                    voiceInfo.put("name", voice.name) // This name should be used as 'id' by client
                    voiceInfo.put("description", "Android System Voice: ${voice.name}")
                    voiceInfo.put("attribution", JSONObject().apply {
                        put("name", "Android OS")
                        put("url", "https://source.android.com/")
                    })
                    voiceInfo.put("installed", true)
                    // For JSONObject.NULL, the compiler needs to know its type.
                    voiceInfo.put("version", JSONObject.NULL as Any?) // Android API doesn't provide voice version
                    voiceInfo.put("languages", JSONArray().put(voice.locale.toLanguageTag()))
                    voiceInfo.put("speakers", JSONObject.NULL as Any?) // Android API doesn't provide speaker info

                    voicesArray.put(voiceInfo)
                }
            } catch (e: Exception) {
                AppLogger.log("Failed to get TTS voices: ${e.message}", AppLogger.LogLevel.ERROR)
            }
        }
        ttsImplInfo.put("voices", voicesArray)

        val ttsArray = JSONArray()
        ttsArray.put(ttsImplInfo)

        // The top-level data object
        val dataObject = JSONObject()
        dataObject.put("tts", ttsArray)
        dataObject.put("asr", JSONArray())
        dataObject.put("handle", JSONArray())
        dataObject.put("intent", JSONArray())
        dataObject.put("wake", JSONArray())
        dataObject.put("mic", JSONArray())
        dataObject.put("snd", JSONArray())

        return dataObject
    }

    private suspend fun writeInfoEvent(stream: OutputStream, header: JSONObject, data: ByteArray) {
        val headerString = header.toString() + "\n"
        stream.write(headerString.toByteArray(StandardCharsets.UTF_8))
        stream.write(data)
        stream.flush()
        AppLogger.log("SENT INFO HEADER: ${header.toString(2)}", AppLogger.LogLevel.DEBUG)
        AppLogger.log("SENT INFO DATA (${data.size} bytes)", AppLogger.LogLevel.DEBUG)
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
                    deferred?.complete(null)
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    onError(utteranceId, -1)
                }
            })
        } catch (e: Exception) {
            AppLogger.log("[TTS] Exception during TTS setup: ${e.message}", AppLogger.LogLevel.ERROR)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            AppLogger.log("[TTS] Engine initialized successfully.", AppLogger.LogLevel.INFO)
            ttsInitialized = true
        } else {
            AppLogger.log("[TTS] Engine initialization failed. Status: $status", AppLogger.LogLevel.ERROR)
            ttsInitialized = false
        }
    }

    private fun synthesizeTextToFile(text: String, utteranceId: String, voiceName: String?) {
        if (!ttsInitialized || tts == null) {
            AppLogger.log("[TTS] Not initialized, cannot synthesize.", AppLogger.LogLevel.ERROR)
            ttsUtteranceRequests.remove(utteranceId)?.complete(null)
            return
        }

        // Select the voice requested by Home Assistant
        if (!voiceName.isNullOrEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val requestedVoice = tts?.voices?.firstOrNull { it.name == voiceName }
            if (requestedVoice != null) {
                // Setting voice is a heavy operation, only do it if it's different
                if (tts?.voice?.name != requestedVoice.name) {
                    val result = tts?.setVoice(requestedVoice)
                    if (result == TextToSpeech.SUCCESS) {
                         AppLogger.log("[TTS] Set voice to: $voiceName", AppLogger.LogLevel.DEBUG)
                    } else {
                         AppLogger.log("[TTS] Failed to set voice to: $voiceName (Error code: $result)", AppLogger.LogLevel.WARN)
                    }
                }
            } else {
                AppLogger.log("[TTS] Requested voice not found: $voiceName. Using current default.", AppLogger.LogLevel.WARN)
            }
        }

        val outputFile = File(cacheDir, "$utteranceId.wav")
        val params = Bundle()
        val result = tts?.synthesizeToFile(text, params, outputFile, utteranceId)

        if (result != TextToSpeech.SUCCESS) {
            AppLogger.log("[TTS] synthesizeToFile call failed immediately for $utteranceId. Result code: $result", AppLogger.LogLevel.ERROR)
            ttsUtteranceRequests.remove(utteranceId)?.complete(null)
        } else {
            AppLogger.log("[TTS] synthesizeToFile call successful for $utteranceId. Waiting for onDone/onError callback.", AppLogger.LogLevel.INFO)
        }
    }

    private suspend fun streamWavFile(socket: Socket, wavFile: File) {
        // For this diagnostic test, we will send audio-start,
        // ONE tiny audio-chunk of silence, then audio-stop.

        // Use parameters consistent with what Android TTS typically produces
        // and what we advertise in audio-start.
        val diagnosticRate = 24000
        val diagnosticWidthBytes = 2 // 16-bit
        val diagnosticChannels = 1

        // Create a tiny PCM payload: 2 bytes of silence for 16-bit mono (1 frame)
        val tinyPcmPayload = ByteArray(2) { 0 } // {0, 0}

        AppLogger.log("DIAGNOSTIC TEST: Sending audio-start, one tiny audio-chunk, then audio-stop.", AppLogger.LogLevel.WARN)

        try {
            val outputStream = socket.getOutputStream()

            // 1. Send audio-start event
            writeAudioStart(outputStream, diagnosticRate, diagnosticWidthBytes, diagnosticChannels)
            AppLogger.log("DIAGNOSTIC: audio-start sent.", AppLogger.LogLevel.DEBUG)

            delay(3000L)

            // 2. Send ONE tiny audio-chunk
            AppLogger.log("DIAGNOSTIC: Sending one tiny audio chunk (2 bytes of silence).", AppLogger.LogLevel.DEBUG)
            writeAudioChunk(
                outputStream,
                tinyPcmPayload,
                tinyPcmPayload.size, // length = 2
                diagnosticRate,
                diagnosticWidthBytes,
                diagnosticChannels
            )
            AppLogger.log("DIAGNOSTIC: Tiny audio-chunk sent.", AppLogger.LogLevel.DEBUG)

            delay(6000L)

            // 3. Send audio-stop event
            writeAudioStop(outputStream)
            AppLogger.log("DIAGNOSTIC: audio-stop sent.", AppLogger.LogLevel.DEBUG)

            AppLogger.log("DIAGNOSTIC TEST: Finished sending minimal stream.", AppLogger.LogLevel.INFO)

        } catch (e: IOException) {
            AppLogger.log("DIAGNOSTIC TEST: Error during minimal stream with tiny chunk: ${e.message}", AppLogger.LogLevel.ERROR)
        } finally {
            // The actual wavFile was not used for streaming in this diagnostic version,
            // but we should still clean it up as it was created by the TTS engine.
            if (wavFile.exists()) {
                if (wavFile.delete()) {
                    AppLogger.log("Deleted temporary TTS file (after diagnostic): ${wavFile.name}", AppLogger.LogLevel.DEBUG)
                } else {
                    AppLogger.log("Failed to delete temporary TTS file (after diagnostic): ${wavFile.name}", AppLogger.LogLevel.WARN)
                }
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
        stream.write(json.toByteArray(StandardCharsets.UTF_8))
        stream.flush()
        AppLogger.log("SENT: ${json.trim()}", AppLogger.LogLevel.DEBUG)
    }

    private suspend fun writeAudioChunk(
        stream: OutputStream,
        data: ByteArray,
        length: Int,
        rate: Int,
        width: Int,
        channels: Int
    ) {
        val jsonHeaderObject = JSONObject().apply {
            put("type", "audio-chunk")
            // Add rate, width, and channels to every chunk header
            put("rate", rate)
            put("width", width)
            put("channels", channels)
            put("payload_length", length)
        }
        val jsonHeaderString = jsonHeaderObject.toString() + "\n"

        stream.write(jsonHeaderString.toByteArray(StandardCharsets.UTF_8))
        if (length > 0) {
            stream.write(data, 0, length)
        }
        stream.flush()

        // Optionally log the header for chunks, can be verbose
        AppLogger.log("SENT: Audio-chunk header: ${jsonHeaderString.trim()} with $length bytes payload", AppLogger.LogLevel.DEBUG)
    }

    private suspend fun writeAudioStop(stream: OutputStream) {
        val json = JSONObject().apply {
            put("type", "audio-stop")
        }.toString() + "\n"
        stream.write(json.toByteArray(StandardCharsets.UTF_8))
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
