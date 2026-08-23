package com.jarvis.assistant.ai

import com.jarvis.assistant.session.ConfidenceLevel
import com.jarvis.assistant.util.NetworkMonitor

sealed class RouteOutcome {
    data class LocalTool(val intent: VoiceIntent) : RouteOutcome()
    /** Medium confidence: brief "did you mean X?" confirmation before executing. */
    data class NeedsConfirmation(val intent: VoiceIntent) : RouteOutcome()
    /** Low confidence: ask the user to repeat rather than guess. */
    data class LocalAnswer(val text: String) : RouteOutcome()
    data class CloudAnswer(val text: String) : RouteOutcome()
    data class NeedsClarification(val heard: String) : RouteOutcome()
    data class Unavailable(val reason: String) : RouteOutcome()
}

/**
 * Single entry point implementing the required pipeline order:
 *   Local regex intent match (fast path, never leaves device)
 *     -> Local LLM classification/answer (still fully offline)
 *       -> DeepSeek (only for genuinely open-ended reasoning, AND only if online
 *          and cloud is enabled, AND not in Local Only Mode)
 *
 * This is the offline-first fallback: connectivity and settings are checked before
 * ever attempting a network call, and every tier before DeepSeek is exhausted first.
 */
class CommandRouter(
    private val localRouter: LocalIntentRouter,
    private val localModel: LocalModelEngine?,
    private val deepSeek: DeepSeekClient,
    private val network: NetworkMonitor,
    private val cloudEnabled: () -> Boolean,
    private val localOnlyMode: () -> Boolean
) {
    suspend fun route(utterance: String, recentContext: List<String>): RouteOutcome {
        val intent = localRouter.route(utterance)

        if (intent.type != IntentType.UNKNOWN_COMPLEX) {
            return when (intent.confidence) {
                ConfidenceLevel.HIGH -> RouteOutcome.LocalTool(intent)
                ConfidenceLevel.MEDIUM -> RouteOutcome.NeedsConfirmation(intent)
                ConfidenceLevel.LOW -> RouteOutcome.NeedsClarification(utterance)
            }
        }

        // Tier 2: fully offline local model, if installed.
        if (localModel != null && localModel.isInstalled()) {
            val answer = localModel.classifyOrAnswer(utterance, recentContext)
            if (!answer.isNullOrBlank()) return RouteOutcome.LocalAnswer(answer)
        }

        // Tier 3: DeepSeek, only when genuinely eligible.
        if (localOnlyMode()) {
            return RouteOutcome.Unavailable(
                "I'm in Local Only Mode, so I can't reach the cloud for that — turn it off in Settings if you want me to."
            )
        }
        if (!cloudEnabled()) {
            return RouteOutcome.Unavailable("Cloud answers are turned off in Settings.")
        }
        if (!network.isOnline()) {
            return RouteOutcome.Unavailable(
                "No connection right now, and that's beyond what I can do offline — I'll need a network to answer that."
            )
        }

        return when (val result = deepSeek.ask(utterance, recentContext)) {
            is DeepSeekClient.Result.Success -> RouteOutcome.CloudAnswer(result.text)
            is DeepSeekClient.Result.NoApiKey -> RouteOutcome.Unavailable(
                "I don't have a DeepSeek key set up yet — add one in Settings for questions like that."
            )
            is DeepSeekClient.Result.Failure -> RouteOutcome.Unavailable("I couldn't reach DeepSeek just now: ${result.reason}")
        }
    }
}
