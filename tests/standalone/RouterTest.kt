import com.jarvis.assistant.ai.IntentType
import com.jarvis.assistant.ai.LocalIntentRouter
import com.jarvis.assistant.ai.VoiceIntent

var passed = 0
var failed = 0
val failures = mutableListOf<String>()

fun check(description: String, condition: Boolean) {
    if (condition) passed++ else { failed++; failures.add(description) }
}

fun expectType(router: LocalIntentRouter, utterance: String, expected: IntentType, label: String = utterance) {
    val result = router.route(utterance)
    check("[$label] expected type=$expected, got=${result.type}", result.type == expected)
}

fun expectTarget(router: LocalIntentRouter, utterance: String, expectedTarget: String, label: String = utterance) {
    val result = router.route(utterance)
    check("[$label] expected target=\"$expectedTarget\", got=\"${result.slots["target"]}\"", result.slots["target"] == expectedTarget)
}

fun main() {
    val router = LocalIntentRouter()

    // --- Core commands from the original spec ---
    expectType(router, "open spotify", IntentType.OPEN_APP)
    expectTarget(router, "open spotify", "spotify")
    expectType(router, "call mom", IntentType.CALL_CONTACT)
    expectTarget(router, "call mom", "mom")
    expectType(router, "text jane hey running late", IntentType.SEND_MESSAGE)
    expectType(router, "set an alarm for 7am", IntentType.SET_ALARM)
    expectType(router, "set the alarm for 7am", IntentType.SET_ALARM)
    expectType(router, "set a timer for 10 minutes", IntentType.SET_TIMER)
    expectType(router, "search for the weather", IntentType.WEB_SEARCH)
    expectType(router, "show me", IntentType.SHOW_ME)
    expectType(router, "boh", IntentType.STOP_INTERRUPT)
    expectType(router, "stop", IntentType.STOP_INTERRUPT)
    expectType(router, "thanks jarvis", IntentType.END_CONVERSATION)

    // --- Memory ---
    expectType(router, "remember dad is raj", IntentType.REMEMBER)
    expectType(router, "forget that", IntentType.FORGET)
    // "what is this" must resolve to screen explanation, NOT memory query.
    expectType(router, "what is this", IntentType.EXPLAIN_SCREEN, "what is this (must not shadow EXPLAIN_SCREEN)")
    expectType(router, "explain this page", IntentType.EXPLAIN_SCREEN)
    // QUERY_MEMORY is deliberately narrow (explicit "recall" phrasing only) — a
    // broad "what is X"/"who is X" pattern was tried and reverted because it
    // hijacked ordinary open-ended questions. Regression-test both sides of that.
    expectType(router, "what do you remember about dad", IntentType.QUERY_MEMORY)
    expectType(router, "what did i tell you to remember about the wifi password", IntentType.QUERY_MEMORY)
    expectType(router, "recall dad's name", IntentType.QUERY_MEMORY)
    expectType(router, "what's the meaning of life", IntentType.UNKNOWN_COMPLEX, "general question must NOT be hijacked by QUERY_MEMORY")
    expectType(router, "who is the president", IntentType.UNKNOWN_COMPLEX, "general question must NOT be hijacked by QUERY_MEMORY")
    expectType(router, "what's the capital of france", IntentType.UNKNOWN_COMPLEX, "general question must NOT be hijacked by QUERY_MEMORY")

    // --- Routines ---
    run {
        val result = router.route("remember when i say study mode, open spotify")
        check("[routine trigger] type=DEFINE_ROUTINE", result.type == IntentType.DEFINE_ROUTINE)
        check("[routine trigger] trigger slot", result.slots["trigger"] == "study mode")
        check("[routine trigger] firstAction slot", result.slots["firstAction"] == "open spotify")
    }

    // --- Notifications ---
    expectType(router, "what are my notifications", IntentType.READ_NOTIFICATIONS)
    expectType(router, "dismiss that", IntentType.DISMISS_NOTIFICATION)
    expectType(router, "reply to that ok be there soon", IntentType.REPLY_NOTIFICATION)

    // --- Calendar / reminders (previously the orphaned-intent bug) ---
    run {
        val result = router.route("add a dentist appointment to my calendar")
        check("[calendar] type=CALENDAR_EVENT", result.type == IntentType.CALENDAR_EVENT)
        // Regression test for the real bug fixed this pass: multi-capture-group
        // patterns were returning just the trailing keyword ("calendar") as target
        // instead of the full utterance.
        check(
            "[calendar] target must be FULL utterance, not just the matched keyword " +
                "(got: \"${result.slots["target"]}\")",
            result.slots["target"] == "add a dentist appointment to my calendar"
        )
    }
    expectType(router, "remind me to call mom in 20 minutes", IntentType.SET_REMINDER)
    run {
        val result = router.route("remind me to take pills at 5pm")
        check("[reminder] target is full utterance", result.slots["target"] == "remind me to take pills at 5pm")
    }

    // --- New tool categories added this pass ---
    expectType(router, "open camera", IntentType.OPEN_CAMERA)
    expectType(router, "take a photo with the camera", IntentType.OPEN_CAMERA)
    expectType(router, "open files", IntentType.OPEN_FILES)
    expectType(router, "open my files", IntentType.OPEN_FILES)

    expectType(router, "open settings", IntentType.OPEN_SETTINGS)
    expectTarget(router, "open settings", "", "open settings (no section -> empty target)")
    expectType(router, "open wifi settings", IntentType.OPEN_SETTINGS)
    expectTarget(router, "open wifi settings", "wifi", "open wifi settings (section captured)")

    expectType(router, "play", IntentType.MEDIA_CONTROL)
    expectTarget(router, "play", "play_pause", "play -> play_pause action")
    expectType(router, "pause", IntentType.MEDIA_CONTROL)
    expectType(router, "skip", IntentType.MEDIA_CONTROL)
    expectTarget(router, "skip", "next", "skip -> next action")
    expectType(router, "previous track", IntentType.MEDIA_CONTROL)
    expectTarget(router, "previous track", "previous", "previous track -> previous action")
    expectType(router, "stop", IntentType.STOP_INTERRUPT, "bare 'stop' must stay STOP_INTERRUPT, not MEDIA_CONTROL")
    expectType(router, "stop the music", IntentType.MEDIA_CONTROL, "explicit 'stop the music' should still route to MEDIA_CONTROL")
    expectTarget(router, "stop the music", "stop", "stop the music -> stop action")

    expectType(router, "go back", IntentType.NAVIGATE)
    expectTarget(router, "go back", "back")
    expectType(router, "go home", IntentType.NAVIGATE)
    expectTarget(router, "go home", "home")
    expectType(router, "show recents", IntentType.NAVIGATE)
    expectTarget(router, "show recents", "recents")

    // --- Pattern-ordering hazards: specific commands must beat the generic
    // OPEN_APP catch-all, since "open camera" also technically matches
    // "^(open|launch|start)\s+(.+)$" ---
    expectType(router, "open camera", IntentType.OPEN_CAMERA, "camera must not fall through to generic OPEN_APP")
    expectType(router, "open files", IntentType.OPEN_FILES, "files must not fall through to generic OPEN_APP")
    expectType(router, "open bluetooth settings", IntentType.OPEN_SETTINGS, "settings must not fall through to generic OPEN_APP")

    // --- Confidence thresholds ---
    run {
        val short = router.route("call mom")
        check("[confidence] short utterance -> HIGH", short.confidence == com.jarvis.assistant.session.ConfidenceLevel.HIGH)
        val medium = router.route("open the settings app on my phone please")
        check(
            "[confidence] medium-length utterance -> MEDIUM (got ${medium.confidence})",
            medium.confidence == com.jarvis.assistant.session.ConfidenceLevel.MEDIUM
        )
    }

    // --- Unmatched utterances fall through to cloud/local-LLM tier ---
    expectType(router, "what's the meaning of life", IntentType.UNKNOWN_COMPLEX)

    println()
    println("=== LocalIntentRouter test results ===")
    println("Passed: $passed")
    println("Failed: $failed")
    if (failures.isNotEmpty()) {
        println()
        println("Failures:")
        failures.forEach { println(" - $it") }
    }
    if (failed > 0) kotlin.system.exitProcess(1)
}
