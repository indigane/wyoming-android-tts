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
            // Use both a BufferedReader for line-based JSON and the raw InputStream for data payloads
            val inputStream = clientSocket.getInputStream()
            val reader = BufferedReader(InputStreamReader(inputStream, StandardCharsets.UTF_8))
            val outputStream = clientSocket.getOutputStream()

            try {
                while (isActive && clientSocket.isConnected) {
                    val line = reader.readLine() ?: break // End of stream or client disconnected
                    AppLogger.log("RECV LINE: $line")
                    try {
                        val headerJson = JSONObject(line)
                        val eventType = headerJson.optString("type")
                        val dataLength = headerJson.optInt("data_length", 0)

                        var dataJson = headerJson // Assume data is in header if no data_length

                        if (dataLength > 0) {
                            // Read the data payload
                            val dataBytes = ByteArray(dataLength)
                            var bytesRead = 0
                            while (bytesRead < dataLength) {
                                val count = inputStream.read(dataBytes, bytesRead, dataLength - bytesRead)
                                if (count == -1) throw IOException("Unexpected end of stream while reading data payload.")
                                bytesRead += count
                            }
                            val dataPayloadString = String(dataBytes, StandardCharsets.UTF_8)
                            AppLogger.log("RECV DATA PAYLOAD: $dataPayloadString")
                            dataJson = JSONObject(dataPayloadString) // This is the actual event data
                        }

                        when (eventType) {
                            "describe" -> {
                                val infoResponseData = getTtsInfoDataJson()
                                val infoDataBytes = infoResponseData.toString().toByteArray(StandardCharsets.UTF_8)
                                val infoHeader = JSONObject().apply {
                                    put("type", "info")
                                    put("version", WYOMING_PROTOCOL_VERSION)
                                    put("data_length", infoDataBytes.size)
                                }
                                writeInfoEvent(outputStream, infoHeader, infoDataBytes)
                                AppLogger.log("Responded to 'describe' request with two-part 'info' event.")
                            }
                            "synthesize" -> {
                                // Text and voice details should be in dataJson now
                                val textToSynthesize = dataJson.optString("text", "")
                                val voiceInfo = dataJson.optJSONObject("voice")
                                val voiceName = voiceInfo?.optString("name")
                                
                                AppLogger.log("Processing Synthesize: Text='${textToSynthesize}', Voice='${voiceName ?: "default"}'")

                                if (textToSynthesize.isNotEmpty()) {
                                    val utteranceId = UUID.randomUUID().toString()
                                    val deferred = CompletableDeferred<File?>()
                                    ttsUtteranceRequests[utteranceId] = deferred

                                    synthesizeTextToFile(textToSynthesize, utteranceId, voiceName)
                                    val resultFile = deferred.await()

                                    if (resultFile != null) {
                                        streamWavFile(clientSocket, resultFile)
                                    } else {
                                        AppLogger.log("TTS failed to produce a file for utteranceId $utteranceId", AppLogger.LogLevel.ERROR)
                                    }
                                } else {
                                    AppLogger.log("Synthesize request with empty text after processing.", AppLogger.LogLevel.WARN)
                                }
                            }
                            else -> AppLogger.log("Received unknown event type: $eventType", AppLogger.LogLevel.WARN)
                        }
                    } catch (e: JSONException) {
                        AppLogger.log("Error parsing JSON from client (line: '$line'): ${e.message}", AppLogger.LogLevel.ERROR)
                    }
                }
            } catch (e: IOException) {
                // This can happen if the client disconnects abruptly, or readLine returns null and loop breaks
                if (e.message?.contains("Socket closed") == true || e.message?.contains("Connection reset") == true || e.message == null) {
                    AppLogger.log("Client connection closed or stream ended.", AppLogger.LogLevel.INFO)
                } else {
                    AppLogger.log("Client connection IO error: ${e.message}", AppLogger.LogLevel.ERROR)
                }
            } finally {
                try {
                    clientSocket.close()
                } catch (e: IOException) { /* Ignore */ }
                AppLogger.log("Client disconnected and socket closed: ${clientSocket.inetAddress?.hostAddress}")
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
        stream.write(json.toByteArray(StandardCharsets.UTF_8))
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
            stream.write(json.toByteArray(StandardCharsets.UTF_8))
            stream.flush()
            AppLogger.log("SENT: Final empty audio-chunk", AppLogger.LogLevel.DEBUG)
            return
        }

        val json = JSONObject().apply {
            put("type", "audio-chunk")
            put("payload_length", length)
        }.toString() + "\n"
        stream.write(json.toByteArray(StandardCharsets.UTF_8))
        stream.write(data, 0, length)
        stream.flush()
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
