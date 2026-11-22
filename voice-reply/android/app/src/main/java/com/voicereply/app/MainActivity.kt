package com.voicereply.app

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Everything happens on the phone, no accounts or keys:
 * phone speech recognition (German) -> offline AI -> phone's German voice.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var models: ModelStore
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var listening = false
    private var thinking = false
    private var downloadWatcher: Job? = null

    private lateinit var statusText: TextView
    private lateinit var conversationText: TextView
    private lateinit var conversationScroll: ScrollView
    private lateinit var talkButton: Button
    private lateinit var replayButton: Button
    private lateinit var newChatButton: Button
    private lateinit var setupPanel: LinearLayout
    private lateinit var setupText: TextView
    private lateinit var setupButton: Button
    private lateinit var progressBar: LinearProgressIndicator
    private lateinit var voiceButton: Button

    private val askMicPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startListening()
            else setStatus("The app needs microphone permission to hear you.")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        conversationText = findViewById(R.id.conversationText)
        conversationScroll = findViewById(R.id.conversationScroll)
        talkButton = findViewById(R.id.talkButton)
        replayButton = findViewById(R.id.replayButton)
        newChatButton = findViewById(R.id.newChatButton)
        setupPanel = findViewById(R.id.setupPanel)
        setupText = findViewById(R.id.setupText)
        setupButton = findViewById(R.id.setupButton)
        progressBar = findViewById(R.id.progressBar)
        voiceButton = findViewById(R.id.voiceButton)
        applySystemBarPadding()

        models = ModelStore(this)

        talkButton.setOnClickListener { if (listening) recognizer?.stopListening() else onTalkPressed() }
        replayButton.setOnClickListener { lastReply()?.let { speak(it) } }
        newChatButton.setOnClickListener {
            lifecycleScope.launch {
                tts?.stop()
                LlmEngine.newConversation()
                renderConversation()
                setStatus("New conversation started.")
            }
        }
        setupButton.setOnClickListener {
            models.startDownload()
            watchDownload()
        }
        voiceButton.setOnClickListener { installGermanVoice() }

        tts = TextToSpeech(this) { status -> onTtsReady(status == TextToSpeech.SUCCESS) }

        renderConversation()
        prepareAi()
    }

    override fun onStop() {
        super.onStop()
        if (listening) recognizer?.cancel()
        listening = false
        updateButtons()
    }

    override fun onDestroy() {
        super.onDestroy()
        recognizer?.destroy()
        tts?.shutdown()
    }

    // ---- Step 1: make sure the AI model is on the phone and loaded -----------

    private fun prepareAi() {
        when {
            LlmEngine.isLoaded -> {
                setupPanel.visibility = View.GONE
                setStatus("Ready. Tap the button and speak German.")
                updateButtons()
            }
            models.isReady() -> loadAi()
            models.isDownloading() -> watchDownload()
            else -> showSetup(
                "One-time setup: the app needs to download its offline AI " +
                    "(${ModelStore.MODEL_NAME}, ${ModelStore.MODEL_SIZE_TEXT}). " +
                    "Wi-Fi is best. After that it works without any account."
            )
        }
    }

    private fun showSetup(message: String) {
        setupPanel.visibility = View.VISIBLE
        setupText.text = message
        setupButton.visibility = View.VISIBLE
        progressBar.visibility = View.GONE
        setStatus("")
        updateButtons()
    }

    private fun watchDownload() {
        setupPanel.visibility = View.VISIBLE
        setupButton.visibility = View.GONE
        progressBar.visibility = View.VISIBLE
        setupText.text = "Downloading the offline AI… You can leave the app; it keeps downloading."
        downloadWatcher?.cancel()
        downloadWatcher = lifecycleScope.launch {
            while (true) {
                when (val p = models.poll()) {
                    is ModelStore.Progress.Running -> {
                        if (p.total > 0) {
                            progressBar.isIndeterminate = false
                            progressBar.setProgressCompat((p.done * 100 / p.total).toInt(), true)
                            setStatus("Downloaded ${p.done / 1_000_000} of ${p.total / 1_000_000} MB")
                        } else {
                            progressBar.isIndeterminate = true
                            setStatus("Starting download…")
                        }
                    }
                    ModelStore.Progress.Waiting -> setStatus("Waiting for internet…")
                    ModelStore.Progress.Done -> {
                        loadAi()
                        return@launch
                    }
                    is ModelStore.Progress.Failed -> {
                        showSetup("❌ ${p.reason}")
                        setupButton.text = getString(R.string.retry_download)
                        return@launch
                    }
                }
                delay(700)
            }
        }
    }

    private fun loadAi() {
        setupPanel.visibility = View.VISIBLE
        setupButton.visibility = View.GONE
        progressBar.visibility = View.VISIBLE
        progressBar.isIndeterminate = true
        setupText.text = "Starting the offline AI…"
        setStatus("")
        lifecycleScope.launch {
            try {
                LlmEngine.load(applicationContext, models.modelFile)
                setupPanel.visibility = View.GONE
                setStatus("Ready. Tap the button and speak German.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                showSetup("❌ ${e.message ?: "The AI could not start."}")
                setupButton.text = getString(R.string.retry_download)
            }
            updateButtons()
        }
    }

    // ---- Step 2: listen (phone's own speech recognition, German) --------------

    private fun onTalkPressed() {
        if (thinking || !LlmEngine.isLoaded) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) startListening() else askMicPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            setStatus("This phone has no speech recognition. Install or update the Google app, then try again.")
            return
        }
        tts?.stop()
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(this).also {
            it.setRecognitionListener(listener)
            recognizer = it
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "de-DE")
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "de-DE")
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        listening = true
        updateButtons()
        setStatus("🎤 Listening… speak German.")
        r.startListening(intent)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {
            setStatus("…")
        }
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (!text.isNullOrBlank()) setStatus("🎤 $text")
        }

        override fun onResults(results: Bundle?) {
            listening = false
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()
            if (text.isNullOrEmpty()) {
                setStatus("I didn't catch that. Tap and try again.")
                updateButtons()
            } else {
                answer(text)
            }
        }

        override fun onError(error: Int) {
            listening = false
            updateButtons()
            setStatus(
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                        "I didn't catch that. Tap and try again."
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                    SpeechRecognizer.ERROR_SERVER ->
                        "Speech recognition needs internet (or the offline German speech pack in your phone's settings)."
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                        "The app needs microphone permission."
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
                        "Speech recognition is busy. Try again in a moment."
                    12, 13 -> // ERROR_LANGUAGE_NOT_SUPPORTED, ERROR_LANGUAGE_UNAVAILABLE (Android 12+)
                        "German speech recognition isn't installed. Add German in your phone's " +
                            "Google voice typing settings, then try again."
                    else -> "Speech recognition error ($error). Tap and try again."
                }
            )
        }
    }

    // ---- Step 3: think (offline AI) and speak (phone's German voice) ----------

    private fun answer(userText: String) {
        thinking = true
        updateButtons()
        LlmEngine.conversation.add(true to userText)
        renderConversation()
        setStatus("💭 Thinking…")
        lifecycleScope.launch {
            try {
                val reply = cleanForSpeech(LlmEngine.reply(userText))
                    .ifEmpty { "Entschuldigung, das habe ich nicht verstanden." }
                LlmEngine.conversation.add(false to reply)
                renderConversation()
                setStatus(if (ttsReady) "🔊 Speaking…" else "Reply ready (no German voice installed).")
                speak(reply)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                setStatus("❌ ${e.message ?: "The AI had a problem."}")
            } finally {
                thinking = false
                updateButtons()
            }
        }
    }

    private fun cleanForSpeech(text: String): String =
        text.replace(Regex("[*#_`]"), "").replace(Regex("\\s+"), " ").trim()

    private fun onTtsReady(ok: Boolean) {
        val engine = tts
        if (!ok || engine == null) {
            ttsReady = false
            voiceButton.visibility = View.VISIBLE
            return
        }
        val result = engine.setLanguage(Locale.GERMANY)
        ttsReady = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
        voiceButton.visibility = if (ttsReady) View.GONE else View.VISIBLE
        updateButtons()
    }

    private fun speak(text: String) {
        if (!ttsReady) return
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "reply")
    }

    private fun installGermanVoice() {
        try {
            startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA))
        } catch (e: ActivityNotFoundException) {
            setStatus("Open your phone's Settings → Text-to-speech and add a German voice.")
        }
    }

    // ---- UI helpers ---------------------------------------------------------------

    private fun lastReply(): String? = LlmEngine.conversation.lastOrNull { !it.first }?.second

    private fun updateButtons() {
        val ready = LlmEngine.isLoaded && !thinking
        talkButton.isEnabled = ready
        talkButton.text = getString(if (listening) R.string.stop_listening else R.string.talk)
        replayButton.isEnabled = ready && !listening && ttsReady && lastReply() != null
        newChatButton.isEnabled = ready && !listening
    }

    private fun renderConversation() {
        conversationText.text = if (LlmEngine.conversation.isEmpty()) {
            getString(R.string.empty_conversation)
        } else {
            LlmEngine.conversation.joinToString("\n\n") { (isUser, text) ->
                (if (isUser) "🗣 You: " else "🔊 Reply: ") + text
            }
        }
        conversationScroll.post { conversationScroll.fullScroll(View.FOCUS_DOWN) }
        updateButtons()
    }

    private fun setStatus(message: String) {
        statusText.text = message
    }

    private fun applySystemBarPadding() {
        val root = findViewById<View>(R.id.root)
        val base = root.paddingLeft
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(base + bars.left, base + bars.top, base + bars.right, base + bars.bottom)
            insets
        }
    }
}
