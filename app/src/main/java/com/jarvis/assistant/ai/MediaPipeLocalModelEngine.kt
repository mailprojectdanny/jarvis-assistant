package com.jarvis.assistant.ai

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInference.LlmInferenceOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
                .setTopK(40)
                .setTemperature(0.4f)
                .build()
            llmInference = LlmInference.createFromOptions(context, options)
            true
        } catch (e: Exception) {
            llmInference = null
            false
        }
    }

    override suspend fun classifyOrAnswer(utterance: String, context: List<String>): String? =
        withContext(Dispatchers.Default) {
            if (!ensureLoaded()) return@withContext null
            val activeInference = llmInference ?: return@withContext null

            val prompt = buildPrompt(utterance, context)
            try {
                activeInference.generateResponse(prompt).trim().ifBlank { null }
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
        llmInference?.close()
        llmInference = null
    }
}
