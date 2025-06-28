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
import androidx.preference.PreferenceManager
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
        // Default values, will be overridden by preferences
        private const val DEFAULT_SERVER_PORT = 10300
        private const val DEFAULT_SERVICE_NAME = "Wyoming Android TTS"
        private const val WYOMING_PROTOCOL_VERSION = "1.6.0"
        private const val APP_VERSION = "1.0.0"
    }
    private var serverSocket: ServerSocket? = null
    private var currentPort: Int = DEFAULT_SERVER_PORT
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private var serverListeningJob: Job? = null
    private var tts: TextToSpeech? = null
    private var ttsInitialized = false
    private val ttsUtteranceRequests = mutableMapOf<String, CompletableDeferred<File?>>()
    private data class WavInfo(val sampleRate: Int, val channels: Int, val bitsPerSample: Int)

    private lateinit var wyomingDiscoveryManager: WyomingDiscoveryManager

    private fun readLineFromStream(inputStream: InputStream, clientAddress: String): String? {
        val lineBuffer = java.io.ByteArrayOutputStream()
        var byteRead: Int = 0 // Initialize byteRead here
        var bytesInLine = 0

        while (bytesInLine < 8192) { // Safety limit for a single line (max header size)
            byteRead = inputStream.read()
            if (byteRead == -1) {
                if (bytesInLine == 0) return null
                AppLogger.log("readLineFromStream: EOF on $clientAddress after $bytesInLine bytes without newline. Processing as line.", AppLogger.LogLevel.WARN)
                break
            }
            bytesInLine++
            lineBuffer.write(byteRead)
            if (byteRead == '\n'.code) {
                break
            }
        }
        // Check if the loop exited due to line being too long AND didn't end with a newline
        if (bytesInLine >= 8192 && byteRead != '\n'.code) {
            throw IOException("Line too long from $clientAddress (>$bytesInLine bytes without newline)")
        }
        if (lineBuffer.size() == 0) return null

        return String(lineBuffer.toByteArray(), StandardCharsets.UTF_8).trimEnd()
    }

    private suspend fun handleClient(clientSocket: Socket) {
        withContext(Dispatchers.IO) {
            val clientAddress = clientSocket.inetAddress?.hostAddress ?: "unknown"
            AppLogger.log("ProdHandleClient: Handling client: $clientAddress")

            try {
                clientSocket.soTimeout = 30000
                clientSocket.tcpNoDelay = true

                val inputStream = clientSocket.getInputStream()
                val outputStream = clientSocket.getOutputStream()

                while (isActive && clientSocket.isConnected && !clientSocket.isClosed) {
                    val headerLine = try {
                        readLineFromStream(inputStream, clientAddress)
                    } catch (e: java.net.SocketTimeoutException) {
                        AppLogger.log("ProdHandleClient: Timeout waiting for header line from $clientAddress. Closing.", AppLogger.LogLevel.WARN)
                        break
                    }

                    if (headerLine == null) {
                        AppLogger.log("ProdHandleClient: Client $clientAddress closed connection or stream ended (null header line).", AppLogger.LogLevel.INFO)
                        break
                    }
                    AppLogger.log("ProdHandleClient: RECV HEADER LINE: '$headerLine'")

                    var eventType: String?
                    var dataLength: Int
                    var eventDataJson: JSONObject?

                    try {
                        val headerJson = JSONObject(headerLine)
                        eventType = headerJson.optString("type")
                        dataLength = headerJson.optInt("data_length", 0)
                        AppLogger.log("ProdHandleClient: Parsed Header: type='$eventType', data_length=$dataLength")
                        eventDataJson = headerJson
                    } catch (e: JSONException) {
                        AppLogger.log("ProdHandleClient: Error parsing header JSON from $clientAddress ('$headerLine'): ${e.message}", AppLogger.LogLevel.ERROR)
                        break
                    }

                    if (dataLength > 0) {
                        AppLogger.log("ProdHandleClient: Data payload expected from $clientAddress, length: $dataLength bytes.")
                        val dataPayloadBytes = ByteArray(dataLength)
                        var totalPayloadBytesRead = 0

                        try {
                            while (totalPayloadBytesRead < dataLength) {
                                val bytesReadThisTurn = inputStream.read(dataPayloadBytes, totalPayloadBytesRead, dataLength - totalPayloadBytesRead)
                                if (bytesReadThisTurn == -1) {
                                    throw IOException("Unexpected EOF in data payload from $clientAddress. Read $totalPayloadBytesRead/$dataLength bytes.")
                                }
                                totalPayloadBytesRead += bytesReadThisTurn
                            }
                            AppLogger.log("ProdHandleClient: Successfully read $totalPayloadBytesRead bytes for data payload.")
                            // Hex logging for dataPayloadBytes is removed here.

                            val dataPayloadString = String(dataPayloadBytes, StandardCharsets.UTF_8)
                            AppLogger.log("ProdHandleClient: RECV DATA PAYLOAD STRING: $dataPayloadString")

                            try {
                                eventDataJson = JSONObject(dataPayloadString)
                            } catch (e: JSONException) {
                                 AppLogger.log("ProdHandleClient: Failed to parse data payload as JSON: '$dataPayloadString', error: ${e.message}", AppLogger.LogLevel.ERROR)
                                 eventType = null
                            }
                        } catch (e: java.net.SocketTimeoutException) {
                             AppLogger.log("ProdHandleClient: Timeout reading data payload from $clientAddress after $totalPayloadBytesRead/$dataLength bytes.", AppLogger.LogLevel.ERROR)
                             eventType = null
                        } catch (e: IOException) {
                            AppLogger.log("ProdHandleClient: IOException during data payload read for $clientAddress ($totalPayloadBytesRead/$dataLength): ${e.message}", AppLogger.LogLevel.ERROR)
                            eventType = null
                        }
                    }

                    if (eventType != null && eventDataJson != null) {
                        when (eventType) {
                            "describe" -> {
                                val infoResponseData = getTtsInfoDataJson()
                                val infoDataBytes = infoResponseData.toString().toByteArray(StandardCharsets.UTF_8)
                                val infoHeader = JSONObject().apply {
                                    put("type", "info"); put("version", WYOMING_PROTOCOL_VERSION); put("data_length", infoDataBytes.size)
                                }
                                writeInfoEvent(outputStream, infoHeader, infoDataBytes)
                                AppLogger.log("ProdHandleClient: Responded to 'describe' from $clientAddress.")
                            }
                            "synthesize" -> {
                                val textToSynthesize = eventDataJson.optString("text", "")
                                val voiceInfo = eventDataJson.optJSONObject("voice")
                                val voiceName = voiceInfo?.optString("name")
                                AppLogger.log("ProdHandleClient: Processing Synthesize: Text='${textToSynthesize}', Voice='${voiceName ?: "default"}'")
                                if (textToSynthesize.isNotEmpty()) {
                                    val utteranceId = UUID.randomUUID().toString()
                                    val deferred = CompletableDeferred<File?>()
                                    ttsUtteranceRequests[utteranceId] = deferred
                                    synthesizeTextToFile(textToSynthesize, utteranceId, voiceName)
                                    val resultFile = deferred.await()
                                    if (resultFile != null) streamWavFile(clientSocket, resultFile)
                                    else AppLogger.log("ProdHandleClient: TTS failed for $clientAddress, $utteranceId", AppLogger.LogLevel.ERROR)
                                } else AppLogger.log("ProdHandleClient: Synthesize from $clientAddress with empty text.", AppLogger.LogLevel.WARN)
                            }
                            else -> AppLogger.log("ProdHandleClient: Unhandled type '$eventType' from $clientAddress.", AppLogger.LogLevel.WARN)
                        }
                    } else {
                         AppLogger.log("ProdHandleClient: Event processing skipped for $clientAddress due to error or null event type/data.", AppLogger.LogLevel.WARN)
                    }
                }
            } catch (e: java.net.SocketTimeoutException) {
                AppLogger.log("ProdHandleClient: Client $clientAddress connection overall timeout (no activity for 30s). Closing.", AppLogger.LogLevel.WARN)
            } catch (e: IOException) {
                if (e.message?.contains("Socket closed", ignoreCase = true) == true ||
                    e.message?.contains("Connection reset", ignoreCase = true) == true ||
                    e.message == "Connection reset by peer" ||
                    e.message?.contains("Software caused connection abort", ignoreCase = true) == true) {
                    AppLogger.log("ProdHandleClient: Client $clientAddress connection closed by peer or reset.", AppLogger.LogLevel.INFO)
                } else {
                    AppLogger.log("ProdHandleClient: Outer connection IO error for $clientAddress: ${e.message}", AppLogger.LogLevel.ERROR)
                }
            } catch (e: Exception) {
                AppLogger.log("ProdHandleClient: Unexpected error handling client $clientAddress: ${e.message} (Type: ${e.javaClass.simpleName})", AppLogger.LogLevel.ERROR)
            }
            finally {
                try {
                    if (!clientSocket.isClosed) clientSocket.close()
                } catch (e: IOException) { AppLogger.log("ProdHandleClient: Error closing socket for $clientAddress: ${e.message}", AppLogger.LogLevel.DEBUG) }
                AppLogger.log("ProdHandleClient: Connection closed for $clientAddress.")
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
        wyomingDiscoveryManager = WyomingDiscoveryManager(applicationContext)
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
                // Consider sending an error event back to HA if protocol supports
                return
            }
            AppLogger.log("[TTS] Parsed WAV: ${wavInfo.sampleRate} Hz, ${wavInfo.bitsPerSample}-bit, ${wavInfo.channels} channels", AppLogger.LogLevel.INFO)

            val outputStream = socket.getOutputStream()
            val audioWidthBytes = wavInfo.bitsPerSample / 8

            // 1. Send audio-start event
            writeAudioStart(outputStream, wavInfo.sampleRate, audioWidthBytes, wavInfo.channels)

            // 2. Send audio-chunk events
            val buffer = ByteArray(4096)
            var bytesRead: Int
            while (fileInputStream.read(buffer).also { bytesRead = it } != -1) {
                if (bytesRead > 0) {
                    writeAudioChunk(outputStream, buffer, bytesRead, wavInfo.sampleRate, audioWidthBytes, wavInfo.channels)
                }
            }

            // 3. Send audio-stop event
            writeAudioStop(outputStream)

            AppLogger.log("Finished streaming ${wavFile.name} to client.", AppLogger.LogLevel.INFO)

        } catch (e: IOException) {
            AppLogger.log("Error streaming WAV file: ${e.message}", AppLogger.LogLevel.ERROR)
            // TODO: Consider sending an error event to HA if connection is still alive
        } finally {
            try {
                fileInputStream?.close()
            } catch (e: IOException) {
                AppLogger.log("Error closing file input stream: ${e.message}", AppLogger.LogLevel.DEBUG)
            }
            if (wavFile.exists()) {
                if (wavFile.delete()) {
                    AppLogger.log("Deleted temporary TTS file: ${wavFile.name}", AppLogger.LogLevel.DEBUG)
                } else {
                    AppLogger.log("Failed to delete temporary TTS file: ${wavFile.name}", AppLogger.LogLevel.WARN)
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
        // Data part of the audio-start event
        val dataJson = JSONObject().apply {
            put("rate", rate)
            put("width", width)
            put("channels", channels)
        }
        val dataBytes = dataJson.toString().toByteArray(StandardCharsets.UTF_8)

        // Header part of the audio-start event
        val headerJson = JSONObject().apply {
            put("type", "audio-start")
            put("version", WYOMING_PROTOCOL_VERSION)
            put("data_length", dataBytes.size)
        }
        val headerString = headerJson.toString() + "\n"

        stream.write(headerString.toByteArray(StandardCharsets.UTF_8))
        stream.write(dataBytes)
        stream.flush()
        AppLogger.log("SENT AudioStart Header: ${headerJson.toString(2)}", AppLogger.LogLevel.DEBUG)
        AppLogger.log("SENT AudioStart Data: ${dataJson.toString(2)}", AppLogger.LogLevel.DEBUG)
    }

    private suspend fun writeAudioChunk(
        stream: OutputStream,
        pcmData: ByteArray,
        pcmLength: Int,
        rate: Int,
        width: Int,
        channels: Int
    ) {
        // Data part of the audio-chunk event (metadata)
        val dataJson = JSONObject().apply {
            put("rate", rate)
            put("width", width)
            put("channels", channels)
        }
        val dataBytes = dataJson.toString().toByteArray(StandardCharsets.UTF_8)

        // Header part of the audio-chunk event
        val headerJson = JSONObject().apply {
            put("type", "audio-chunk")
            put("version", WYOMING_PROTOCOL_VERSION)
            put("data_length", dataBytes.size)
            put("payload_length", pcmLength)
        }
        val headerString = headerJson.toString() + "\n"

        stream.write(headerString.toByteArray(StandardCharsets.UTF_8))
        stream.write(dataBytes)
        if (pcmLength > 0) {
            stream.write(pcmData, 0, pcmLength)
        }
        stream.flush()

        if (pcmLength == 0) {
            AppLogger.log("SENT Empty AudioChunk (should be rare): Header: ${headerJson.toString(2)}, Data: ${dataJson.toString(2)}", AppLogger.LogLevel.DEBUG)
        } else {
            AppLogger.log("SENT AudioChunk: Header: ${headerJson.toString(2)}, Data: ${dataJson.toString(2)}, PCM Payload: $pcmLength bytes", AppLogger.LogLevel.DEBUG)
        }
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
        loadPreferences() // Load preferences before starting server and notification

        val notification: Notification = NotificationCompat.Builder(this, WyomingTtsApp.CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_message_listening, currentPort))
            .setSmallIcon(R.mipmap.ic_launcher)
            .build()
        startForeground(NOTIFICATION_ID, notification)
        startServer()
        return START_STICKY
    }

    private fun loadPreferences() {
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(applicationContext)
        currentPort = sharedPreferences.getString("service_port", DEFAULT_SERVER_PORT.toString())?.toIntOrNull() ?: DEFAULT_SERVER_PORT
        // Service name will be read directly in startServer when registering
        AppLogger.log("Loaded preferences: Port=$currentPort")
    }

    private fun startServer() {
        if (serverListeningJob?.isActive == true) {
            AppLogger.log("Server already running on port $currentPort")
            return
        }
        serverListeningJob = serviceScope.launch {
            try {
                serverSocket = ServerSocket(currentPort)
                AppLogger.log("Server started, listening on port $currentPort")

                val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(applicationContext)
                val serviceName = sharedPreferences.getString("service_name", DEFAULT_SERVICE_NAME) ?: DEFAULT_SERVICE_NAME
                wyomingDiscoveryManager.registerService(serviceName, currentPort)

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
                AppLogger.log("Could not start server on port $currentPort: ${e.message}", AppLogger.LogLevel.ERROR)
            } finally {
                AppLogger.log("Server listening loop ended.", AppLogger.LogLevel.WARN)
                serverSocket?.close()
            }
        }
    }

    private fun stopServer() {
        AppLogger.log("Stopping server...")
        wyomingDiscoveryManager.unregisterService()
        serverListeningJob?.cancel()
        // serviceJob.cancel() // Cancelling serviceJob here might be too early if unregisterService relies on it or if other coroutines use it.
                           // NsdManager operations are asynchronous. Consider if serviceJob needs to be active for callbacks.
                           // For now, assume NsdManager handles its own threading for unregistration.
        try {
            serverSocket?.close()
            serverSocket = null
            AppLogger.log("Server socket closed.")
        } catch (e: IOException) {
            AppLogger.log("Error closing server socket: ${e.message}", AppLogger.LogLevel.ERROR)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        AppLogger.log("Service destroying...")
        stopServer() // This now calls unregisterService()
        if (tts != null) {
            AppLogger.log("[TTS] Shutting down TextToSpeech engine.", AppLogger.LogLevel.INFO)
            tts?.stop()
            tts?.shutdown()
            tts = null
            ttsInitialized = false
        }
        serviceJob.cancel() // Cancel the main service job as one of the last steps
        AppLogger.log("Service destroyed.")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
