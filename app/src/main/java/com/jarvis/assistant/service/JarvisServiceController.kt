package com.jarvis.assistant.service

import android.content.Context
import android.content.Intent
import android.os.Build
import com.jarvis.assistant.session.JarvisUiState

/**
 * JarvisForegroundService picks its wake-word engine and local LLM engine once, in
 * onCreate. That's fine on first natural startup (setup wizard finishes fully before
 * the service is ever started), but if a model is downloaded/imported or removed
 * *while the service is already running* — e.g. from Settings, or a setup step
 * revisited after skipping it — the running instance would otherwise keep using
 * whatever it picked at creation time until the process happens to restart. This
 * makes that update immediate instead of silently stale, which is the difference
 * between "downloaded" and "actually usable".
 */
object JarvisServiceController {

    fun start(context: Context) {
        val intent = Intent(context, JarvisForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    fun stop(context: Context) {
        context.stopService(Intent(context, JarvisForegroundService::class.java))
    }

    /** Call after a model download/import/removal so the change takes effect now,
     *  not "next time the app happens to restart". No-ops if JARVIS isn't running,
     *  since the next natural start will already pick up the new model. */
    fun restartIfRunning(context: Context) {
        if (!JarvisUiState.serviceRunning.value) return
        stop(context)
        start(context)
    }
}
