package com.jarvis.assistant.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.UUID

/** Wraps Android TTS (works fully offline once voice data is installed).
 *  stopSpeaking() gives instant "boh" interruption — TTS.stop() is immediate, not queued. */
class TextToSpeechManager(context: Context, private val onDone: (utteranceId: String) -> Unit = {}) {

    private var ready = false
    private var pendingText: String? = null

    private val tts = TextToSpeech(context) { status ->
        ready = status == TextToSpeech.SUCCESS
        if (ready) {
            tts?.language = Locale.getDefault()
            pendingText?.let { speak(it) }
            pendingText = null
        }
    }

    init {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { utteranceId?.let(onDone) }
            override fun onError(utteranceId: String?) {}
        })
    }

    private var tts: TextToSpeech? = tts

    fun speak(text: String) {
        if (!ready) { pendingText = text; return }
        val id = UUID.randomUUID().toString()
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
    }

    /** Instant interruption ("boh"). */
    fun stopSpeaking() {
        tts?.stop()
    }

    fun isSpeaking(): Boolean = tts?.isSpeaking == true

    fun shutdown() {
        tts?.shutdown()
    }
}
