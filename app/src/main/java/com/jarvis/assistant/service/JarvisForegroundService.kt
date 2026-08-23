package com.jarvis.assistant.service

import android.app.*
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.jarvis.assistant.JarvisApplication
import com.jarvis.assistant.accessibility.GlobalNavAction
import com.jarvis.assistant.accessibility.JarvisAccessibilityService
import com.jarvis.assistant.ai.*
import com.jarvis.assistant.notifications.JarvisNotificationListenerService
import com.jarvis.assistant.overlay.OverlayContent
import com.jarvis.assistant.overlay.ShowMeController
import com.jarvis.assistant.routines.RoutineCaptureSession
import com.jarvis.assistant.routines.RoutineEngine
import com.jarvis.assistant.session.JarvisUiState
import com.jarvis.assistant.session.SessionState
import com.jarvis.assistant.session.SessionStateMachine
import com.jarvis.assistant.tools.MediaAction
import com.jarvis.assistant.tools.MessageTools
import com.jarvis.assistant.tools.ReminderScheduler
import com.jarvis.assistant.tools.ToolExecutor
import com.jarvis.assistant.tools.ToolResult
import com.jarvis.assistant.util.NetworkMonitor
import com.jarvis.assistant.voice.SpeechRecognizerWakeWordDetector
import com.jarvis.assistant.voice.SpeechToText
import com.jarvis.assistant.voice.TextToSpeechManager
import com.jarvis.assistant.voice.VoskWakeWordDetector
import com.jarvis.assistant.voice.WakeWordDetector
import com.jarvis.assistant.voice.WakeWordModelManager
import kotlinx.coroutines.*
import java.util.Calendar

/**
 * Wake Word -> STT -> Context/Memory -> Local intent / Local LLM / DeepSeek
 *   -> Execute -> Verify -> TTS, with real barge-in and offline-first fallback.
 *
 * Wake-word engine selection: VoskWakeWordDetector (real, low-power, grammar-
 * constrained) is used whenever its model is installed. If the model isn't
 * installed yet (e.g. user skipped that setup step), this falls back to the
 * SpeechRecognizer-based prototype rather than not listening at all — a real
 * degraded mode, not a silent failure.
 */
class JarvisForegroundService : Service() {

    private val serviceJob = SupervisorJob()
    private val scope = CoroutineScope(serviceJob + Dispatchers.Main.immediate)

    private lateinit var session: SessionStateMachine
    private lateinit var wakeWord: WakeWordDetector
    private lateinit var stt: SpeechToText
    private lateinit var bargeInStt: SpeechToText
    private lateinit var tts: TextToSpeechManager
    private lateinit var router: LocalIntentRouter
    private lateinit var commandRouter: CommandRouter
    private lateinit var tools: ToolExecutor
    private lateinit var messageTools: MessageTools
    private lateinit var routineEngine: RoutineEngine
    private lateinit var showMe: ShowMeController
    private var localModelEngine: MediaPipeLocalModelEngine? = null

    private val recentContext = ArrayDeque<String>(6)
    private var activeRoutineCapture: RoutineCaptureSession? = null
    private var pendingConfirmation: VoiceIntent? = null
    private var bargeInterrupted = false
    private var wakeWordFallbackAttempted = false

    // SpeechRecognizer (used by SpeechToText and, when active, VoskWakeWordDetector's
    // fallback path) must be created/driven from a thread with a Looper — normally
    // main. TTS utterance-progress callbacks and Vosk's own recognition callbacks are
    // NOT guaranteed to fire on main, so every entry point that can lead to creating
    // a new recognizer or touching session state is funneled through this handler.
    private val mainHandler = Handler(Looper.getMainLooper())
    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    override fun onCreate() {
        super.onCreate()
        JarvisUiState.serviceRunning.value = true
        instance = this
        val app = application as JarvisApplication

        session = SessionStateMachine(scope, inactivityTimeoutMs = app.settingsStore.sessionTimeoutMs())

        val voskManager = WakeWordModelManager(this)
        wakeWord = if (voskManager.isModelInstalled()) {
            VoskWakeWordDetector(this, voskManager)
        } else {
            SpeechRecognizerWakeWordDetector(this)
        }

        stt = SpeechToText(this)
        bargeInStt = SpeechToText(this)
        tts = TextToSpeechManager(this) { onMain { onSpeechDone() } }
        router = LocalIntentRouter()
        tools = ToolExecutor(this)
        messageTools = MessageTools(this)
        showMe = ShowMeController(this)

        val localModelManager = LocalModelManager(this)
        localModelEngine = if (localModelManager.isModelInstalled()) {
            MediaPipeLocalModelEngine(this, localModelManager)
        } else null

        routineEngine = RoutineEngine(router, tools, app.memoryStore)

        commandRouter = CommandRouter(
            localRouter = router,
            localModel = localModelEngine,
            deepSeek = DeepSeekClient { app.settingsStore.getDeepSeekKey() },
            network = NetworkMonitor(this),
            cloudEnabled = { app.settingsStore.cloudEnabled() },
            localOnlyMode = { app.settingsStore.localOnlyMode() }
        )

        startForeground(NOTIF_ID, buildNotification("Standing by for \"Hey JARVIS\""))
        beginWakeWordLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null

    private fun beginWakeWordLoop() {
        JarvisUiState.currentAction.value = "Standing by"
        JarvisUiState.sessionActive.value = false
        JarvisUiState.sessionState.value = SessionState.STANDBY
        wakeWord.start(
            onDetected = { _ ->
                onMain {
                    wakeWord.stop()
                    session.onWakeWordDetected()
                    JarvisUiState.sessionActive.value = true
                    JarvisUiState.clearConversation()
                    updateNotification("Listening…")
                    speakThenListen("Yeah?")
                }
            },
            onError = { message ->
                // A recoverable ASR hiccup (no-match, timeout) is already handled
                // internally by both detector implementations via auto-restart. If
                // Vosk fails at a more fundamental level (corrupted model, native lib
                // issue) it stops listening entirely — falling silent forever would
                // make "always listening" false. Fall back once to the SpeechRecognizer
                // implementation rather than leaving the user with a JARVIS that never
                // wakes up again.
                if (wakeWord is VoskWakeWordDetector && !wakeWordFallbackAttempted) {
                    wakeWordFallbackAttempted = true
                    onMain {
                        wakeWord.stop()
                        wakeWord = SpeechRecognizerWakeWordDetector(this)
                        updateNotification("Standing by (fallback wake-word engine)")
                        beginWakeWordLoop()
                    }
                }
            }
        )
    }

    private fun listenForCommand() {
        session.transitionTo(SessionState.LISTENING)
        JarvisUiState.sessionState.value = SessionState.LISTENING
        JarvisUiState.currentAction.value = "Listening…"
        stt.listenOnce(
            onFinal = { text -> if (text.isNotBlank()) handleUtterance(text) else returnToListeningOrStandby() },
            onError = { returnToListeningOrStandby() }
        )
    }

    /** Speaks a reply while concurrently listening for barge-in — any speech detected
     *  while JARVIS is talking immediately cuts TTS and is treated as the next command.
     *  Saying "boh" alone does the same thing but with no follow-on command captured
     *  (matches the spec: instant interrupt, then wait fresh). */
    private fun speakThenListen(text: String) {
        bargeInterrupted = false
        JarvisUiState.sessionState.value = SessionState.SPEAKING
        JarvisUiState.currentAction.value = "Speaking"
        JarvisUiState.pushJarvis(text)
        tts.speak(text)
        bargeInStt.listenOnce(
            onPartial = { partial ->
                if (!bargeInterrupted && partial.isNotBlank() && tts.isSpeaking()) {
                    bargeInterrupted = true
                    tts.stopSpeaking()
                }
            },
            onFinal = { finalText ->
                if (bargeInterrupted && finalText.isNotBlank()) {
                    val normalized = finalText.trim().lowercase()
                    if (normalized == "boh" || normalized == "stop" || normalized == "wait") {
                        listenForCommand()
                    } else {
                        handleUtterance(finalText)
                    }
                }
                // If not interrupted, onSpeechDone() (TTS callback) drives the next listen.
            },
            onError = { /* no-op: normal TTS completion still drives flow via onSpeechDone */ }
        )
    }

    private fun handleUtterance(text: String) {
        session.onUserActivity()
        JarvisUiState.pushUser(text)
        recentContext.addLast(text)
        if (recentContext.size > 6) recentContext.removeFirst()

        // Mid-capture of a custom routine definition takes priority over normal routing.
        activeRoutineCapture?.let { capture ->
            val finished = capture.addStep(text)
            if (finished) {
                val routine = capture.build()
                (application as JarvisApplication).memoryStore.saveRoutine(routine)
                activeRoutineCapture = null
                session.transitionTo(SessionState.SPEAKING)
                speakThenListen("Saved \"${routine.trigger}\" with ${routine.actions.size} steps.")
            } else {
                session.transitionTo(SessionState.SPEAKING)
                speakThenListen("Got it, what's next?")
            }
            return
        }

        // Medium-confidence matches wait here for a yes/no before executing.
        pendingConfirmation?.let { pending ->
            pendingConfirmation = null
            val normalized = text.trim().lowercase()
            session.transitionTo(SessionState.SPEAKING)
            if (normalized in AFFIRMATIVE) {
                val reply = executeLocalIntent(pending)
                speakThenListen(reply)
            } else {
                speakThenListen("Okay, cancelled.")
            }
            return
        }

        val intent = router.route(text)

        when (intent.type) {
            IntentType.STOP_INTERRUPT -> {
                tts.stopSpeaking(); bargeInStt.cancel(); stt.cancel()
                listenForCommand()
                return
            }
            IntentType.END_CONVERSATION -> {
                session.transitionTo(SessionState.SPEAKING)
                JarvisUiState.sessionState.value = SessionState.SPEAKING
                JarvisUiState.pushJarvis("Got it. Call me if you need anything.")
                tts.speak("Got it. Call me if you need anything.")
                session.endSession()
                updateNotification("Standing by for \"Hey JARVIS\"")
                beginWakeWordLoop()
                return
            }
            IntentType.DEFINE_ROUTINE -> {
                val trigger = intent.slots["trigger"].orEmpty()
                val firstAction = intent.slots["firstAction"].orEmpty()
                val capture = RoutineCaptureSession(trigger)
                if (firstAction.isNotBlank()) capture.addStep(firstAction)
                activeRoutineCapture = capture
                session.transitionTo(SessionState.SPEAKING)
                speakThenListen("Okay, tell me the steps for \"$trigger\", then say done.")
                return
            }
            IntentType.SHOW_ME -> {
                session.transitionTo(SessionState.EXECUTING)
                val result = showMe.showNow()
                session.transitionTo(SessionState.SPEAKING)
                speakThenListen(result.fold({ "There you go." }, { it.message ?: "Couldn't show that." }))
                return
            }
            IntentType.READ_NOTIFICATIONS -> {
                session.transitionTo(SessionState.EXECUTING)
                val reply = readNotificationsSummary()
                session.transitionTo(SessionState.SPEAKING)
                speakThenListen(reply)
                return
            }
            IntentType.READ_SCREEN, IntentType.EXPLAIN_SCREEN -> {
                session.transitionTo(SessionState.EXECUTING)
                val reply = readScreenAloud()
                session.transitionTo(SessionState.SPEAKING)
                speakThenListen(reply)
                return
            }
            IntentType.CLICK_ELEMENT -> {
                session.transitionTo(SessionState.EXECUTING)
                val label = intent.slots["target"].orEmpty()
                val reply = clickScreenElement(label)
                session.transitionTo(SessionState.SPEAKING)
                speakThenListen(reply)
                return
            }
            else -> { /* fall through to router below, including routine-trigger check */ }
        }

        // Check for a saved routine trigger (e.g. "study mode") before local/cloud routing.
        val routine = routineEngine.findRoutine(text)
        if (routine != null) {
            session.transitionTo(SessionState.EXECUTING)
            val summary = routineEngine.run(routine)
            session.transitionTo(SessionState.SPEAKING)
            speakThenListen(summary)
            return
        }

        session.transitionTo(SessionState.THINKING)
        JarvisUiState.sessionState.value = SessionState.THINKING
        JarvisUiState.currentAction.value = "Thinking…"
        scope.launch {
            val outcome = commandRouter.route(text, recentContext.toList())
            val reply = when (outcome) {
                is RouteOutcome.LocalTool -> executeLocalIntent(outcome.intent)
                is RouteOutcome.NeedsConfirmation -> {
                    pendingConfirmation = outcome.intent
                    "Did you want me to ${describeIntent(outcome.intent)}? Say yes or no."
                }
                is RouteOutcome.LocalAnswer -> outcome.text
                is RouteOutcome.CloudAnswer -> outcome.text
                is RouteOutcome.NeedsClarification -> "Sorry, could you repeat that?"
                is RouteOutcome.Unavailable -> outcome.reason
            }
            if (outcome is RouteOutcome.LocalAnswer || outcome is RouteOutcome.CloudAnswer) {
                showMe.stashText("JARVIS", reply)
            }
            session.transitionTo(SessionState.SPEAKING)
            // commandRouter.route() may have run on a background dispatcher (DeepSeek's
            // IO calls, MediaPipe's Default-dispatcher inference); speakThenListen()
            // creates a SpeechRecognizer, which requires a Looper thread, so hop back
            // to main explicitly rather than assuming this coroutine is already there.
            withContext(Dispatchers.Main.immediate) { speakThenListen(reply) }
        }
    }

    private fun describeIntent(intent: VoiceIntent): String {
        val target = intent.slots["target"].orEmpty()
        return when (intent.type) {
            IntentType.OPEN_APP -> "open $target"
            IntentType.CALL_CONTACT -> "call $target"
            IntentType.SEND_MESSAGE -> "send that message"
            IntentType.SET_ALARM -> "set that alarm"
            IntentType.SET_TIMER -> "start that timer"
            IntentType.WEB_SEARCH -> "search for $target"
            IntentType.CALENDAR_EVENT -> "add that to your calendar"
            IntentType.SET_REMINDER -> "set that reminder"
            IntentType.OPEN_CAMERA -> "open the camera"
            IntentType.OPEN_FILES -> "open Files"
            IntentType.OPEN_SETTINGS -> "open settings"
            IntentType.MEDIA_CONTROL -> "control playback"
            IntentType.NAVIGATE -> "navigate"
            IntentType.QUERY_MEMORY -> "look that up"
            else -> "do that"
        }
    }

    private fun readNotificationsSummary(): String {
        if (!JarvisNotificationListenerService.isAccessGranted(this)) {
            return "I don't have notification access yet — grant it in Settings if you want me to read those."
        }
        val notifications = JarvisNotificationListenerService.currentSnapshot()
        if (notifications.isEmpty()) return "No notifications right now."
        showMe.stash(
            OverlayContent.BulletList(
                "Notifications",
                notifications.take(8).map { "${it.appLabel}: ${it.title} — ${it.text}" }
            )
        )
        val spoken = notifications.take(3).joinToString(". ") { "${it.appLabel}: ${it.title}" }
        val extra = if (notifications.size > 3) ", and ${notifications.size - 3} more. Say \"show me\" to see all of them." else "."
        return "You've got: $spoken$extra"
    }

    /** "What is this?" / "Read this." / "Explain this page." — requires the
     *  Accessibility service to be enabled; returns an honest failure otherwise
     *  rather than pretending to have read the screen. */
    private fun readScreenAloud(): String {
        val service = JarvisAccessibilityService.instance
            ?: return "I need the Accessibility service turned on to read the screen — enable it in Settings."
        val text = service.readCurrentScreenText()
        showMe.stashText("On screen", text)
        // Keep the spoken reply short; the full text is available via "show me".
        val spoken = text.lineSequence().filter { it.isNotBlank() }.take(4).joinToString(". ")
        return spoken.ifBlank { text }.take(400)
    }

    /** "Click the search button." */
    private fun clickScreenElement(label: String): String {
        val service = JarvisAccessibilityService.instance
            ?: return "I need the Accessibility service turned on to do that — enable it in Settings."
        if (label.isBlank()) return "Click what, exactly?"
        return if (service.clickByLabel(label)) "Done." else "I couldn't find \"$label\" on screen."
    }

    private fun executeLocalIntent(intent: VoiceIntent): String {
        session.transitionTo(SessionState.EXECUTING)
        JarvisUiState.sessionState.value = SessionState.EXECUTING
        JarvisUiState.currentAction.value = "Executing: ${describeIntent(intent)}"
        val target = intent.slots["target"].orEmpty()
        val app = application as JarvisApplication

        val result: ToolResult = when (intent.type) {
            IntentType.OPEN_APP -> tools.openApp(target)
            IntentType.CALL_CONTACT -> tools.callContact(target)
            IntentType.WEB_SEARCH -> tools.webSearch(target)
            IntentType.SEND_MESSAGE -> {
                // "text jane hey running late" -> split contact name from message body.
                val parts = target.split(Regex("\\s+"), limit = 2)
                if (parts.size < 2) ToolResult.Failure("I need both a contact and a message.")
                else messageTools.sendText(parts[0], parts[1])
            }
            IntentType.SET_ALARM -> parseAndSetAlarm(target)
            IntentType.SET_TIMER -> parseAndSetTimer(target)
            IntentType.REMEMBER -> {
                app.memoryStore.remember(target.substringBefore(" is").ifBlank { "note" }, target)
                ToolResult.Success("Got it, I'll remember that.")
            }
            IntentType.FORGET -> {
                app.memoryStore.forget(target)
                ToolResult.Success("Forgotten.")
            }
            IntentType.QUERY_MEMORY -> {
                val answer = recallFact(app, target)
                if (answer != null) ToolResult.Success(answer)
                else ToolResult.Failure("I don't have anything remembered about that.")
            }
            IntentType.DISMISS_NOTIFICATION -> {
                val latest = JarvisNotificationListenerService.currentSnapshot().firstOrNull()
                val svc = JarvisNotificationListenerService.instance
                if (latest != null && svc != null && svc.dismiss(latest)) ToolResult.Success("Dismissed.")
                else ToolResult.Failure("Nothing to dismiss.")
            }
            IntentType.REPLY_NOTIFICATION -> {
                val latest = JarvisNotificationListenerService.currentSnapshot().firstOrNull()
                val svc = JarvisNotificationListenerService.instance
                val replyText = intent.slots["target"].orEmpty()
                if (latest != null && svc != null && svc.replyTo(latest, replyText)) ToolResult.Success("Replied.")
                else ToolResult.Failure("That notification doesn't support a quick reply.")
            }
            IntentType.CALENDAR_EVENT -> parseAndCreateCalendarEvent(target)
            IntentType.SET_REMINDER -> parseAndScheduleReminder(target)
            IntentType.OPEN_CAMERA -> tools.openCamera()
            IntentType.OPEN_FILES -> tools.openFiles()
            IntentType.OPEN_SETTINGS -> tools.openSettings(target)
            IntentType.MEDIA_CONTROL -> tools.mediaControl(
                when (target) {
                    "next" -> MediaAction.NEXT
                    "previous" -> MediaAction.PREVIOUS
                    "stop" -> MediaAction.STOP
                    else -> MediaAction.PLAY_PAUSE
                }
            )
            IntentType.NAVIGATE -> tools.navigate(
                when (target) {
                    "back" -> GlobalNavAction.BACK
                    "home" -> GlobalNavAction.HOME
                    else -> GlobalNavAction.RECENTS
                }
            )
            else -> ToolResult.Failure("I don't have that action wired up yet.")
        }

        return when (result) {
            is ToolResult.Success -> result.message
            is ToolResult.NeedsPermission -> "I need the ${result.permission.substringAfterLast('.')} permission first — ${result.rationale}"
            is ToolResult.Failure -> "That didn't work: ${result.reason}"
        }
    }

    private fun parseAndSetAlarm(text: String): ToolResult {
        val match = Regex("""(\d{1,2})(?::(\d{2}))?\s*(am|pm)?""").find(text)
            ?: return ToolResult.Failure("I couldn't figure out a time from \"$text\".")
        var hour = match.groupValues[1].toIntOrNull() ?: return ToolResult.Failure("Bad time format.")
        val minute = match.groupValues[2].toIntOrNull() ?: 0
        val meridiem = match.groupValues[3]
        if (meridiem.equals("pm", true) && hour < 12) hour += 12
        if (meridiem.equals("am", true) && hour == 12) hour = 0
        return tools.setAlarm(hour, minute)
    }

    private fun parseAndSetTimer(text: String): ToolResult {
        val minutesMatch = Regex("""(\d+)\s*min""").find(text)
        val secondsMatch = Regex("""(\d+)\s*sec""").find(text)
        val totalSeconds = when {
            minutesMatch != null -> (minutesMatch.groupValues[1].toIntOrNull() ?: 0) * 60
            secondsMatch != null -> secondsMatch.groupValues[1].toIntOrNull() ?: 0
            else -> Regex("""\d+""").find(text)?.value?.toIntOrNull()?.times(60) ?: 0
        }
        if (totalSeconds <= 0) return ToolResult.Failure("I couldn't figure out a duration from \"$text\".")
        return tools.setTimer(totalSeconds)
    }

    /** Resolves a target trigger time from either a relative ("in 20 minutes"/"in 2
     *  hours") or absolute ("at 5pm") phrase, defaulting to null (failure) if neither
     *  is present — this is a real requirement, not a cosmetic one, since scheduling
     *  a reminder with no time is meaningless. */
    private fun resolveTriggerMillis(text: String): Long? {
        val now = System.currentTimeMillis()
        Regex("""in\s+(\d+)\s*min""").find(text)?.let { return now + it.groupValues[1].toLong() * 60_000L }
        Regex("""in\s+(\d+)\s*hour""").find(text)?.let { return now + it.groupValues[1].toLong() * 3_600_000L }
        Regex("""at\s+(\d{1,2})(?::(\d{2}))?\s*(am|pm)?""").find(text)?.let { match ->
            var hour = match.groupValues[1].toIntOrNull() ?: return null
            val minute = match.groupValues[2].toIntOrNull() ?: 0
            val meridiem = match.groupValues[3]
            if (meridiem.equals("pm", true) && hour < 12) hour += 12
            if (meridiem.equals("am", true) && hour == 12) hour = 0
            val cal = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            if (cal.timeInMillis <= now) cal.add(Calendar.DAY_OF_YEAR, 1)
            return cal.timeInMillis
        }
        return null
    }

    private fun parseAndScheduleReminder(text: String): ToolResult {
        val triggerAt = resolveTriggerMillis(text)
            ?: return ToolResult.Failure("I need a time — try \"remind me to call mom in 20 minutes\" or \"at 5pm\".")

        val reminderText = text
            .replace(Regex("""^remind me( to)?\s*""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\bin\s+\d+\s*(minutes?|min|hours?|hour)\b""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\bat\s+\d{1,2}(:\d{2})?\s*(am|pm)?\b""", RegexOption.IGNORE_CASE), "")
            .trim()
            .ifBlank { "reminder" }

        return ReminderScheduler(this).schedule(reminderText, triggerAt)
    }

    private fun parseAndCreateCalendarEvent(text: String): ToolResult {
        // Real event creation, but deliberately opens the Calendar app's own
        // pre-filled "add event" screen rather than silently writing to the
        // calendar — the user still taps Save, which is the right amount of
        // friction for something that permanently modifies their calendar.
        val startMillis = resolveTriggerMillis(text) ?: (System.currentTimeMillis() + 3_600_000L)
        val endMillis = startMillis + 3_600_000L // default 1-hour duration

        val title = text
            .replace(Regex("""^(add|create|schedule)\s+""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\b(an?|the)\s+(event|meeting)\b""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\bin\s+(my\s+)?calendar\b""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\bat\s+\d{1,2}(:\d{2})?\s*(am|pm)?\b""", RegexOption.IGNORE_CASE), "")
            .trim()
            .ifBlank { "New event" }

        return tools.createCalendarEvent(title, startMillis, endMillis)
    }

    /** Backs the QUERY_MEMORY intent — MemoryStore.recall() existed but was never
     *  wired to any voice command, so "remember X" had no way to be asked back.
     *  Tries an exact key match first, then a tolerant substring match against
     *  everything remembered, since the key derived at REMEMBER time ("dad" from
     *  "remember dad is raj") won't always exactly equal how it's later asked about
     *  ("what's dad's name" vs "who is dad"). */
    private fun recallFact(app: JarvisApplication, query: String): String? {
        app.memoryStore.recall(query)?.let { return it }
        val normalizedQuery = query.lowercase()
        return app.memoryStore.allFacts()
            .firstOrNull { fact ->
                normalizedQuery.contains(fact.key.lowercase()) || fact.key.lowercase().contains(normalizedQuery)
            }
            ?.value
    }

    private fun onSpeechDone() {
        if (bargeInterrupted) return // barge-in path already decided what happens next
        bargeInStt.cancel()
        if (!session.sessionActive.value) return
        listenForCommand()
    }

    private fun returnToListeningOrStandby() {
        if (session.sessionActive.value) listenForCommand()
    }

    private fun buildNotification(text: String): Notification {
        val channelId = "jarvis_session"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "JARVIS session", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NotificationManager::class.java)).createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("JARVIS")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        (getSystemService(NotificationManager::class.java)).notify(NOTIF_ID, buildNotification(text))
    }

    override fun onDestroy() {
        JarvisUiState.serviceRunning.value = false
        instance = null
        wakeWord.stop()
        stt.release()
        bargeInStt.release()
        tts.shutdown()
        localModelEngine?.release()
        serviceJob.cancel()
        super.onDestroy()
    }

    companion object {
        const val NOTIF_ID = 42
        private val AFFIRMATIVE = setOf("yes", "yeah", "yep", "correct", "do it", "sure", "please")

        var instance: JarvisForegroundService? = null
            private set
    }

    /** Applies a new inactivity timeout to the currently-running session immediately
     *  — without this, SessionStateMachine.setInactivityTimeout() (which exists
     *  precisely for this) was dead code, and a Settings change silently only took
     *  effect on the next service restart. */
    fun updateInactivityTimeout(ms: Long) {
        session.setInactivityTimeout(ms)
    }
}
