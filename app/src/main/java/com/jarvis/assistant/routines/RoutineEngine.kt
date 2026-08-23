package com.jarvis.assistant.routines

import com.jarvis.assistant.ai.IntentType
import com.jarvis.assistant.ai.LocalIntentRouter
import com.jarvis.assistant.memory.MemoryStore
import com.jarvis.assistant.memory.RoutineCommand
import com.jarvis.assistant.tools.ToolExecutor
import com.jarvis.assistant.tools.ToolResult

/**
 * "Remember when I say study mode, open these apps" style custom routines.
 * Two halves:
 *  - RoutineCaptureSession: a short conversational state machine that collects the
 *    steps the user lists after defining a trigger, until they say "done"/"that's it".
 *  - RoutineEngine: executes a saved routine by replaying each stored step through
 *    the same local intent router + tool executor used for live commands, so a
 *    routine step behaves identically to the user saying it live.
 */
class RoutineCaptureSession(private val trigger: String) {
    private val steps = mutableListOf<String>()

    private val endPhrases = setOf("done", "that's it", "that's all", "save it", "save routine")

    /** Returns true if this utterance ended the capture (caller should then persist). */
    fun addStep(utterance: String): Boolean {
        val normalized = utterance.trim().lowercase()
        if (normalized in endPhrases) return true
        steps.add(utterance.trim())
        return false
    }

    fun build(): RoutineCommand = RoutineCommand(trigger = trigger, actions = steps.toList())
    fun stepCount(): Int = steps.size
}

class RoutineEngine(
    private val router: LocalIntentRouter,
    private val toolExecutor: ToolExecutor,
    private val memoryStore: MemoryStore
) {
    /** Matches "study mode" against saved routine triggers, case-insensitively. */
    fun findRoutine(utterance: String): RoutineCommand? =
        memoryStore.getRoutine(utterance.trim())

    /** Runs every stored step, returns a short human summary of what happened —
     *  never silently swallows a failed step. */
    fun run(routine: RoutineCommand): String {
        if (routine.actions.isEmpty()) return "That routine doesn't have any steps saved."
        val results = routine.actions.map { step -> step to executeStep(step) }
        val failures = results.filterNot { it.second }
        return if (failures.isEmpty()) {
            "Done — ran all ${routine.actions.size} steps for \"${routine.trigger}\"."
        } else {
            "Ran \"${routine.trigger}\", but ${failures.size} step(s) didn't complete: " +
                failures.joinToString(", ") { it.first }
        }
    }

    private fun executeStep(step: String): Boolean {
        val intent = router.route(step)
        val result = when (intent.type) {
            IntentType.OPEN_APP -> toolExecutor.openApp(intent.slots["target"].orEmpty())
            IntentType.CALL_CONTACT -> toolExecutor.callContact(intent.slots["target"].orEmpty())
            IntentType.WEB_SEARCH -> toolExecutor.webSearch(intent.slots["target"].orEmpty())
            else -> ToolResult.Failure("Unsupported routine step type")
        }
        return result is ToolResult.Success
    }
}
