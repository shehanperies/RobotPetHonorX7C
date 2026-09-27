package com.shehan.robotpet.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

data class VoiceChoice(
    val name: String,
    val languageTag: String,
    val networkRequired: Boolean,
    val label: String
)

data class VoiceDebug(
    val recognitionAvailable: Boolean = false,
    val onDeviceAvailable: Boolean = false,
    val listening: Boolean = false,
    val usingOnDevice: Boolean = false,
    val languageTag: String = "en-US",
    val partialText: String = "",
    val finalText: String = "",
    val error: String = "",
    val rmsDb: Float = -120f,
    val availableLanguages: List<String> = listOf("en-US"),
    val availableVoices: List<VoiceChoice> = emptyList(),
    val selectedVoiceName: String = "",
    val voicePreset: String = "Welly"
)

class VoiceManager(
    private val context: Context,
    private val onText: (String) -> Unit,
    private val onFailure: (String) -> Unit,
    private val onDebug: (VoiceDebug) -> Unit,
    private val onSpeakingChanged: (Boolean) -> Unit = {}
) : RecognitionListener, TextToSpeech.OnInitListener {

    private var recognizer: SpeechRecognizer? = null
    private val tts = TextToSpeech(context, this)
    private val handler = Handler(Looper.getMainLooper())
    private val utteranceCounter = AtomicLong(0L)

    private var retryCount = 0
    private var fallbackTried = false
    private var ttsReady = false
    private var configuredVoiceName = ""

    private var debug = VoiceDebug(
        recognitionAvailable = SpeechRecognizer.isRecognitionAvailable(context),
        onDeviceAvailable = Build.VERSION.SDK_INT >= 31 &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
    )

    init {
        tts.setOnUtteranceProgressListener(
            object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    handler.post { onSpeakingChanged(true) }
                }

                override fun onDone(utteranceId: String?) {
                    handler.post { onSpeakingChanged(false) }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    handler.post { onSpeakingChanged(false) }
                }
            }
        )
        publish()
    }

    fun listen(languageTag: String = "en-US") {
        tts.stop()
        onSpeakingChanged(false)
        retryCount = 0
        fallbackTried = false
        debug = debug.copy(
            languageTag = "en-US",
            partialText = "",
            finalText = "",
            error = ""
        )
        startRecognizer(preferOnDevice = true)
    }

    fun applySettings(languageTag: String, voiceName: String, preset: String) {
        configuredVoiceName = voiceName
        debug = debug.copy(
            languageTag = "en-US",
            selectedVoiceName = voiceName,
            voicePreset = "Welly",
            availableLanguages = listOf("en-US")
        )
        applyTtsSettings()
        publish()
    }

    fun speak(text: String): Boolean {
        if (debug.listening || text.isBlank() || !ttsReady) return false
        applyTtsSettings()
        val id = "welly-${utteranceCounter.incrementAndGet()}"
        return tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) == TextToSpeech.SUCCESS
    }

    fun testVoice() {
        if (!debug.listening) speak("Hi! I'm Welly. What are you doing?")
    }

    private fun startRecognizer(preferOnDevice: Boolean) {
        destroyRecognizer(cancelFirst = true)

        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            fail("RECOGNIZER_UNAVAILABLE")
            return
        }

        val canOnDevice = Build.VERSION.SDK_INT >= 31 &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        val useOnDevice = preferOnDevice && canOnDevice

        recognizer = try {
            if (useOnDevice) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            } else {
                SpeechRecognizer.createSpeechRecognizer(context)
            }
        } catch (_: Throwable) {
            SpeechRecognizer.createSpeechRecognizer(context)
        }

        recognizer?.setRecognitionListener(this)

        debug = debug.copy(
            recognitionAvailable = true,
            onDeviceAvailable = canOnDevice,
            listening = true,
            usingOnDevice = useOnDevice,
            languageTag = "en-US",
            partialText = "",
            error = ""
        )
        publish()
        recognizer?.startListening(recognitionIntent(useOnDevice))
    }

    private fun recognitionIntent(preferOffline: Boolean): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, preferOffline)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1150L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 650L)
        }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            onFailure("TTS_INIT")
            return
        }
        ttsReady = true

        val englishVoices = tts.voices.orEmpty()
            .filter { it.locale.language == Locale.ENGLISH.language }

        val selected = englishVoices.firstOrNull { it.name == configuredVoiceName }
            ?: englishVoices.maxByOrNull {
                (if (!it.isNetworkConnectionRequired) 10_000 else 0) + it.quality
            }

        if (selected != null) {
            tts.voice = selected
            configuredVoiceName = selected.name
        } else {
            tts.language = Locale.US
        }

        debug = debug.copy(
            availableLanguages = listOf("en-US"),
            availableVoices = englishVoices.map {
                VoiceChoice(
                    name = it.name,
                    languageTag = it.locale.toLanguageTag(),
                    networkRequired = it.isNetworkConnectionRequired,
                    label = "Welly candidate • ${it.name}"
                )
            },
            selectedVoiceName = selected?.name.orEmpty(),
            voicePreset = "Welly"
        )
        applyTtsSettings()
        publish()
    }

    private fun applyTtsSettings() {
        if (!ttsReady) return
        tts.language = Locale.US

        val selected = tts.voices.orEmpty()
            .firstOrNull { it.name == configuredVoiceName && it.locale.language == "en" }

        if (selected != null) tts.voice = selected

        tts.setPitch(1.0f)
        tts.setSpeechRate(1.0f)
    }

    override fun onResults(results: Bundle?) {
        val text = results
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            .orEmpty()

        debug = debug.copy(
            listening = false,
            finalText = text,
            partialText = "",
            error = ""
        )
        publish()
        destroyRecognizer(cancelFirst = false)

        if (text.isNotBlank()) onText(text) else fail("NO_TEXT")
    }

    override fun onPartialResults(partialResults: Bundle?) {
        val text = partialResults
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            .orEmpty()

        if (text.isNotBlank()) {
            debug = debug.copy(partialText = text)
            publish()
        }
    }

    override fun onError(error: Int) {
        val name = errorName(error)

        if (
            (error == SpeechRecognizer.ERROR_NO_MATCH ||
                error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) &&
            retryCount < 1
        ) {
            retryCount++
            debug = debug.copy(listening = false, error = "$name • retrying")
            publish()
            destroyRecognizer(cancelFirst = false)
            handler.postDelayed({ startRecognizer(preferOnDevice = debug.usingOnDevice) }, 300L)
            return
        }

        val fallbackErrors = setOf(
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
            SpeechRecognizer.ERROR_NETWORK,
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
            SpeechRecognizer.ERROR_SERVER,
            SpeechRecognizer.ERROR_SERVER_DISCONNECTED
        )

        if (debug.usingOnDevice && !fallbackTried && error in fallbackErrors) {
            fallbackTried = true
            debug = debug.copy(listening = false, error = "$name • system fallback")
            publish()
            destroyRecognizer(cancelFirst = false)
            handler.postDelayed({ startRecognizer(preferOnDevice = false) }, 300L)
            return
        }

        fail(name)
        destroyRecognizer(cancelFirst = false)
    }

    private fun fail(message: String) {
        debug = debug.copy(listening = false, error = message)
        publish()
        onFailure(message)
    }

    override fun onRmsChanged(rmsdB: Float) {
        debug = debug.copy(rmsDb = rmsdB)
        publish()
    }

    override fun onReadyForSpeech(params: Bundle?) = Unit
    override fun onBeginningOfSpeech() = Unit
    override fun onBufferReceived(buffer: ByteArray?) = Unit
    override fun onEndOfSpeech() = Unit
    override fun onEvent(eventType: Int, params: Bundle?) = Unit

    private fun errorName(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "AUDIO"
        SpeechRecognizer.ERROR_CLIENT -> "CLIENT"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "MIC_PERMISSION"
        SpeechRecognizer.ERROR_NETWORK -> "NETWORK"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "NETWORK_TIMEOUT"
        SpeechRecognizer.ERROR_NO_MATCH -> "NO_MATCH"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "BUSY"
        SpeechRecognizer.ERROR_SERVER -> "SERVER"
        SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "SERVER_DISCONNECTED"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "SPEECH_TIMEOUT"
        SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "TOO_MANY_REQUESTS"
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "LANGUAGE_NOT_SUPPORTED"
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "LANGUAGE_UNAVAILABLE"
        else -> "ERROR_$error"
    }

    private fun destroyRecognizer(cancelFirst: Boolean) {
        if (cancelFirst) runCatching { recognizer?.cancel() }
        runCatching { recognizer?.destroy() }
        recognizer = null
    }

    private fun publish() = onDebug(debug)

    fun shutdown() {
        destroyRecognizer(cancelFirst = true)
        tts.stop()
        onSpeakingChanged(false)
        tts.shutdown()
    }
}
