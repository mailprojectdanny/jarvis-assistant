package com.jarvis.assistant.ai

import com.jarvis.assistant.session.ConfidenceLevel

/**
 * Fast on-device intent detection. Handles the "never send simple device commands
 * to DeepSeek" rule from the spec: anything that matches a known local intent is
 * executed immediately without any network call.
 *
 * v1 is rule/pattern based (fast, zero-dependency, fully offline) so the app is
 * useful immediately. The setup wizard's "download local AI model" step hooks in
 * a small on-device LLM (e.g. gguf model via llama.cpp/MediaPipe LLM Inference)
 * for fuzzier intent matching + short local answers — swap point is [LocalModelEngine].
 */
// Named VoiceIntent (not Intent) specifically to avoid colliding with android.content.Intent
// in any file that needs both (e.g. the foreground service, which launches real Android
// intents AND routes voice intents).
data class VoiceIntent(
    val type: IntentType,
    val slots: Map<String, String> = emptyMap(),
    val confidence: ConfidenceLevel
)

enum class IntentType {
    OPEN_APP, CALL_CONTACT, SEND_MESSAGE, SET_ALARM, SET_TIMER, SET_REMINDER,
    CALENDAR_EVENT, WEB_SEARCH, SHOW_ME, READ_SCREEN, CLICK_ELEMENT, EXPLAIN_SCREEN,
    REMEMBER, FORGET, QUERY_MEMORY, STOP_INTERRUPT, END_CONVERSATION,
    DEFINE_ROUTINE, READ_NOTIFICATIONS, DISMISS_NOTIFICATION, REPLY_NOTIFICATION,
    OPEN_CAMERA, OPEN_FILES, MEDIA_CONTROL, NAVIGATE, OPEN_SETTINGS,
    UNKNOWN_COMPLEX
}

class LocalIntentRouter {

    private val patterns: List<Pair<Regex, IntentType>> = listOf(
        // Specific-app-like commands must come before the generic OPEN_APP catch-all,
        // since "open camera" / "open files" / "open wifi settings" would otherwise
        // resolve as a (worse) generic app-name search.
        Regex("""^(open|launch|take a (photo|picture)( with)?)\s*(the\s+)?camera$""") to IntentType.OPEN_CAMERA,
        Regex("""^open\s+(my\s+)?files$""") to IntentType.OPEN_FILES,
        Regex("""^open\s+(?:(.+)\s+)?settings$""") to IntentType.OPEN_SETTINGS,
        Regex("""^(play|resume)( music)?$""") to IntentType.MEDIA_CONTROL,
        Regex("""^pause( music)?$""") to IntentType.MEDIA_CONTROL,
        Regex("""^stop\s+(the\s+)?(music|playback|song)$""") to IntentType.MEDIA_CONTROL,
        Regex("""^(skip|next)( track| song)?$""") to IntentType.MEDIA_CONTROL,
        Regex("""^(previous|last)( track| song)?$""") to IntentType.MEDIA_CONTROL,
        Regex("""^go back$""") to IntentType.NAVIGATE,
        Regex("""^(go home|home screen)$""") to IntentType.NAVIGATE,
        Regex("""^(recent apps|show recents|recents)$""") to IntentType.NAVIGATE,

        Regex("""^(open|launch|start)\s+(.+)$""") to IntentType.OPEN_APP,
        Regex("""^call\s+(.+)$""") to IntentType.CALL_CONTACT,
        Regex("""^(text|message|send a message to)\s+(.+)$""") to IntentType.SEND_MESSAGE,
        Regex("""^(set\s+(an?\s+|the\s+)?|wake me(\s+up)?\s+)?alarm.*""") to IntentType.SET_ALARM,
        Regex("""^(set |start )?(a )?timer.*""") to IntentType.SET_TIMER,
        Regex("""^remind me.*""") to IntentType.SET_REMINDER,
        Regex("""^(add|create|schedule).*(event|meeting|calendar).*""") to IntentType.CALENDAR_EVENT,
        Regex("""^(search|google|look up)\s+(.+)$""") to IntentType.WEB_SEARCH,
        Regex("""^(show me|put that on screen).*""") to IntentType.SHOW_ME,
        Regex("""^(read this|what does this say).*""") to IntentType.READ_SCREEN,
        Regex("""^click (the )?(.+)$""") to IntentType.CLICK_ELEMENT,
        Regex("""^(what is this|explain this( page)?).*""") to IntentType.EXPLAIN_SCREEN,
        // Deliberately narrow: only explicit "check your memory" phrasing routes here.
        // A broad "what is X"/"who is X" pattern was tried and reverted — it hijacked
        // ordinary open-ended questions ("what's the meaning of life") that should
        // reach the local LLM/DeepSeek tier instead, which is a worse outcome than
        // leaving recall() reachable only through explicit phrasing.
        Regex("""^(what do you remember about|what did i (tell you to remember about|say about)|recall)\s+(.+?)\??$""") to IntentType.QUERY_MEMORY,
        // Must be checked before the generic REMEMBER pattern below.
        Regex("""^remember when i say (.+?),\s*(.+)$""") to IntentType.DEFINE_ROUTINE,
        Regex("""^remember\s+(.+)$""") to IntentType.REMEMBER,
        Regex("""^forget\s+(that|.+)$""") to IntentType.FORGET,
        Regex("""^(boh|stop|wait|hold on)$""") to IntentType.STOP_INTERRUPT,
        Regex("""^(that's all|thanks jarvis|bye jarvis|nothing else)$""") to IntentType.END_CONVERSATION,
        Regex("""^(what are my|read my|check my) notifications.*""") to IntentType.READ_NOTIFICATIONS,
        Regex("""^dismiss (that|it|the .+)$""") to IntentType.DISMISS_NOTIFICATION,
        Regex("""^reply(\s+to (that|it))?\s+(.+)$""") to IntentType.REPLY_NOTIFICATION
    )

    fun route(utterance: String): VoiceIntent {
        val normalized = utterance.trim().lowercase()
        val confidence = when {
            normalized.length < 25 -> ConfidenceLevel.HIGH
            normalized.length < 60 -> ConfidenceLevel.MEDIUM
            else -> ConfidenceLevel.LOW
        }
        for ((pattern, type) in patterns) {
            val match = pattern.find(normalized) ?: continue

            if (type == IntentType.DEFINE_ROUTINE) {
                val trigger = match.groupValues.getOrNull(1)?.trim().orEmpty()
                val firstAction = match.groupValues.getOrNull(2)?.trim().orEmpty()
                return VoiceIntent(
                    type,
                    mapOf("trigger" to trigger, "firstAction" to firstAction),
                    ConfidenceLevel.HIGH
                )
            }

            if (type == IntentType.MEDIA_CONTROL) {
                val action = when {
                    normalized.startsWith("play") || normalized.startsWith("resume") || normalized.startsWith("pause") -> "play_pause"
                    normalized.startsWith("stop") -> "stop"
                    normalized.startsWith("skip") || normalized.startsWith("next") -> "next"
                    else -> "previous"
                }
                return VoiceIntent(type, mapOf("target" to action), ConfidenceLevel.HIGH)
            }

            if (type == IntentType.NAVIGATE) {
                val action = when {
                    normalized.contains("back") -> "back"
                    normalized.contains("home") -> "home"
                    else -> "recents"
                }
                return VoiceIntent(type, mapOf("target" to action), ConfidenceLevel.HIGH)
            }

            if (type == IntentType.OPEN_SETTINGS) {
                val section = match.groupValues.getOrNull(1)?.trim().orEmpty()
                return VoiceIntent(type, mapOf("target" to section), confidence)
            }

            // These patterns have multiple capture groups purely for matching (e.g.
            // "(add|create|schedule).*(event|meeting|calendar)"), so the generic
            // "last non-blank group" extraction below would wrongly pick a keyword
            // fragment instead of the full request. Use the whole utterance instead.
            if (type == IntentType.CALENDAR_EVENT || type == IntentType.SET_REMINDER) {
                return VoiceIntent(type, mapOf("target" to normalized), confidence)
            }

            val slot = match.groupValues.lastOrNull { it.isNotBlank() }?.let { mapOf("target" to it) } ?: emptyMap()
            return VoiceIntent(type, slot, confidence)
        }
        // Nothing matched locally -> route to DeepSeek for a real answer.
        return VoiceIntent(IntentType.UNKNOWN_COMPLEX, mapOf("query" to utterance), ConfidenceLevel.LOW)
    }
}

/** Swap-point for a real on-device model (llama.cpp gguf / MediaPipe LLM Inference API). */
interface LocalModelEngine {
    suspend fun isInstalled(): Boolean
    suspend fun downloadModel(onProgress: (Int) -> Unit): Boolean
    suspend fun classifyOrAnswer(utterance: String, context: List<String>): String?
}
