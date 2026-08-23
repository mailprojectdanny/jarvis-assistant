package com.jarvis.assistant.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Implements the required lifecycle:
 *
 *   Standby --(wake word)--> Active --(timeout / clear end-of-conversation)--> Standby
 *
 * Only the wake-word detector may move Standby -> Active. Once Active, no wake word
 * is required again until the session times out or is explicitly ended.
 */
enum class SessionState { STANDBY, LISTENING, THINKING, EXECUTING, SPEAKING }

enum class ConfidenceLevel { HIGH, MEDIUM, LOW }

class SessionStateMachine(
    private val scope: CoroutineScope,
    private var inactivityTimeoutMs: Long = 20_000L
) {
    private val _state = MutableStateFlow(SessionState.STANDBY)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private val _sessionActive = MutableStateFlow(false)
    val sessionActive: StateFlow<Boolean> = _sessionActive.asStateFlow()

    private var timeoutJob: Job? = null

    fun setInactivityTimeout(ms: Long) {
        inactivityTimeoutMs = ms
    }

    /** Called only by the wake-word detector. */
    fun onWakeWordDetected() {
        _sessionActive.value = true
        _state.value = SessionState.LISTENING
        resetInactivityTimer()
    }

    /** Called after every user utterance/command while a session is active. */
    fun onUserActivity() {
        if (!_sessionActive.value) return
        resetInactivityTimer()
    }

    fun transitionTo(newState: SessionState) {
        if (!_sessionActive.value && newState != SessionState.STANDBY) return
        _state.value = newState
        if (newState != SessionState.STANDBY) resetInactivityTimer()
    }

    /** Call when NLU/dialogue logic detects the conversation has clearly concluded
     *  (e.g. "thanks, that's all", "bye jarvis", or a long silent pause). */
    fun endSession() {
        timeoutJob?.cancel()
        _sessionActive.value = false
        _state.value = SessionState.STANDBY
    }

    private fun resetInactivityTimer() {
        timeoutJob?.cancel()
        timeoutJob = scope.launch {
            delay(inactivityTimeoutMs)
            endSession()
        }
    }
}
