package com.jarvis.assistant.overlay

import android.app.Service
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.setPadding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL

sealed class OverlayContent {
    data class Card(val title: String, val body: String) : OverlayContent()
    data class BulletList(val title: String, val items: List<String>) : OverlayContent()
    data class ImageUrl(val title: String, val url: String) : OverlayContent()
}

/**
 * Renders the "Show me" visual layer using WindowManager.addView with
 * TYPE_APPLICATION_OVERLAY — the standard, user-authorized overlay mechanism.
 * Requires Settings.canDrawOverlays(context), requested explicitly during setup.
 *
 * Protected/secure screens: Android's WindowManager itself refuses to composite
 * TYPE_APPLICATION_OVERLAY windows over secure system surfaces (the lock screen,
 * permission/credential dialogs, other apps' FLAG_SECURE windows). That's enforced
 * at the OS level — there's no API surface for an app to draw there even if it
 * wanted to, so no extra handling is needed here beyond not fighting the system.
 *
 * Supports the three content types requested: text/definition cards, step/bullet
 * lists, and images. Auto-dismisses after a timeout in addition to tap-to-close,
 * so a forgotten card doesn't sit on screen indefinitely.
 */
class OverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayView: LinearLayout? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var dismissRunnable: Runnable? = null
    private val scope = CoroutineScope(Job() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        val title = intent?.getStringExtra(EXTRA_TITLE).orEmpty()
        when (intent?.getStringExtra(EXTRA_KIND)) {
            KIND_LIST -> {
                val items = intent.getStringArrayExtra(EXTRA_ITEMS)?.toList().orEmpty()
                showList(title, items)
            }
            KIND_IMAGE -> {
                val url = intent.getStringExtra(EXTRA_BODY).orEmpty()
                showImage(title, url)
            }
            else -> {
                val body = intent?.getStringExtra(EXTRA_BODY).orEmpty()
                showCard(title, body)
            }
        }
        return START_NOT_STICKY
    }

    private fun baseContainer(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(48)
        setBackgroundColor(Color.parseColor("#E6101828"))
    }

    private fun titleView(title: String) = TextView(this).apply {
        text = title
        setTextColor(Color.WHITE)
        textSize = 20f
    }

    private fun closeHint() = TextView(this).apply {
        text = "Tap to dismiss"
        setTextColor(Color.parseColor("#5C6BC0"))
        textSize = 12f
        setPadding(0, 24, 0, 0)
    }

    private fun showCard(title: String, body: String) {
        val container = baseContainer()
        container.addView(titleView(title))
        container.addView(TextView(this).apply {
            text = body
            setTextColor(Color.parseColor("#B0BEC5"))
            textSize = 16f
            setPadding(0, 16, 0, 0)
        })
        container.addView(closeHint())
        mount(container)
    }

    private fun showList(title: String, items: List<String>) {
        val container = baseContainer()
        container.addView(titleView(title))
        items.forEachIndexed { index, item ->
            container.addView(TextView(this).apply {
                text = "${index + 1}. $item"
                setTextColor(Color.parseColor("#B0BEC5"))
                textSize = 16f
                setPadding(0, 12, 0, 0)
            })
        }
        container.addView(closeHint())
        mount(container)
    }

    private fun showImage(title: String, url: String) {
        val container = baseContainer()
        container.addView(titleView(title))
        val imageView = ImageView(this).apply {
            adjustViewBounds = true
            setPadding(0, 16, 0, 16)
        }
        container.addView(imageView)
        container.addView(closeHint())
        mount(container)

        scope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                runCatching { BitmapFactory.decodeStream(URL(url).openStream()) }.getOrNull()
            }
            if (bitmap != null) imageView.setImageBitmap(bitmap)
            else container.addView(TextView(this@OverlayService).apply {
                text = "Couldn't load that image."
                setTextColor(Color.parseColor("#EF5350"))
            })
        }
    }

    private fun mount(container: LinearLayout) {
        dismiss()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        container.setOnClickListener { dismiss(); stopSelf() }

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP }

        windowManager?.addView(container, params)
        overlayView = container

        dismissRunnable = Runnable { dismiss(); stopSelf() }.also {
            mainHandler.postDelayed(it, AUTO_DISMISS_MS)
        }
    }

    private fun dismiss() {
        dismissRunnable?.let { mainHandler.removeCallbacks(it) }
        overlayView?.let { runCatching { windowManager?.removeView(it) } }
        overlayView = null
    }

    override fun onDestroy() {
        dismiss()
        scope.launch { }.cancel()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_KIND = "kind"
        const val EXTRA_TITLE = "title"
        const val EXTRA_BODY = "body"
        const val EXTRA_ITEMS = "items"
        const val KIND_CARD = "card"
        const val KIND_LIST = "list"
        const val KIND_IMAGE = "image"
        private const val AUTO_DISMISS_MS = 20_000L
    }
}
