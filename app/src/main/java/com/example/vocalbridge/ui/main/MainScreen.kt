package com.example.vocalbridge.ui.main

import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.vocalbridge.tts.TtsEngineManager

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.style.TextAlign
import com.example.vocalbridge.ui.main.MainScreenViewModel
import com.example.vocalbridge.ui.main.EngineStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    modifier: Modifier = Modifier,
    viewModel: MainScreenViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // ── Header ──
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Sherpa TTS",
            style = MaterialTheme.typography.headlineLarge.copy(
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.5).sp,
            ),
            color = MaterialTheme.colorScheme.onSurface,
        )
        // ── Voice Selection ──
        VoiceSelection(
            selectedVoiceId = state.speakerId,
            onVoiceSelected = { viewModel.updateSpeaker(it) },
            isSpeaking = state.isSpeaking
        )

        Spacer(modifier = Modifier.height(16.dp))

        // ── Status Card ──
        StatusCard(
            status = state.engineStatus,
            message = state.statusMessage,
            isSpeaking = state.isSpeaking,
            onRetry = { viewModel.initializeEngine() },
        )

        Spacer(modifier = Modifier.height(16.dp))

        // ── Text Input ──
        OutlinedTextField(
            value = state.inputText,
            onValueChange = { viewModel.updateInputText(it) },
            label = { Text("Text to speak") },
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp),
            shape = RoundedCornerShape(16.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
            ),
            trailingIcon = {
                Column {
                    if (state.inputText.isNotEmpty()) {
                        TextButton(onClick = { viewModel.updateInputText("") }) {
                            Text("Clear")
                        }
                    }
                    TextButton(onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        if (clipboard.hasPrimaryClip() && clipboard.primaryClip?.itemCount ?: 0 > 0) {
                            val textToPaste = clipboard.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
                            viewModel.updateInputText(textToPaste)
                        }
                    }) {
                        Text("Paste")
                    }
                }
            }
        )

        Spacer(modifier = Modifier.height(16.dp))

        // ── Speed Control ──
        SpeedControl(
            speed = state.speed,
            onSpeedChange = { viewModel.updateSpeed(it) },
        )

        Spacer(modifier = Modifier.height(16.dp))

        // ── Continuous Playback Mode ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Continuous Playback Mode",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "Screen-off reading. Perfectly gapless audio, but reading app UI will lose sync.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = state.continuousPlaybackMode,
                onCheckedChange = { viewModel.updateContinuousPlaybackMode(it) }
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // ── Play / Stop Button ──
        PlayStopButton(
            isSpeaking = state.isSpeaking,
            isReady = state.engineStatus == EngineStatus.READY,
            onPlay = { viewModel.speak() },
            onStop = { viewModel.stopPlayback() },
        )

        Spacer(modifier = Modifier.height(24.dp))

        // ── System TTS Settings ──
        FilledTonalButton(
            onClick = {
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                    // This is the closest standard intent; on most devices the user
                    // can also navigate via "Text-to-speech output" in settings.
                    // We use a more specific action if available:
                }
                try {
                    context.startActivity(Intent("com.android.settings.TTS_SETTINGS"))
                } catch (_: Exception) {
                    context.startActivity(intent)
                }
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
        ) {
            Text("Open System TTS Settings")
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Select \"TTSAndroid\" as preferred engine in system settings to use it across all apps.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp),
        )

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun StatusCard(
    status: EngineStatus,
    message: String,
    isSpeaking: Boolean,
    onRetry: () -> Unit,
) {
    val statusColor = when (status) {
        EngineStatus.READY -> Color(0xFF4CAF50)
        EngineStatus.INITIALIZING -> Color(0xFFFFC107)
        EngineStatus.ERROR -> Color(0xFFF44336)
        EngineStatus.NOT_INITIALIZED -> MaterialTheme.colorScheme.outlineVariant
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Animated status dot
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(statusColor),
            )

            // Pulsing animation when speaking
            if (isSpeaking) {
                val infiniteTransition = rememberInfiniteTransition(label = "pulse")
                val scale by infiniteTransition.animateFloat(
                    initialValue = 1f,
                    targetValue = 1.4f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(600),
                        repeatMode = RepeatMode.Reverse,
                    ),
                    label = "pulseScale",
                )
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .scale(scale)
                        .alpha(0.3f)
                        .clip(CircleShape)
                        .background(Color(0xFF4CAF50)),
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = when {
                        isSpeaking -> "Speaking…"
                        status == EngineStatus.READY -> "Engine Ready"
                        status == EngineStatus.INITIALIZING -> "Initializing…"
                        status == EngineStatus.ERROR -> "Error"
                        else -> "Not Initialized"
                    },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (status == EngineStatus.ERROR) {
                FilledTonalButton(onClick = onRetry) {
                    Text("Retry")
                }
            }
        }
    }
}

@Composable
private fun SpeedControl(
    speed: Float,
    onSpeedChange: (Float) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Speed",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "%.1fx".format(speed),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
            }

            Slider(
                value = speed,
                onValueChange = onSpeedChange,
                valueRange = TtsEngineManager.MIN_SPEED..TtsEngineManager.MAX_SPEED,
                steps = 24,
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                ),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "0.5x",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "3.0x",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PlayStopButton(
    isSpeaking: Boolean,
    isReady: Boolean,
    onPlay: () -> Unit,
    onStop: () -> Unit,
) {
    Button(
        onClick = { if (isSpeaking) onStop() else onPlay() },
        enabled = isReady,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (isSpeaking) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.primary
            },
        ),
    ) {
        Text(
            text = if (isSpeaking) "⏹  Stop" else "▶  Speak",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VoiceSelection(
    selectedVoiceId: Int,
    onVoiceSelected: (Int) -> Unit,
    isSpeaking: Boolean
) {
    var expanded by remember { mutableStateOf(false) }
    val voices = TtsEngineManager.availableVoices
    val selectedVoice = voices.find { it.id == selectedVoiceId } ?: voices.first()

    Card(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Voice Profile",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { if (!isSpeaking) expanded = it },
            ) {
                OutlinedTextField(
                    value = "${selectedVoice.name} (${selectedVoice.description})",
                    onValueChange = {},
                    readOnly = true,
                    enabled = !isSpeaking,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                    )
                )

                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                ) {
                    voices.forEach { voice ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(text = voice.name, fontWeight = FontWeight.Bold)
                                    Text(
                                        text = voice.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            },
                            onClick = {
                                onVoiceSelected(voice.id)
                                expanded = false
                            }
                        )
                    }
                }
            }
        }
    }
}
