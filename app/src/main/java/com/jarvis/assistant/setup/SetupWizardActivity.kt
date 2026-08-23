package com.jarvis.assistant.setup

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.jarvis.assistant.JarvisApplication
import com.jarvis.assistant.ai.LocalModelManager
import com.jarvis.assistant.notifications.JarvisNotificationListenerService
import com.jarvis.assistant.voice.TextToSpeechManager
import com.jarvis.assistant.voice.WakeWordModelManager
import kotlinx.coroutines.launch

/**
 * Implements the FIRST INSTALL flow from the spec, fully wired to real components:
 * 1. Privacy explainer
 * 2. Local wake-word model download (Vosk, real progress, real network call)
 * 3. Wake word/voice explainer
 * 4. Permissions, requested one at a time with rationale, real system dialogs
 * 5. Overlay permission (real Settings deep link)
 * 6. Optional DeepSeek key + optional local LLM import (real SAF file picker)
 * 7. System test (actually speaks via real TTS to confirm the pipeline works)
 * 8. Final permission/status dashboard reflecting real granted/denied state
 */
private enum class Step {
    PRIVACY, WAKE_MODEL, VOICE, PERMISSIONS, OVERLAY, NOTIFICATIONS,
    LOCAL_LLM, DEEPSEEK, TEST, DASHBOARD
}

private data class PermissionSpec(val permission: String, val title: String, val rationale: String)

class SetupWizardActivity : ComponentActivity() {

    private val runtimePermissions = listOf(
        PermissionSpec(Manifest.permission.RECORD_AUDIO, "Microphone", "Needed for wake-word listening and every voice command."),
        PermissionSpec(Manifest.permission.CALL_PHONE, "Phone", "Lets JARVIS place calls when you ask it to call someone."),
        PermissionSpec(Manifest.permission.READ_CONTACTS, "Contacts", "Lets JARVIS resolve names like \"call Mom\" to a number."),
        PermissionSpec(Manifest.permission.SEND_SMS, "SMS", "Lets JARVIS send texts you dictate."),
        // Calendar events and the camera are both opened via the Calendar/Camera
        // app's own screen (an implicit intent the user completes themselves),
        // which is why those two don't need a runtime permission of their own here.
    ) + if (Build.VERSION.SDK_INT >= 33) listOf(
        PermissionSpec(Manifest.permission.POST_NOTIFICATIONS, "Notifications", "Needed for JARVIS's own persistent status notification and reminders."),
    ) else emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as JarvisApplication
        val wakeModelManager = WakeWordModelManager(this)
        val localModelManager = LocalModelManager(this)

        setContent {
            var step by remember { mutableStateOf(Step.PRIVACY) }
            var permissionIndex by remember { mutableIntStateOf(0) }
            var downloadProgress by remember { mutableIntStateOf(if (wakeModelManager.isModelInstalled()) 100 else 0) }
            var downloadError by remember { mutableStateOf<String?>(null) }
            var isDownloading by remember { mutableStateOf(false) }
            var testStatus by remember { mutableStateOf("Not run yet") }
            val scope = rememberCoroutineScope()

            val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
                permissionIndex++
            }

            val modelPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
                if (uri != null) {
                    localModelManager.importModel(uri)
                    com.jarvis.assistant.service.JarvisServiceController.restartIfRunning(this@SetupWizardActivity)
                }
            }

            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF5C6BC0))) {
                Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF0A0F1E)) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        when (step) {
                            Step.PRIVACY -> WizardPage(
                                title = "Your data stays on this phone",
                                body = "Conversations, memory, contacts, files, and screen data are stored locally and encrypted. Nothing is uploaded unless you explicitly ask a complex question that's sent to DeepSeek — and even then, only the question itself.",
                                cta = "Continue"
                            ) { step = Step.WAKE_MODEL }

                            Step.WAKE_MODEL -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("Local wake-word engine", style = MaterialTheme.typography.headlineSmall, color = Color.White)
                                Spacer(Modifier.height(16.dp))
                                Text(
                                    "JARVIS needs a small offline speech model (~${WakeWordModelManager.MODEL_SIZE_MB}MB) so \"Hey JARVIS\" works fully on-device, with no cloud round-trip. Downloaded once, used forever.",
                                    color = Color(0xFFB0BEC5)
                                )
                                Spacer(Modifier.height(24.dp))
                                if (isDownloading) {
                                    LinearProgressIndicator(progress = { downloadProgress / 100f }, modifier = Modifier.fillMaxWidth(0.8f))
                                    Spacer(Modifier.height(8.dp))
                                    Text("$downloadProgress%", color = Color(0xFFB0BEC5))
                                } else if (wakeModelManager.isModelInstalled()) {
                                    Text("✓ Installed", color = Color(0xFF66BB6A))
                                }
                                downloadError?.let {
                                    Spacer(Modifier.height(8.dp))
                                    Text(it, color = Color(0xFFEF5350))
                                }
                                Spacer(Modifier.height(24.dp))
                                Row {
                                    Button(onClick = {
                                        isDownloading = true
                                        downloadError = null
                                        scope.launch {
                                            val result = wakeModelManager.ensureModel { pct -> downloadProgress = pct }
                                            isDownloading = false
                                            result.onFailure { e -> downloadError = e.message ?: "Download failed." }
                                            if (result.isSuccess) {
                                                com.jarvis.assistant.service.JarvisServiceController.restartIfRunning(this@SetupWizardActivity)
                                                step = Step.VOICE
                                            }
                                        }
                                    }, enabled = !isDownloading && !wakeModelManager.isModelInstalled()) {
                                        Text(if (wakeModelManager.isModelInstalled()) "Installed" else "Download now")
                                    }
                                    Spacer(Modifier.width(12.dp))
                                    TextButton(onClick = { step = Step.VOICE }) {
                                        Text("Skip (uses a less efficient fallback)")
                                    }
                                }
                                if (wakeModelManager.isModelInstalled()) {
                                    Spacer(Modifier.height(12.dp))
                                    Button(onClick = { step = Step.VOICE }) { Text("Continue") }
                                }
                            }

                            Step.VOICE -> WizardPage(
                                title = "Wake word & voice",
                                body = "Say \"Hey JARVIS\", \"Yo JARVIS\", or just \"JARVIS\" to start a conversation. You won't need to repeat it for follow-up commands — say \"boh\" any time to interrupt instantly.",
                                cta = "Continue"
                            ) { step = Step.PERMISSIONS }

                            Step.PERMISSIONS -> {
                                if (permissionIndex >= runtimePermissions.size) {
                                    LaunchedEffect(Unit) { step = Step.OVERLAY }
                                } else {
                                    val spec = runtimePermissions[permissionIndex]
                                    val alreadyGranted = ContextCompat.checkSelfPermission(this@SetupWizardActivity, spec.permission) == PackageManager.PERMISSION_GRANTED
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text("Permission ${permissionIndex + 1} of ${runtimePermissions.size}", color = Color(0xFF5C6BC0))
                                        Spacer(Modifier.height(8.dp))
                                        Text(spec.title, style = MaterialTheme.typography.headlineSmall, color = Color.White)
                                        Spacer(Modifier.height(12.dp))
                                        Text(spec.rationale, color = Color(0xFFB0BEC5))
                                        Spacer(Modifier.height(24.dp))
                                        if (alreadyGranted) {
                                            Text("✓ Already granted", color = Color(0xFF66BB6A))
                                            Spacer(Modifier.height(12.dp))
                                            Button(onClick = { permissionIndex++ }) { Text("Continue") }
                                        } else {
                                            Row {
                                                Button(onClick = { permissionLauncher.launch(spec.permission) }) { Text("Grant") }
                                                Spacer(Modifier.width(12.dp))
                                                TextButton(onClick = { permissionIndex++ }) { Text("Skip") }
                                            }
                                        }
                                    }
                                }
                            }

                            Step.OVERLAY -> WizardPage(
                                title = "\"Show me\" overlay",
                                body = "To display visual cards over your current app, JARVIS needs the display-over-other-apps permission. You can enable it now or skip and turn it on later in Settings.",
                                cta = if (Settings.canDrawOverlays(this@SetupWizardActivity)) "Continue" else "Open permission screen"
                            ) {
                                if (!Settings.canDrawOverlays(this@SetupWizardActivity)) {
                                    runCatching {
                                        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                                    }
                                }
                                step = Step.NOTIFICATIONS
                            }

                            Step.NOTIFICATIONS -> WizardPage(
                                title = "Notification access (optional)",
                                body = "To let JARVIS read or reply to notifications when you ask, grant Notification Access. This is a special Android permission you grant manually — JARVIS never gets it silently.",
                                cta = if (JarvisNotificationListenerService.isAccessGranted(this@SetupWizardActivity)) "Continue" else "Open permission screen"
                            ) {
                                if (!JarvisNotificationListenerService.isAccessGranted(this@SetupWizardActivity)) {
                                    runCatching {
                                        startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
                                    }
                                }
                                step = Step.LOCAL_LLM
                            }

                            Step.LOCAL_LLM -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("On-device LLM (optional)", style = MaterialTheme.typography.headlineSmall, color = Color.White)
                                Spacer(Modifier.height(16.dp))
                                Text(
                                    "For fuzzier offline understanding and short local answers, JARVIS can use a small local language model. Because the model license (e.g. Gemma) requires a manual accept, download it once from the model page in your browser, then pick the .task file here.",
                                    color = Color(0xFFB0BEC5)
                                )
                                Spacer(Modifier.height(16.dp))
                                if (localModelManager.isModelInstalled()) {
                                    Text("✓ Local model installed", color = Color(0xFF66BB6A))
                                }
                                Spacer(Modifier.height(16.dp))
                                Row {
                                    Button(onClick = {
                                        runCatching {
                                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(LocalModelManager.MODEL_INFO_URL)))
                                        }
                                    }) { Text("Get model") }
                                    Spacer(Modifier.width(12.dp))
                                    Button(onClick = { modelPickerLauncher.launch(arrayOf("*/*")) }) { Text("Pick file") }
                                }
                                Spacer(Modifier.height(16.dp))
                                TextButton(onClick = { step = Step.DEEPSEEK }) { Text("Continue") }
                            }

                            Step.DEEPSEEK -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                var key by remember { mutableStateOf("") }
                                var showKey by remember { mutableStateOf(false) }
                                Text("DeepSeek (optional)", style = MaterialTheme.typography.headlineSmall, color = Color.White)
                                Spacer(Modifier.height(16.dp))
                                Text(
                                    "Add a DeepSeek API key for complex cloud answers. Simple device commands never use the cloud. You can add, test, or delete this anytime in Settings.",
                                    color = Color(0xFFB0BEC5)
                                )
                                Spacer(Modifier.height(16.dp))
                                OutlinedTextField(
                                    value = key,
                                    onValueChange = { key = it },
                                    label = { Text("API key") },
                                    visualTransformation = if (showKey) androidx.compose.ui.text.input.VisualTransformation.None
                                        else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                                    trailingIcon = {
                                        TextButton(onClick = { showKey = !showKey }) { Text(if (showKey) "Hide" else "Show") }
                                    }
                                )
                                Spacer(Modifier.height(16.dp))
                                Button(onClick = {
                                    if (key.isNotBlank()) app.settingsStore.setDeepSeekKey(key.trim())
                                    step = Step.TEST
                                }) { Text(if (key.isBlank()) "Skip" else "Save and continue") }
                            }

                            Step.TEST -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("System test", style = MaterialTheme.typography.headlineSmall, color = Color.White)
                                Spacer(Modifier.height(16.dp))
                                Text("Tap below — JARVIS will speak using the real TTS engine to confirm audio output works. Wake word and STT are verified once you start using JARVIS normally.", color = Color(0xFFB0BEC5))
                                Spacer(Modifier.height(16.dp))
                                Text(testStatus, color = Color(0xFF5C6BC0))
                                Spacer(Modifier.height(16.dp))
                                Button(onClick = {
                                    testStatus = "Speaking…"
                                    val tester = TextToSpeechManager(this@SetupWizardActivity) { testStatus = "✓ TTS confirmed working." }
                                    tester.speak("Systems check. JARVIS online.")
                                }) { Text("Run test") }
                                Spacer(Modifier.height(16.dp))
                                Button(onClick = { step = Step.DASHBOARD }) { Text("Continue") }
                            }

                            Step.DASHBOARD -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("You're set", style = MaterialTheme.typography.headlineSmall, color = Color.White)
                                Spacer(Modifier.height(16.dp))
                                StatusRow("Microphone", ContextCompat.checkSelfPermission(this@SetupWizardActivity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
                                StatusRow("Wake-word model", wakeModelManager.isModelInstalled())
                                StatusRow("Overlay permission", Settings.canDrawOverlays(this@SetupWizardActivity))
                                StatusRow("Notification access", JarvisNotificationListenerService.isAccessGranted(this@SetupWizardActivity))
                                StatusRow("Local LLM", localModelManager.isModelInstalled())
                                StatusRow("DeepSeek key", app.settingsStore.getDeepSeekKey() != null)
                                Spacer(Modifier.height(24.dp))
                                Text("Review or change any of these anytime from JARVIS Settings.", color = Color(0xFFB0BEC5))
                                Spacer(Modifier.height(24.dp))
                                Button(onClick = {
                                    app.settingsStore.setSetupComplete(true)
                                    finish()
                                }) { Text("Finish") }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WizardPage(title: String, body: String, cta: String, onNext: () -> Unit) {
    Text(title, style = MaterialTheme.typography.headlineSmall, color = Color.White)
    Spacer(Modifier.height(16.dp))
    Text(body, color = Color(0xFFB0BEC5))
    Spacer(Modifier.height(32.dp))
    Button(onClick = onNext) { Text(cta) }
}

@Composable
private fun StatusRow(label: String, granted: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(if (granted) "✓" else "○", color = if (granted) Color(0xFF66BB6A) else Color(0xFF78909C))
        Spacer(Modifier.width(8.dp))
        Text(label, color = Color(0xFFB0BEC5))
    }
}
