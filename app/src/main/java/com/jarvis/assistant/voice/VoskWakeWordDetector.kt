package com.jarvis.assistant.voice

import android.content.Context
import org.json.JSONArray
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService

/**
 * Production wake-word detector. Replaces the SpeechRecognizer prototype.
 *
 * Why this is the "real, low-power" implementation rather than another prototype:
 * - Uses Vosk's on-device Kaldi recognizer directly against the mic (via
 *   SpeechService/AudioRecord), not Android's cloud-capable SpeechRecognizer
 *   service — no OS-level speech service round-trip per utterance.
 * - The recognizer is grammar-constrained to the wake phrases (+ an [unk] catch-all),
 *   which collapses the decoding search space to a handful of words instead of a full
 *   open vocabulary language model. That's what actually saves CPU/battery during
 *   continuous listening — this is the standard technique for on-device keyword
 *   spotting when a dedicated proprietary wake-word engine (Porcupine, Snowboy) isn't
 *   being used.
 *
 * Honest limitation: this is still a general ASR engine running constrained, not a
 * purpose-built micro-footprint keyword spotter like Porcupine's DSP-optimized model.
 * It draws more power than that class of engine. If sustained multi-day battery life
 * under continuous wake-word listening becomes a hard requirement, swap in Porcupine
 * behind this same WakeWordDetector interface — no caller code changes.
 */
class VoskWakeWordDetector(
    private val context: Context,
    private val modelManager: WakeWordModelManager
) : WakeWordDetector {

    override var wakePhrases: List<String> = listOf("hey jarvis", "yo jarvis", "jarvis")

    private var speechService: SpeechService? = null
    private var recognizer: Recognizer? = null
    private var running = false
    @Volatile private var triggered = false

    override fun start(onDetected: (phrase: String) -> Unit, onError: (String) -> Unit) {
        if (running) return
        if (!modelManager.isModelInstalled()) {
            onError("Local wake-word model isn't downloaded yet. Finish setup to install it.")
            return
        }
        triggered = false
        try {
            val model = modelManager.loadModel()
            val grammar = buildGrammar()
            recognizer = Recognizer(model, SAMPLE_RATE, grammar)
            speechService = SpeechService(recognizer, SAMPLE_RATE).also { service ->
                running = true
                service.startListening(object : RecognitionListener {
                    override fun onPartialResult(hypothesis: String?) = fireIfMatch(hypothesis, onDetected)
                    override fun onResult(hypothesis: String?) = fireIfMatch(hypothesis, onDetected)
                    override fun onFinalResult(hypothesis: String?) = fireIfMatch(hypothesis, onDetected)
                    override fun onError(exception: Exception?) {
                        if (running) onError(exception?.message ?: "Wake-word listener error")
                    }
                    override fun onTimeout() {
                        // SpeechService auto-restarts internally; nothing to do.
                    }
                })
            }
        } catch (e: Exception) {
            running = false
            onError("Couldn't start local wake-word engine: ${e.message}")
        }
    }

    /** Vosk's partial/result/final callbacks can all fire for the same utterance as
     *  the recognizer refines its hypothesis. Without this guard, a single "hey
     *  jarvis" could trigger onDetected 2-3 times before the caller has a chance to
     *  call stop(), since these callbacks may arrive off the main thread in quick
     *  succession. Only the first match per start()/stop() cycle fires. */
    private fun fireIfMatch(resultJson: String?, onDetected: (String) -> Unit) {
        if (triggered) return
        val match = extractMatch(resultJson) ?: return
        if (triggered) return // re-check post-extraction in case of a race
        triggered = true
        onDetected(match)
    }

    private fun extractMatch(resultJson: String?): String? {
        if (resultJson == null) return null
        val text = runCatching {
            org.json.JSONObject(resultJson).optString("text", resultJson).lowercase()
        }.getOrDefault(resultJson.lowercase())
        return wakePhrases.firstOrNull { text.contains(it) }
    }

    private fun buildGrammar(): String {
        // [unk] lets the constrained grammar absorb non-wake speech instead of
        // misfiring on the nearest wake phrase.
        val array = JSONArray()
        wakePhrases.forEach { array.put(it) }
        array.put("[unk]")
        return array.toString()
    }

    override fun stop() {
        running = false
        speechService?.stop()
        speechService?.shutdown()
        speechService = null
        recognizer?.close()
        recognizer = null
    }

    companion object {
        private const val SAMPLE_RATE = 16000.0f
    }
}
