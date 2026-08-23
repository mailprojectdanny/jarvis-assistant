package com.jarvis.assistant

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.jarvis.assistant.session.JarvisUiState
import com.jarvis.assistant.session.SessionState
import com.jarvis.assistant.session.Speaker
import com.jarvis.assistant.service.JarvisServiceController
import com.jarvis.assistant.settings.SettingsActivity
import com.jarvis.assistant.setup.SetupWizardActivity

/**
 * Required main-screen elements from the spec: JARVIS branding, wake/listening
 * status, conversation transcript, current action, and a stop button — all driven
 * live from JarvisUiState, which the foreground service updates in real time.
 */
class MainActivity : ComponentActivity() {

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startJarvisService()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as JarvisApplication

        if (!app.settingsStore.setupComplete()) {
            startActivity(Intent(this, SetupWizardActivity::class.java))
        }

        setContent {
            val running by JarvisUiState.serviceRunning.collectAsState()
            val sessionState by JarvisUiState.sessionState.collectAsState()
            val sessionActive by JarvisUiState.sessionActive.collectAsState()
            val currentAction by JarvisUiState.currentAction.collectAsState()
            val conversation by JarvisUiState.conversation.collectAsState()
            val listState = rememberLazyListState()

            LaunchedEffect(conversation.size) {
                if (conversation.isNotEmpty()) listState.animateScrollToItem(conversation.size - 1)
            }

            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF5C6BC0))) {
                Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF0A0F1E)) {
                    Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
                        Row(verticalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.weight(1f)) {
                                Text("JARVIS", style = MaterialTheme.typography.headlineLarge, color = Color.White)
                                Text(
                                    statusLabel(sessionActive, sessionState),
                                    color = statusColor(sessionActive, sessionState)
                                )
                            }
                            IconButton(onClick = { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }) {
                                Icon(Icons.Default.Settings, contentDescription = "Settings", tint = Color(0xFFB0BEC5))
                            }
                        }

                        Spacer(Modifier.height(8.dp))
                        Text("Current action: $currentAction", color = Color(0xFF78909C), style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(16.dp))

                        LazyColumn(
                            state = listState,
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(conversation) { turn ->
                                val isUser = turn.speaker == Speaker.USER
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
                                ) {
                                    Surface(
                                        color = if (isUser) Color(0xFF1E2A47) else Color(0xFF16203A),
                                        shape = MaterialTheme.shapes.medium
                                    ) {
                                        Text(
                                            turn.text,
                                            modifier = Modifier.padding(12.dp),
                                            color = if (isUser) Color(0xFFE0E0E0) else Color(0xFFB0BEC5)
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(Modifier.height(16.dp))

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                            if (!running) {
                                Button(onClick = { requestMicAndStart() }) { Text("Start JARVIS") }
                            } else {
                                Button(
                                    onClick = { JarvisServiceController.stop(this@MainActivity) },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF5350))
                                ) { Text("Stop") }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun statusLabel(active: Boolean, state: SessionState): String = when {
        !active -> "Standing by — say \"Hey JARVIS\""
        state == SessionState.LISTENING -> "Listening…"
        state == SessionState.THINKING -> "Thinking…"
        state == SessionState.EXECUTING -> "Executing…"
        state == SessionState.SPEAKING -> "Speaking…"
        else -> "Active"
    }

    private fun statusColor(active: Boolean, state: SessionState): Color = when {
        !active -> Color(0xFF78909C)
        state == SessionState.LISTENING -> Color(0xFF66BB6A)
        state == SessionState.THINKING -> Color(0xFFFFB74D)
        state == SessionState.EXECUTING -> Color(0xFF42A5F5)
        state == SessionState.SPEAKING -> Color(0xFF5C6BC0)
        else -> Color(0xFFB0BEC5)
    }

    private fun requestMicAndStart() {
        micPermission.launch(android.Manifest.permission.RECORD_AUDIO)
    }

    private fun startJarvisService() {
        JarvisServiceController.start(this)
    }
}
