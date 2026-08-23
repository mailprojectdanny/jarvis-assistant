package com.jarvis.assistant.voice

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.vosk.Model
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * Manages the small offline Vosk speech model used for both wake-word grammar
 * detection and general on-device STT fallback. Downloaded once on first run
 * (the "download local AI model" step in the setup wizard), then loaded from
 * local storage on every subsequent launch — no network needed after that.
 *
 * Model: vosk-model-small-en-us (~40MB), Apache-2.0 licensed, fully offline.
 * For non-English devices, swap MODEL_URL/MODEL_DIR_NAME per locale — the rest
 * of the pipeline is language-agnostic.
 */
class WakeWordModelManager(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private val modelRoot = File(context.filesDir, "vosk-model")

    fun isModelInstalled(): Boolean =
        File(modelRoot, "conf/model.conf").exists() || File(modelRoot, "am/final.mdl").exists()

    fun modelPath(): String = modelRoot.absolutePath

    /** Downloads + unzips the model with progress callbacks. Safe to call repeatedly;
     *  no-ops if already installed. Runs entirely off the main thread. */
    suspend fun ensureModel(onProgress: (percent: Int) -> Unit): Result<Unit> = withContext(Dispatchers.IO) {
        if (isModelInstalled()) {
            onProgress(100)
            return@withContext Result.success(Unit)
        }
        try {
            val zipFile = File(context.cacheDir, "vosk-model.zip")
            downloadWithProgress(MODEL_URL, zipFile, onProgress)
            unzip(zipFile, context.filesDir)
            // The zip extracts to a versioned folder name; normalize to modelRoot.
            val extracted = context.filesDir.listFiles()
                ?.firstOrNull { it.isDirectory && it.name.startsWith("vosk-model") && it.name != modelRoot.name }
            if (extracted != null && extracted.exists()) {
                extracted.renameTo(modelRoot)
            }
            zipFile.delete()
            if (!isModelInstalled()) return@withContext Result.failure(IllegalStateException("Model files missing after extraction."))
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun loadModel(): Model = Model(modelPath())

    private fun downloadWithProgress(url: String, target: File, onProgress: (Int) -> Unit) {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("Model download failed: HTTP ${resp.code}")
            val body = resp.body ?: throw IllegalStateException("Empty model download body")
            val total = body.contentLength()
            var read = 0L
            body.byteStream().use { input ->
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(8192)
                    var n: Int
                    while (input.read(buffer).also { n = it } != -1) {
                        output.write(buffer, 0, n)
                        read += n
                        if (total > 0) onProgress(((read * 100) / total).toInt())
                    }
                }
            }
        }
    }

    private fun unzip(zipFile: File, destDir: File) {
        ZipInputStream(zipFile.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val outFile = File(destDir, entry.name)
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { fos -> zis.copyTo(fos) }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    companion object {
        const val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
        const val MODEL_SIZE_MB = 40
    }
}
