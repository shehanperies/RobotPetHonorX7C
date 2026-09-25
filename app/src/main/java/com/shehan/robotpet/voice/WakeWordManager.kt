package com.shehan.robotpet.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat

/**
 * Foreground soft wake word. V8 fixes permission recovery and mic ownership, but this is still
 * an Android SpeechRecognizer based wake listener (not a DSP/KWS model).
 */
class WakeWordManager(
    private val context: Context,
    private val languageProvider: () -> String,
    private val onWake: () -> Unit,
    private val onState: (String) -> Unit = {}
) : RecognitionListener {
    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var enabled = false
    private var paused = true
    private var generation = 0
    private var usingOnDevice = false

    fun start() {
        enabled = true
        if (!hasMicPermission()) {
            paused = true
            onState("WAKE_MIC_PERMISSION")
            return
        }
        paused = false
        scheduleStart(500L)
    }

    fun pause() {
        paused = true
        generation++
        handler.removeCallbacksAndMessages(null)
        destroy(true)
        onState("WAKE_PAUSED")
    }

    fun resume(delayMs: Long = 900L) {
        if (!enabled) return
        if (!hasMicPermission()) {
            paused = true
            onState("WAKE_MIC_PERMISSION")
            return
        }
        paused = false
        scheduleStart(delayMs)
    }

    fun onPermissionChanged(granted: Boolean) {
        if (!enabled) return
        if (granted) resume(500L) else pause()
    }

    fun setEnabled(value: Boolean) {
        enabled = value
        if (value) resume(500L) else pause()
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun scheduleStart(delayMs: Long) {
        if (!enabled || paused) return
        val token = ++generation
        handler.postDelayed({ if (token == generation && enabled && !paused) startSession() }, delayMs)
    }

    private fun startSession() {
        if (!enabled || paused || !hasMicPermission()) return
        destroy(true)
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onState("WAKE_UNAVAILABLE"); scheduleStart(3500L); return
        }
        usingOnDevice = Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        recognizer = try {
            if (usingOnDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            else SpeechRecognizer.createSpeechRecognizer(context)
        } catch (_: Throwable) {
            usingOnDevice = false
            runCatching { SpeechRecognizer.createSpeechRecognizer(context) }.getOrNull()
        }
        val r = recognizer ?: run { onState("WAKE_CREATE_FAILED"); scheduleStart(3000L); return }
        r.setRecognitionListener(this)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageProvider().ifBlank { "en-US" })
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, usingOnDevice)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 950L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 600L)
        }
        onState(if (usingOnDevice) "WAKE_READY_OFFLINE" else "WAKE_READY_SYSTEM")
        runCatching { r.startListening(intent) }.onFailure { scheduleStart(1500L) }
    }

    private fun inspect(bundle: Bundle?): Boolean {
        val values = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
        if (values.any(WakeWordMatcher::matches)) {
            pause()
            onState("WAKE_HEARD_WELLY")
            onWake()
            return true
        }
        return false
    }

    override fun onPartialResults(partialResults: Bundle?) { inspect(partialResults) }
    override fun onResults(results: Bundle?) {
        if (!inspect(results)) { destroy(false); scheduleStart(550L) }
    }

    override fun onError(error: Int) {
        destroy(false)
        if (!enabled || paused) return
        val delay = when (error) {
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> 1800L
            SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> 8000L
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                paused = true; onState("WAKE_MIC_PERMISSION"); return
            }
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> 650L
            else -> 1200L
        }
        scheduleStart(delay)
    }

    override fun onReadyForSpeech(params: Bundle?) = Unit
    override fun onBeginningOfSpeech() = Unit
    override fun onRmsChanged(rmsdB: Float) = Unit
    override fun onBufferReceived(buffer: ByteArray?) = Unit
    override fun onEndOfSpeech() = Unit
    override fun onEvent(eventType: Int, params: Bundle?) = Unit

    private fun destroy(cancel: Boolean) {
        if (cancel) runCatching { recognizer?.cancel() }
        runCatching { recognizer?.destroy() }; recognizer = null
    }

    fun shutdown() {
        enabled = false; paused = true; generation++
        handler.removeCallbacksAndMessages(null); destroy(true)
    }
}
