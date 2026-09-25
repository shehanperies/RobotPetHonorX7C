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
import java.util.Locale

data class VoiceDebug(
    val recognitionAvailable: Boolean = false,
    val onDeviceAvailable: Boolean = false,
    val listening: Boolean = false,
    val usingOnDevice: Boolean = false,
    val languageTag: String = "en-US",
    val partialText: String = "",
    val finalText: String = "",
    val error: String = "",
    val rmsDb: Float = -120f
)

class VoiceManager(
    private val context: Context,
    private val onText: (String) -> Unit,
    private val onDebug: (VoiceDebug) -> Unit
) : RecognitionListener, TextToSpeech.OnInitListener {

    private var recognizer: SpeechRecognizer? = null
    private val tts = TextToSpeech(context, this)
    private val handler = Handler(Looper.getMainLooper())

    private var debug = VoiceDebug(
        recognitionAvailable = SpeechRecognizer.isRecognitionAvailable(context),
        onDeviceAvailable = Build.VERSION.SDK_INT >= 31 &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
    )

    private var fallbackTried = false

    init {
        publish()
    }

    fun listen(languageTag: String = "en-US") {
        fallbackTried = false
        debug = debug.copy(
            languageTag = languageTag.ifBlank { "en-US" },
            partialText = "",
            finalText = "",
            error = ""
        )
        startRecognizer(preferOnDevice = true)
    }

    private fun startRecognizer(preferOnDevice: Boolean) {
        destroyRecognizer()

        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            debug = debug.copy(
                listening = false,
                error = "RECOGNIZER_UNAVAILABLE"
            )
            publish()
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

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, debug.languageTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, useOnDevice)
        }

        debug = debug.copy(
            recognitionAvailable = true,
            onDeviceAvailable = canOnDevice,
            listening = true,
            usingOnDevice = useOnDevice,
            error = ""
        )
        publish()

        recognizer?.startListening(intent)
    }

    fun speak(text: String) {
        recognizer?.cancel()
        debug = debug.copy(listening = false)
        publish()
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "robotpet")
    }

    fun testVoice() {
        speak("Hello. My voice is working.")
    }

    private fun destroyRecognizer() {
        recognizer?.cancel()
        recognizer?.destroy()
        recognizer = null
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.forLanguageTag(debug.languageTag)
        }
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
        destroyRecognizer()

        if (text.isNotBlank()) onText(text)
    }

    override fun onPartialResults(partialResults: Bundle?) {
        val text = partialResults
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            .orEmpty()

        debug = debug.copy(partialText = text)
        publish()
    }

    override fun onError(error: Int) {
        val name = errorName(error)

        val fallbackErrors = setOf(
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
            SpeechRecognizer.ERROR_NETWORK,
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
            SpeechRecognizer.ERROR_SERVER
        )

        if (debug.usingOnDevice && !fallbackTried && error in fallbackErrors) {
            fallbackTried = true
            destroyRecognizer()
            debug = debug.copy(
                listening = false,
                error = "$name → SYSTEM_FALLBACK"
            )
            publish()

            handler.postDelayed({
                startRecognizer(preferOnDevice = false)
            }, 250L)
            return
        }

        debug = debug.copy(
            listening = false,
            error = name
        )
        publish()
        destroyRecognizer()
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

    private fun publish() = onDebug(debug)

    fun shutdown() {
        destroyRecognizer()
        tts.stop()
        tts.shutdown()
    }
}
