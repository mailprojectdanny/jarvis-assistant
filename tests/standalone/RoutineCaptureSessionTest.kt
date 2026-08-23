import com.jarvis.assistant.routines.RoutineCaptureSession

var passed = 0
var failed = 0
val failures = mutableListOf<String>()

fun check(description: String, condition: Boolean) {
    if (condition) passed++ else { failed++; failures.add(description) }
}

fun main() {
    // Normal capture flow, ended with "done".
    run {
        val session = RoutineCaptureSession("study mode")
        check("step 1 not end phrase", !session.addStep("open spotify"))
        check("step 2 not end phrase", !session.addStep("open notion"))
        check("'done' ends capture", session.addStep("done"))
        val routine = session.build()
        check("trigger preserved", routine.trigger == "study mode")
        check("actions in order", routine.actions == listOf("open spotify", "open notion"))
        check("stepCount before end matches", session.stepCount() == 2)
    }

    // Every documented end phrase should terminate capture.
    listOf("done", "that's it", "that's all", "save it", "save routine").forEach { endPhrase ->
        val session = RoutineCaptureSession("trigger")
        session.addStep("step one")
        check("'$endPhrase' ends capture", session.addStep(endPhrase))
    }

    // End-phrase matching should be case-insensitive and whitespace-tolerant,
    // since it's matched against real spoken/transcribed text.
    run {
        val session = RoutineCaptureSession("trigger")
        check("'DONE' (uppercase) ends capture", session.addStep("  DONE  "))
    }

    // A step whose text happens to contain an end phrase as a substring must
    // NOT be treated as ending capture — only an exact (trimmed/lowercased) match.
    run {
        val session = RoutineCaptureSession("trigger")
        check("'mark it as done later' is a real step, not an end phrase", !session.addStep("mark it as done later"))
        check("captured as a literal step", session.build().actions == listOf("mark it as done later"))
    }

    // Empty routine (immediately says "done") should build with zero actions
    // rather than erroring — RoutineEngine.run() already handles the empty case.
    run {
        val session = RoutineCaptureSession("empty routine")
        check("immediate 'done' ends capture", session.addStep("done"))
        check("zero actions captured", session.build().actions.isEmpty())
    }

    println()
    println("=== RoutineCaptureSession test results ===")
    println("Passed: $passed")
    println("Failed: $failed")
    if (failures.isNotEmpty()) {
        println()
        println("Failures:")
        failures.forEach { println(" - $it") }
    }
    if (failed > 0) kotlin.system.exitProcess(1)
}
