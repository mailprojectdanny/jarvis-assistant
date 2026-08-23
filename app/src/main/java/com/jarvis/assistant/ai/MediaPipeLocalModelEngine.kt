package com.jarvis.assistant.ai

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInference.LlmInferenceOptions
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession.LlmInferenceSessionOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Real offline LLM inference via MediaPipe's LlmInference task (llama.cpp/XNNPACK
 * under the hood, runs entirely on-CPU/GPU on-device, zero network calls). Used for:
 *  - fuzzy intent classification when the regex router in LocalIntentRouter misses
 *    (typos, rephrasing, accents transcribed oddly)
 *  - short factual/local answers so simple questions never have to hit DeepSeek
 *
 * This is the module referenced by LocalIntentRouter's LocalModelEngine interface —
 * swapping engines later (e.g. to a smaller/faster runtime) means implementing that
 * interface again, no caller changes required.
 */
class MediaPipeLocalModelEngine(
    private val context: Context,
    private val modelManager: LocalModelManager
) : LocalModelEngine {

    private var llmInference: LlmInference? = null
    private var session: LlmInferenceSession? = null

    override suspend fun isInstalled(): Boolean = modelManager.isModelInstalled()

    /** Model acquisition is a license-gated manual import, not a silent background
     *  download — see LocalModelManager for why. This just reports current status. */
    override suspend fun downloadModel(onProgress: (Int) -> Unit): Boolean {
        val installed = modelManager.isModelInstalled()
        onProgress(if (installed) 100 else 0)
        return installed
    }

    private fun ensureLoaded(): Boolean {
        if (llmInference != null) return true
        if (!modelManager.isModelInstalled()) return false
        return try {
            val options = LlmInferenceOptions.builder()
                .setModelPath(modelManager.modelPath())
                .setMaxTokens(512)
                .setMaxTopK(40)
                .build()
            llmInference = LlmInference.createFromOptions(context, options)
            session = LlmInferenceSession.createFromOptions(
                llmInference,
                LlmInferenceSessionOptions.builder()
                    .setTemperature(0.4f)
                    .setTopK(40)
                    .build()
            )
            true
        } catch (e: Exception) {
            llmInference = null
            session = null
            false
        }
    }

    override suspend fun classifyOrAnswer(utterance: String, context: List<String>): String? =
        withContext(Dispatchers.Default) {
            if (!ensureLoaded()) return@withContext null
            val activeSession = session ?: return@withContext null

            val prompt = buildPrompt(utterance, context)
            try {
                suspendCancellableCoroutine { cont ->
                    val builder = StringBuilder()
                    activeSession.addQueryChunk(prompt)
                    activeSession.generateResponseAsync { partial, done ->
                        builder.append(partial)
                        if (done && cont.isActive) {
                            cont.resume(builder.toString().trim().ifBlank { null })
                        }
                    }
                    cont.invokeOnCancellation { /* MediaPipe session cleans up on close() */ }
                }
            } catch (e: Exception) {
                null
            }
        }

    private fun buildPrompt(utterance: String, recentContext: List<String>): String {
        val history = recentContext.takeLast(4).joinToString("\n") { "User: $it" }
        return """
            You are JARVIS, a concise on-device voice assistant. Answer in 1-3 short
            spoken-friendly sentences. If this is a device command you can't execute
            yourself (only answer, don't pretend to act), just answer conversationally.
            $history
            User: $utterance
            JARVIS:
        """.trimIndent()
    }

    fun release() {
        session?.close()
        llmInference?.close()
        session = null
        llmInference = null
    }
}
