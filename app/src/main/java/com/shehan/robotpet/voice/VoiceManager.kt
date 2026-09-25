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
    val phase: String = "IDLE",
    val languageTag: String = "en-US",
    val partialText: String = "",
    val finalText: String = "",
    val error: String = "",
    val rmsDb: Float = -120f,
    val availableLanguages: List<String> = emptyList(),
    val installedOnDeviceLanguages: List<String> = emptyList(),
    val onlineLanguages: List<String> = emptyList(),
    val availableTtsLanguages: List<String> = emptyList(),
    val availableVoices: List<VoiceChoice> = emptyList(),
    val selectedVoiceName: String = "",
    val activeVoiceName: String = "",
    val voicePreset: String = "Normal"
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
    private var configuredPreset = "Normal"

    private var debug = VoiceDebug(
        recognitionAvailable = SpeechRecognizer.isRecognitionAvailable(context),
        onDeviceAvailable = Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
    )

    init { publish() }

    fun listen(languageTag: String = debug.languageTag) {
        tts.stop()
        retryCount = 0
        fallbackTried = false
        debug = debug.copy(
            languageTag = languageTag.ifBlank { "en-US" },
            partialText = "",
            finalText = "",
            error = "",
            phase = "STARTING_MIC"
        )
        publish()
        startRecognizer(preferOnDevice = true)
    }

    fun applySettings(languageTag: String, voiceName: String, preset: String) {
        configuredVoiceName = voiceName
        configuredPreset = preset.ifBlank { "Normal" }
        debug = debug.copy(
            languageTag = languageTag.ifBlank { "en-US" },
            selectedVoiceName = voiceName,
            voicePreset = configuredPreset
        )
        applyTtsSettings()
        publish()
    }

    fun previewVoice(languageTag: String, voiceName: String, preset: String) {
        if (!ttsReady || debug.listening) return
        val oldLanguage = debug.languageTag
        val oldName = configuredVoiceName
        val oldPreset = configuredPreset

        debug = debug.copy(languageTag = languageTag.ifBlank { oldLanguage })
        configuredVoiceName = voiceName
        configuredPreset = preset.ifBlank { oldPreset }
        applyTtsSettings()
        tts.speak("Hello. This is my voice.", TextToSpeech.QUEUE_FLUSH, null, "robotpet-preview")

        handler.postDelayed({
            debug = debug.copy(languageTag = oldLanguage)
            configuredVoiceName = oldName
            configuredPreset = oldPreset
            applyTtsSettings()
            publish()
        }, 2400L)
    }

    fun speak(text: String): Boolean {
        if (debug.listening || !ttsReady) return false
        applyTtsSettings()
        debug = debug.copy(phase = "SPEAKING")
        publish()
        val result = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "robotpet")
        handler.postDelayed({
            if (!debug.listening && debug.phase == "SPEAKING") {
                debug = debug.copy(phase = "IDLE")
                publish()
            }
        }, (700L + text.length * 48L).coerceAtMost(6500L))
        return result == TextToSpeech.SUCCESS
    }

    fun testVoice() { if (!debug.listening) speak("Hello. This is my selected voice.") }

    private fun startRecognizer(preferOnDevice: Boolean) {
        destroyRecognizer(cancelFirst = true)
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            fail("RECOGNIZER_UNAVAILABLE")
            return
        }

        val canOnDevice = Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        val useOnDevice = preferOnDevice && canOnDevice
        recognizer = try {
            if (useOnDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            else SpeechRecognizer.createSpeechRecognizer(context)
        } catch (_: Throwable) {
            SpeechRecognizer.createSpeechRecognizer(context)
        }
        recognizer?.setRecognitionListener(this)

        debug = debug.copy(
            recognitionAvailable = true,
            onDeviceAvailable = canOnDevice,
            listening = true,
            usingOnDevice = useOnDevice,
            partialText = "",
            error = "",
            phase = "STARTING_MIC"
        )
        publish()
        recognizer?.startListening(recognitionIntent(debug.languageTag, useOnDevice))
    }

    private fun recognitionIntent(languageTag: String, preferOffline: Boolean) =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, preferOffline)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1300L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 750L)
        }

    private fun queryRecognitionLanguages() {
        if (Build.VERSION.SDK_INT < 33 || !SpeechRecognizer.isRecognitionAvailable(context)) {
            // Do NOT fill this with TTS languages; recognition and synthesis are separate.
            if (debug.availableLanguages.isEmpty()) {
                debug = debug.copy(availableLanguages = listOf(debug.languageTag))
                publish()
            }
            return
        }

        runCatching {
            supportProbe?.destroy()
            supportProbe = SpeechRecognizer.createSpeechRecognizer(context)
            supportProbe?.checkRecognitionSupport(
                recognitionIntent(debug.languageTag, false),
                context.mainExecutor,
                object : RecognitionSupportCallback {
                    override fun onSupportResult(recognitionSupport: RecognitionSupport) {
                        val installed = recognitionSupport.installedOnDeviceLanguages
                            .filter { it.isNotBlank() }.distinct().sorted()
                        val online = recognitionSupport.onlineLanguages
                            .filter { it.isNotBlank() }.distinct().sorted()
                        val supportedOffline = recognitionSupport.supportedOnDeviceLanguages
                            .filter { it.isNotBlank() }.distinct().sorted()
                        val all = (installed + online + supportedOffline + debug.languageTag)
                            .filter { it.isNotBlank() }.distinct().sorted()
                        debug = debug.copy(
                            availableLanguages = all,
                            installedOnDeviceLanguages = installed,
                            onlineLanguages = online
                        )
                        publish()
                        supportProbe?.destroy(); supportProbe = null
                    }
                    override fun onError(error: Int) {
                        if (debug.availableLanguages.isEmpty()) {
                            debug = debug.copy(availableLanguages = listOf(debug.languageTag))
                            publish()
                        }
                        supportProbe?.destroy(); supportProbe = null
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
                        append(if (it.isNetworkConnectionRequired) " • online" else " • offline")
                    }
                )
            }
        val ttsLanguages = tts.availableLanguages.orEmpty()
            .map { it.toLanguageTag() }.filter { it.isNotBlank() }.distinct().sorted()
        debug = debug.copy(availableVoices = voices, availableTtsLanguages = ttsLanguages)
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
            debug = debug.copy(
                activeVoiceName = selected.name,
                selectedVoiceName = if (configuredVoiceName.isBlank()) selected.name else configuredVoiceName
            )
        }
        val (pitch, rate) = when (configuredPreset) {
            "Cute" -> 1.18f to 1.03f
            "Deep" -> 0.82f to 0.92f
            "Tiny Bot" -> 1.30f to 1.08f
            "Calm" -> 0.96f to 0.88f
            "Robot" -> 0.90f to 0.96f
            else -> 1.00f to 1.00f
        }
        tts.setPitch(pitch)
        tts.setSpeechRate(rate)
    }

    override fun onReadyForSpeech(params: Bundle?) {
        debug = debug.copy(phase = "LISTENING")
        publish()
    }

    override fun onBeginningOfSpeech() {
        debug = debug.copy(phase = "HEARING")
        publish()
    }

    override fun onEndOfSpeech() {
        debug = debug.copy(phase = "PROCESSING")
        publish()
    }

    override fun onResults(results: Bundle?) {
        val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull().orEmpty()
        debug = debug.copy(
            listening = false,
            finalText = text,
            partialText = "",
            error = "",
            phase = if (text.isBlank()) "IDLE" else "PROCESSING"
        )
        publish()
        destroyRecognizer(cancelFirst = false)
        if (text.isNotBlank()) onText(text) else fail("NO_TEXT")
    }

    override fun onPartialResults(partialResults: Bundle?) {
        val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull().orEmpty()
        if (text.isNotBlank()) {
            debug = debug.copy(partialText = text, phase = "HEARING")
            publish()
        }
    }

    override fun onError(error: Int) {
        val name = errorName(error)
        if ((error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) && retryCount < 1) {
            retryCount++
            debug = debug.copy(listening = false, error = "$name • retrying", phase = "RETRYING")
            publish()
            destroyRecognizer(cancelFirst = false)
            handler.postDelayed({ startRecognizer(preferOnDevice = debug.usingOnDevice) }, 280L)
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
            debug = debug.copy(listening = false, error = "$name • system fallback", phase = "FALLBACK")
            publish()
            destroyRecognizer(cancelFirst = false)
            handler.postDelayed({ startRecognizer(preferOnDevice = false) }, 280L)
            return
        }
        fail(name)
        destroyRecognizer(cancelFirst = false)
    }

    private fun fail(message: String) {
        debug = debug.copy(listening = false, error = message, phase = "ERROR")
        publish()
        onFailure(message)
    }

    override fun onRmsChanged(rmsdB: Float) {
        debug = debug.copy(rmsDb = rmsdB)
        publish()
    }

    override fun onBufferReceived(buffer: ByteArray?) = Unit
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
        supportProbe?.destroy(); supportProbe = null
        tts.stop(); tts.shutdown()
    }
}
