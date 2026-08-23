# Standalone logic tests

These verify the real, unmodified production logic for the two pieces of this
codebase that have zero Android/coroutines dependencies once their one or two
external types are stubbed: `LocalIntentRouter` (regex command classification)
and `RoutineCaptureSession` (the "remember when I say X..." capture state
machine). They're not JUnit/instrumented tests — no Android SDK is available in
every environment this might be reviewed in — just a real Kotlin compiler run
against real source.

## Running them

You need `kotlinc` matching (or newer than) this project's Kotlin version (see
root `build.gradle.kts` — currently 1.9.24).

```bash
# LocalIntentRouter
mkdir -p /tmp/router_test && cd /tmp/router_test
cp <path-to-project>/app/src/main/java/com/jarvis/assistant/ai/LocalIntentRouter.kt .
cat > ConfidenceLevelStub.kt << 'STUB'
package com.jarvis.assistant.session
enum class ConfidenceLevel { HIGH, MEDIUM, LOW }
STUB
cp <path-to-project>/tests/standalone/RouterTest.kt .
kotlinc *.kt -include-runtime -d test.jar && java -jar test.jar

# RoutineCaptureSession
mkdir -p /tmp/routine_test && cd /tmp/routine_test
# extract just the RoutineCaptureSession class from RoutineEngine.kt (it's one
# of two classes in that file; the other, RoutineEngine itself, needs the real
# Android-backed ToolExecutor/MemoryStore and isn't unit-testable this way)
cat > RoutineCommandStub.kt << 'STUB'
package com.jarvis.assistant.memory
data class RoutineCommand(val trigger: String, val actions: List<String>)
STUB
cp <path-to-project>/tests/standalone/RoutineCaptureSessionTest.kt .
# then paste the RoutineCaptureSession class body from RoutineEngine.kt into
# a RoutineCaptureSession.kt with `package com.jarvis.assistant.routines` and
# `import com.jarvis.assistant.memory.RoutineCommand` at the top
kotlinc *.kt -include-runtime -d test.jar && java -jar test.jar
```

Last run (against production source, Kotlin 1.9.24): 63/63 and 16/16 passing.

Everything else in this codebase — services, UI, tool execution against real
system APIs — depends on the Android framework and can only be verified with a
real device/emulator via `./gradlew connectedAndroidTest` or by running the app.
