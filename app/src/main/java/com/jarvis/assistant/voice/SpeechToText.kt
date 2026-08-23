package com.jarvis.assistant.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/** One-shot high-accuracy capture of a single command while a session is Active.
 *  Supports barge-in: caller can invoke stop() at any time (e.g. on hearing "boh"). */
class SpeechToText(private val context: Context) {

    private var recognizer: SpeechRecognizer? = null

    fun listenOnce(
        onPartial: (String) -> Unit = {},
        onFinal: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle) {
                    val text = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull().orEmpty()
                    onFinal(text)
                }
                override fun onPartialResults(partialResults: Bundle) {
                    partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()?.let(onPartial)
                }
                override fun onError(error: Int) = onError(errorText(error))
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            startListening(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                }
            )
        }
    }

    /** Barge-in / "boh" interruption support. */
    fun cancel() {
        recognizer?.cancel()
    }

    fun release() {
        recognizer?.destroy()
        recognizer = null
    }

    private fun errorText(code: Int) = when (code) {
        SpeechRecognizer.ERROR_NO_MATCH -> "Didn't catch that."
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected."
        SpeechRecognizer.ERROR_NETWORK -> "Network error during recognition."
        else -> "Speech recognition error ($code)."
    }
}
