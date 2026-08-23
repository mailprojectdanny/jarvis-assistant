package com.jarvis.assistant.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Pluggable wake-word detection. Swap the implementation without touching callers.
 *
 * v1 (this file): uses Android's on-device SpeechRecognizer in continuous partial-results
 * mode and matches against the configured wake phrases locally. This works and keeps
 * everything on-device, but is NOT a true low-power wake-word engine — the OS speech
 * service has to be actively running, which costs more battery than a dedicated
 * keyword-spotting model (e.g. Porcupine, Vosk-small). Swap in WakeWordDetectorPorcupine
 * (next module) for production-grade always-on listening.
 */
interface WakeWordDetector {
    fun start(onDetected: (phrase: String) -> Unit, onError: (String) -> Unit)
    fun stop()
    var wakePhrases: List<String>
}

class SpeechRecognizerWakeWordDetector(
    private val context: Context
) : WakeWordDetector {

    override var wakePhrases: List<String> = listOf("hey jarvis", "yo jarvis", "jarvis")

    private var recognizer: SpeechRecognizer? = null
    private var running = false
    @Volatile private var triggered = false

    override fun start(onDetected: (phrase: String) -> Unit, onError: (String) -> Unit) {
        if (running) return
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onError("On-device speech recognition isn't available on this device.")
            return
        }
        running = true
        triggered = false
        recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle) = handle(results, onDetected)
                override fun onPartialResults(partialResults: Bundle) = handle(partialResults, onDetected)
                override fun onError(error: Int) {
                    // Restart listening loop on recoverable errors (no-match, timeout).
                    if (running) restartListening()
                }
                override fun onEndOfSpeech() { if (running) restartListening() }
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
        beginListening()
    }

    /** onPartialResults and onResults can both fire for the same wake-word utterance;
     *  without this guard onDetected could fire twice for one "hey jarvis" before the
     *  caller's stop() takes effect. */
    private fun handle(results: Bundle, onDetected: (String) -> Unit) {
        if (triggered) return
        val matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: return
        val heard = matches.joinToString(" ").lowercase()
        val hit = wakePhrases.firstOrNull { heard.contains(it) } ?: return
        if (triggered) return
        triggered = true
        onDetected(hit)
    }

    private fun restartListening() {
        recognizer?.cancel()
        beginListening()
    }

    private fun beginListening() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
        recognizer?.startListening(intent)
    }

    override fun stop() {
        running = false
        recognizer?.cancel()
        recognizer?.destroy()
        recognizer = null
    }
}
