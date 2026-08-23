package com.jarvis.assistant.overlay

import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * Bridges "what JARVIS just said/found" to the overlay renderer. Every spoken reply
 * that has a natural visual form gets stashed here; "Show me" / "put that on screen"
 * renders whatever is currently stashed. This is what makes examples like:
 *   "JARVIS, spell xylophone." -> "Show me." -> [card with the spelling]
 * work without JARVIS having to re-derive the content.
 */
class ShowMeController(private val context: Context) {

    @Volatile private var last: OverlayContent? = null

    fun stash(content: OverlayContent) {
        last = content
    }

    fun stashText(title: String, body: String) = stash(OverlayContent.Card(title, body))

    /** Returns false (with a reason) if there's nothing to show or overlay permission
     *  is missing — caller speaks that back rather than silently failing. */
    fun showNow(): Result<Unit> {
        if (!Settings.canDrawOverlays(context)) {
            return Result.failure(IllegalStateException("I don't have permission to draw over other apps yet — enable it in Settings."))
        }
        val content = last ?: return Result.failure(IllegalStateException("I don't have anything to show right now."))

        val intent = Intent(context, OverlayService::class.java)
        when (content) {
            is OverlayContent.Card -> {
                intent.putExtra(OverlayService.EXTRA_KIND, OverlayService.KIND_CARD)
                intent.putExtra(OverlayService.EXTRA_TITLE, content.title)
                intent.putExtra(OverlayService.EXTRA_BODY, content.body)
            }
            is OverlayContent.BulletList -> {
                intent.putExtra(OverlayService.EXTRA_KIND, OverlayService.KIND_LIST)
                intent.putExtra(OverlayService.EXTRA_TITLE, content.title)
                intent.putExtra(OverlayService.EXTRA_ITEMS, content.items.toTypedArray())
            }
            is OverlayContent.ImageUrl -> {
                intent.putExtra(OverlayService.EXTRA_KIND, OverlayService.KIND_IMAGE)
                intent.putExtra(OverlayService.EXTRA_TITLE, content.title)
                intent.putExtra(OverlayService.EXTRA_BODY, content.url)
            }
        }
        context.startService(intent)
        return Result.success(Unit)
    }

    fun clear() { last = null }
}
