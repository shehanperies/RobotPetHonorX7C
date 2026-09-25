package com.shehan.robotpet.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import java.util.Locale

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
    val availableLanguages: List<String> = emptyList(),
    val availableVoices: List<VoiceChoice> = emptyList(),
    val selectedVoiceName: String = "",
    val voicePreset: String = "Robot"
)

class VoiceManager(
    private val context: Context,
    private val onText: (String) -> Unit,
    private val onFailure: (String) -> Unit,
    private val onDebug: (VoiceDebug) -> Unit
) : RecognitionListener, TextToSpeech.OnInitListener {

    private var recognizer: SpeechRecognizer? = null
    private var supportProbe: SpeechRecognizer? = null
    private val tts = TextToSpeech(context, this)
    private val handler = Handler(Looper.getMainLooper())

    private var retryCount = 0
    private var ttsReady = false
    private var fallbackTried = false
    private var configuredVoiceName = ""
    private var configuredPreset = "Robot"

    private var debug = VoiceDebug(
        recognitionAvailable = SpeechRecognizer.isRecognitionAvailable(context),
        onDeviceAvailable = Build.VERSION.SDK_INT >= 31 &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
    )

    init {
        publish()
    }

    fun listen(languageTag: String = debug.languageTag) {
        tts.stop()
        retryCount = 0
        fallbackTried = false
        debug = debug.copy(
            languageTag = languageTag.ifBlank { "en-US" },
            partialText = "",
            finalText = "",
            error = ""
        )
        applyTtsSettings()
        startRecognizer(preferOnDevice = true)
    }

    fun applySettings(languageTag: String, voiceName: String, preset: String) {
        configuredVoiceName = voiceName
        configuredPreset = preset.ifBlank { "Robot" }
        debug = debug.copy(
            languageTag = languageTag.ifBlank { "en-US" },
            selectedVoiceName = voiceName,
            voicePreset = configuredPreset
        )
        applyTtsSettings()
        publish()
    }

    fun speak(text: String): Boolean {
        // Never let an autonomous reaction kill an active microphone session.
        if (debug.listening) return false
        applyTtsSettings()
        return tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "robotpet") == TextToSpeech.SUCCESS
    }

    fun testVoice() {
        if (!debug.listening) speak("Hello. This is my selected voice.")
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

        val intent = recognitionIntent(debug.languageTag, useOnDevice)
        debug = debug.copy(
            recognitionAvailable = true,
            onDeviceAvailable = canOnDevice,
            listening = true,
            usingOnDevice = useOnDevice,
            partialText = "",
            error = ""
        )
        publish()

        recognizer?.startListening(intent)
    }

    private fun recognitionIntent(languageTag: String, preferOffline: Boolean): Intent {
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, preferOffline)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 700L)
        }
    }

    private fun queryRecognitionLanguages() {
        if (Build.VERSION.SDK_INT < 33 || !SpeechRecognizer.isRecognitionAvailable(context)) return

        runCatching {
            supportProbe?.destroy()
            supportProbe = SpeechRecognizer.createSpeechRecognizer(context)
            val intent = recognitionIntent(debug.languageTag, false)
            supportProbe?.checkRecognitionSupport(
                intent,
                context.mainExecutor,
                object : RecognitionSupportCallback {
                    override fun onSupportResult(recognitionSupport: RecognitionSupport) {
                        val languages = buildSet {
                            addAll(recognitionSupport.installedOnDeviceLanguages)
                            addAll(recognitionSupport.onlineLanguages)
                            addAll(recognitionSupport.supportedOnDeviceLanguages)
                        }
                            .filter { it.isNotBlank() }
                            .sortedBy { it.lowercase() }

                        if (languages.isNotEmpty()) {
                            debug = debug.copy(availableLanguages = languages)
                            publish()
                        }
                        supportProbe?.destroy()
                        supportProbe = null
                    }

                    override fun onError(error: Int) {
                        supportProbe?.destroy()
                        supportProbe = null
                    }
                }
            )
        }
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        ttsReady = true

        val voices = tts.voices.orEmpty()
            .sortedWith(compareBy<Voice>({ it.locale.displayLanguage }, { it.name }))
            .map {
                VoiceChoice(
                    name = it.name,
                    languageTag = it.locale.toLanguageTag(),
                    networkRequired = it.isNetworkConnectionRequired,
                    label = buildString {
                        append(it.locale.displayName)
                        append(" • ")
                        append(it.name)
                        if (it.isNetworkConnectionRequired) append(" • online") else append(" • offline")
                    }
                )
            }

        val fallbackLanguages = buildSet {
            add(Locale.getDefault().toLanguageTag())
            addAll(tts.availableLanguages.orEmpty().map { it.toLanguageTag() })
        }.filter { it.isNotBlank() }.sortedBy { it.lowercase() }

        debug = debug.copy(
            availableVoices = voices,
            availableLanguages = if (debug.availableLanguages.isEmpty()) fallbackLanguages else debug.availableLanguages
        )

        applyTtsSettings()
        publish()
        handler.post { queryRecognitionLanguages() }
    }

    private fun applyTtsSettings() {
        if (!ttsReady) return
        val locale = Locale.forLanguageTag(debug.languageTag)
        tts.language = locale

        val selected = tts.voices?.firstOrNull { it.name == configuredVoiceName }
            ?: tts.voices?.firstOrNull { it.locale.toLanguageTag() == debug.languageTag && !it.isNetworkConnectionRequired }
            ?: tts.voices?.firstOrNull { it.locale.language == locale.language && !it.isNetworkConnectionRequired }

        if (selected != null) {
            tts.voice = selected
            if (configuredVoiceName.isBlank()) {
                configuredVoiceName = selected.name
                debug = debug.copy(selectedVoiceName = selected.name)
            }
        }

        val (pitch, rate) = when (configuredPreset) {
            "Cute" -> 1.25f to 1.05f
            "Deep" -> 0.72f to 0.90f
            "Tiny Bot" -> 1.42f to 1.12f
            "Calm" -> 0.94f to 0.86f
            "Normal" -> 1.00f to 1.00f
            else -> 0.86f to 0.96f // Robot
        }
        tts.setPitch(pitch)
        tts.setSpeechRate(rate)
    }

    override fun onResults(results: Bundle?) {
        val candidates = results
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            .orEmpty()
        val text = candidates.firstOrNull().orEmpty()

        debug = debug.copy(
            listening = false,
            finalText = text,
            partialText = "",
            error = ""
        )
        publish()
        destroyRecognizer(cancelFirst = false)

        if (text.isNotBlank()) {
            onText(text)
        } else {
            fail("NO_TEXT")
        }
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

        if ((error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) && retryCount < 1) {
            retryCount++
            debug = debug.copy(listening = false, error = "$name • retrying")
            publish()
            destroyRecognizer(cancelFirst = false)
            handler.postDelayed({ startRecognizer(preferOnDevice = debug.usingOnDevice) }, 250L)
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
            handler.postDelayed({ startRecognizer(preferOnDevice = false) }, 250L)
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
        supportProbe?.destroy()
        supportProbe = null
        tts.stop()
        tts.shutdown()
    }
}
