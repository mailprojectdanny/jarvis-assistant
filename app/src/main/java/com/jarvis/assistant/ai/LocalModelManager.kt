package com.jarvis.assistant.ai

import android.content.Context
import android.net.Uri
import java.io.File

/**
 * Manages the on-device LLM weights used by MediaPipeLocalModelEngine.
 *
 * Honest note on why this isn't a silent auto-download like the wake-word model:
 * Google's MediaPipe-converted small models (Gemma-2 2B, Phi-3-mini, Falcon-1B, etc.)
 * are distributed through Kaggle Models / Hugging Face behind an explicit license
 * click-through (e.g. Gemma's usage terms). There is no legitimate unauthenticated
 * download URL for these — anything that pretended otherwise would either break on
 * first run or silently bypass a license gate, which this project won't do.
 *
 * So the real flow is: the setup wizard links out to the model page, the user accepts
 * the license and downloads the .task file once (their browser, their account), then
 * picks it via the Storage Access Framework. From then on it's copied into app-private
 * storage and everything is 100% offline — identical end state to the wake-word model,
 * just with a manual first step instead of a background download.
 */
class LocalModelManager(private val context: Context) {

    private val modelFile = File(context.filesDir, "local_llm/model.task")

    fun isModelInstalled(): Boolean = modelFile.exists() && modelFile.length() > 0

    fun modelPath(): String = modelFile.absolutePath

    /** Copies a user-picked .task file (from ACTION_OPEN_DOCUMENT) into app storage. */
    fun importModel(sourceUri: Uri): Result<Unit> = runCatching {
        modelFile.parentFile?.mkdirs()
        context.contentResolver.openInputStream(sourceUri)?.use { input ->
            modelFile.outputStream().use { output -> input.copyTo(output) }
        } ?: throw IllegalStateException("Couldn't open the selected model file.")
        if (modelFile.length() < MIN_PLAUSIBLE_MODEL_BYTES) {
            modelFile.delete()
            throw IllegalStateException("That file doesn't look like a valid .task model.")
        }
    }

    fun deleteModel() {
        modelFile.delete()
    }

    companion object {
        // Recommended model, ~1.3GB int4 quantized, fits comfortably on modern devices:
        // https://www.kaggle.com/models/google/gemma-2/tfLite (choose "gemma2-2b-it-cpu-int4.task")
        const val MODEL_INFO_URL = "https://www.kaggle.com/models/google/gemma-2/tfLite"
        private const val MIN_PLAUSIBLE_MODEL_BYTES = 10_000_000L
    }
}
