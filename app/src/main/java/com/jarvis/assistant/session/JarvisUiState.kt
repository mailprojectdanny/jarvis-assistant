package com.jarvis.assistant.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ConversationTurn(val speaker: Speaker, val text: String)
enum class Speaker { USER, JARVIS }

/**
 * In-process shared state between JarvisForegroundService and MainActivity's UI.
 * Same process, so a simple observable singleton is the correct tool here — no IPC,
 * no extra permissions, nothing leaves the app. This is what powers the spec's
 * required main-screen elements: wake/listening status, conversation, current action.
 */
object JarvisUiState {
    val sessionState = MutableStateFlow(SessionState.STANDBY)
    val sessionActive = MutableStateFlow(false)
    val currentAction = MutableStateFlow("Standing by")
    val conversation = MutableStateFlow<List<ConversationTurn>>(emptyList())
    /** True whenever JarvisForegroundService is alive (onCreate has run, onDestroy
     *  hasn't) — independent of sessionActive, which only covers an active wake-word
     *  conversation. Lets the UI show the right Start/Stop button state even after
     *  the app process is relaunched while the foreground service is still running. */
    val serviceRunning = MutableStateFlow(false)

    private const val MAX_TURNS = 50

    fun pushUser(text: String) = append(ConversationTurn(Speaker.USER, text))
    fun pushJarvis(text: String) = append(ConversationTurn(Speaker.JARVIS, text))

    private fun append(turn: ConversationTurn) {
        conversation.value = (conversation.value + turn).takeLast(MAX_TURNS)
    }

    fun clearConversation() { conversation.value = emptyList() }
}
