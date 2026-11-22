package com.voicereply.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.view.View
import android.webkit.MimeTypeMap
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * One screen: record (or pick / share) German audio, send it to the server,
 * show what was heard + the German reply, and play the reply in your voice.
 */
class MainActivity : AppCompatActivity() {

    private data class Turn(val role: String, val text: String)

    private class ServerReply(val transcript: String, val reply: String, val audio: ByteArray)

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private val history = mutableListOf<Turn>()
    private var recorder: MediaRecorder? = null
    private var player: MediaPlayer? = null
    private var busy = false

    private lateinit var recordingFile: File
    private lateinit var replyFile: File

    private lateinit var statusText: TextView
    private lateinit var conversationText: TextView
    private lateinit var conversationScroll: ScrollView
    private lateinit var recordButton: Button
    private lateinit var pickButton: Button
    private lateinit var replayButton: Button
    private lateinit var newChatButton: Button
    private lateinit var settingsPanel: LinearLayout
    private lateinit var serverUrlInput: EditText
    private lateinit var tokenInput: EditText

    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }

    private val askMicPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startRecording()
            else setStatus("The app needs microphone permission to record you.")
        }

    private val pickAudio =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) sendUri(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        conversationText = findViewById(R.id.conversationText)
        conversationScroll = findViewById(R.id.conversationScroll)
        recordButton = findViewById(R.id.recordButton)
        pickButton = findViewById(R.id.pickButton)
        replayButton = findViewById(R.id.replayButton)
        newChatButton = findViewById(R.id.newChatButton)
        settingsPanel = findViewById(R.id.settingsPanel)
        serverUrlInput = findViewById(R.id.serverUrlInput)
        tokenInput = findViewById(R.id.tokenInput)
        applySystemBarPadding()

        recordingFile = File(cacheDir, "recording.m4a")
        replyFile = File(cacheDir, "reply.mp3")

        serverUrlInput.setText(serverUrl())
        tokenInput.setText(appToken())
        if (serverUrl().isEmpty()) {
            settingsPanel.visibility = View.VISIBLE
            setStatus("First, enter your server URL and app token.")
        }

        findViewById<Button>(R.id.settingsButton).setOnClickListener {
            settingsPanel.visibility =
                if (settingsPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        findViewById<Button>(R.id.saveButton).setOnClickListener { saveSettings() }
        recordButton.setOnClickListener {
            if (recorder == null) onRecordPressed() else stopRecordingAndSend()
        }
        pickButton.setOnClickListener { pickAudio.launch("audio/*") }
        replayButton.setOnClickListener { play(replyFile) }
        newChatButton.setOnClickListener {
            history.clear()
            stopPlayback()
            renderConversation()
            updateButtons()
            setStatus("New conversation started.")
        }

        savedInstanceState?.getString(KEY_HISTORY)?.let { restoreHistory(it) }
        renderConversation()
        updateButtons()

        // Audio shared from another app (only on first start, not on re-creation).
        if (savedInstanceState == null) handleShareIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShareIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_HISTORY, historyJson())
    }

    override fun onStop() {
        super.onStop()
        // Android does not allow recording in the background, so cancel it.
        if (recorder != null) {
            releaseRecorder()
            setStatus("Recording cancelled.")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseRecorder()
        stopPlayback()
    }

    // ---- Recording -----------------------------------------------------------

    private fun onRecordPressed() {
        if (busy) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) startRecording() else askMicPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun startRecording() {
        stopPlayback()
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(this)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(44_100)
            r.setAudioEncodingBitRate(96_000)
            r.setOutputFile(recordingFile.absolutePath)
            r.prepare()
            r.start()
        } catch (e: Exception) {
            r.release()
            setStatus("Could not start recording: ${e.message}")
            return
        }
        recorder = r
        updateButtons()
        setStatus("🔴 Recording… speak German, then tap \"Stop and send\".")
    }

    private fun stopRecordingAndSend() {
        val r = recorder ?: return
        recorder = null
        // stop() throws if almost nothing was recorded.
        val ok = try {
            r.stop()
            true
        } catch (e: RuntimeException) {
            false
        }
        r.release()
        updateButtons()
        if (!ok || !recordingFile.exists() || recordingFile.length() == 0L) {
            setStatus("Recording was too short. Try again.")
            return
        }
        send(recordingFile.readBytes(), "recording.m4a", "audio/mp4")
    }

    private fun releaseRecorder() {
        recorder?.let { r ->
            try {
                r.stop()
            } catch (e: RuntimeException) {
                // nothing was recorded yet; ignore
            }
            r.release()
        }
        recorder = null
        updateButtons()
    }

    // ---- Audio from a file or another app --------------------------------------

    private fun handleShareIntent(intent: Intent?) {
        if (intent == null || intent.action != Intent.ACTION_SEND) return
        val uri = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java) ?: return
        intent.action = null // handle it only once
        sendUri(uri)
    }

    private fun sendUri(uri: Uri) {
        if (busy || recorder != null) return
        lifecycleScope.launch {
            val bytes = try {
                withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use { it.readBytes() }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            when {
                bytes == null || bytes.isEmpty() -> setStatus("Could not read that audio file.")
                bytes.size > MAX_UPLOAD_BYTES -> setStatus("That file is too big (max 25 MB).")
                else -> {
                    val mime = contentResolver.getType(uri) ?: "application/octet-stream"
                    val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "bin"
                    send(bytes, "audio.$ext", mime)
                }
            }
        }
    }

    // ---- Talking to the server ---------------------------------------------------

    private fun send(bytes: ByteArray, fileName: String, mime: String) {
        val url = serverUrl()
        if (url.isEmpty()) {
            settingsPanel.visibility = View.VISIBLE
            setStatus("Enter your server URL in Settings first.")
            return
        }
        busy = true
        updateButtons()
        setStatus("⏳ Listening and thinking…")
        val historyJson = historyJson()

        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val r = postAudio(url, bytes, fileName, mime, historyJson)
                    replyFile.writeBytes(r.audio)
                    r
                }
                history.add(Turn("user", result.transcript))
                history.add(Turn("assistant", result.reply))
                renderConversation()
                setStatus("🔊 Playing the reply.")
                play(replyFile)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setStatus("❌ ${e.message ?: e.javaClass.simpleName}")
            } finally {
                busy = false
                updateButtons()
            }
        }
    }

    private fun postAudio(
        baseUrl: String,
        bytes: ByteArray,
        fileName: String,
        mime: String,
        historyJson: String,
    ): ServerReply {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("audio", fileName, bytes.toRequestBody(mime.toMediaTypeOrNull()))
            .addFormDataPart("history", historyJson)
            .build()
        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/reply")
            .apply { if (appToken().isNotEmpty()) header("X-App-Token", appToken()) }
            .post(body)
            .build()

        return http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val detail = runCatching { JSONObject(text).optString("detail") }.getOrNull()
                throw IOException(
                    if (detail.isNullOrBlank()) "Server error (HTTP ${response.code})" else detail
                )
            }
            val json = JSONObject(text)
            ServerReply(
                transcript = json.getString("transcript"),
                reply = json.getString("reply"),
                audio = Base64.decode(json.getString("audio_base64"), Base64.DEFAULT),
            )
        }
    }

    private fun checkServer() {
        val url = serverUrl()
        if (url.isEmpty()) return
        setStatus("Checking the server…")
        lifecycleScope.launch {
            val message = try {
                withContext(Dispatchers.IO) {
                    val request = Request.Builder().url(url.trimEnd('/') + "/health").build()
                    http.newCall(request).execute().use { r ->
                        if (!r.isSuccessful) {
                            "⚠ Server answered with HTTP ${r.code}."
                        } else {
                            val j = JSONObject(r.body?.string().orEmpty())
                            val missing = listOf(
                                "anthropic_key_set" to "Anthropic API key",
                                "elevenlabs_key_set" to "ElevenLabs API key",
                                "voice_id_set" to "voice ID",
                            ).filter { !j.optBoolean(it.first) }.map { it.second }
                            if (missing.isEmpty()) "✅ Connected. The server is ready."
                            else "⚠ Connected, but the server is missing: ${missing.joinToString()}."
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "❌ Can't reach the server: ${e.message}"
            }
            setStatus(message)
        }
    }

    // ---- Playback ----------------------------------------------------------------

    private fun play(file: File) {
        if (!file.exists()) return
        stopPlayback()
        try {
            player = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnCompletionListener { stopPlayback() }
                prepare()
                start()
            }
        } catch (e: Exception) {
            stopPlayback()
            setStatus("Could not play the reply: ${e.message}")
        }
    }

    private fun stopPlayback() {
        player?.release()
        player = null
    }

    // ---- UI helpers --------------------------------------------------------------

    private fun updateButtons() {
        val recording = recorder != null
        recordButton.text = getString(if (recording) R.string.stop_and_send else R.string.record)
        recordButton.isEnabled = !busy
        pickButton.isEnabled = !busy && !recording
        newChatButton.isEnabled = !busy && !recording
        replayButton.isEnabled = !busy && !recording && history.isNotEmpty() && replyFile.exists()
    }

    private fun renderConversation() {
        conversationText.text = if (history.isEmpty()) {
            getString(R.string.empty_conversation)
        } else {
            history.joinToString("\n\n") { turn ->
                (if (turn.role == "user") "🗣 You: " else "🔊 Reply: ") + turn.text
            }
        }
        conversationScroll.post { conversationScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun setStatus(message: String) {
        statusText.text = message
    }

    private fun applySystemBarPadding() {
        val root = findViewById<View>(R.id.root)
        val base = root.paddingLeft
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
            )
            v.setPadding(base + bars.left, base + bars.top, base + bars.right, base + bars.bottom)
            insets
        }
    }

    // ---- Settings & state ----------------------------------------------------------

    private fun serverUrl(): String = prefs.getString(KEY_URL, null).orEmpty().trim()

    private fun appToken(): String = prefs.getString(KEY_TOKEN, null).orEmpty()

    private fun saveSettings() {
        var url = serverUrlInput.text.toString().trim()
        if (url.isNotEmpty() && !url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://$url"
        }
        serverUrlInput.setText(url)
        prefs.edit()
            .putString(KEY_URL, url)
            .putString(KEY_TOKEN, tokenInput.text.toString().trim())
            .apply()
        settingsPanel.visibility = View.GONE
        Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
        checkServer()
    }

    private fun historyJson(): String {
        val array = JSONArray()
        history.forEach { array.put(JSONObject().put("role", it.role).put("text", it.text)) }
        return array.toString()
    }

    private fun restoreHistory(json: String) {
        runCatching {
            val array = JSONArray(json)
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                history.add(Turn(o.getString("role"), o.getString("text")))
            }
        }
    }

    private companion object {
        const val KEY_URL = "server_url"
        const val KEY_TOKEN = "app_token"
        const val KEY_HISTORY = "history"
        const val MAX_UPLOAD_BYTES = 25 * 1024 * 1024
    }
}
