@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package us.lastra.actuator

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderState
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.channels.Channel
import kotlin.math.abs
import kotlin.math.roundToInt

internal val PRESETS = listOf(0, 45, 90)

@Composable
fun ActuatorScreen(
    link: ActuatorLink,
    messages: Channel<String>,
    onGrantPermission: () -> Unit,
    onEnableBluetooth: () -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(messages) {
        for (m in messages) snackbar.showSnackbar(m)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Actuator", fontWeight = FontWeight.SemiBold) },
                actions = { ConnectionPill(link.phase) },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            AnimatedContent(targetState = link.phase == Phase.Connected, label = "phase") { connected ->
                if (connected) {
                    Controls(link)
                } else {
                    ConnectionCard(link, onGrantPermission, onEnableBluetooth)
                }
            }
        }
    }
}

// ---- connected ------------------------------------------------------------

@Composable
private fun Controls(link: ActuatorLink) {
    val status = link.status
    val haptics = LocalHapticFeedback.current

    // While the finger is on the slider, it owns the angle; otherwise the
    // device's reported angle does.
    var dragging by remember { mutableStateOf<Float?>(null) }
    val deviceAngle = status?.angleTenths?.div(10f)
    val shown = dragging ?: deviceAngle ?: 45f
    val busy = status?.sweeping == true || status?.locked == true

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        HornGauge(
            angle = shown,
            known = dragging != null || deviceAngle != null,
            modifier = Modifier
                .widthIn(max = 320.dp)
                .fillMaxWidth(0.8f),
        )

        Text(
            text = if (dragging != null || deviceAngle != null) "%.1f°".format(shown) else "—",
            style = MaterialTheme.typography.displayLarge,
            fontWeight = FontWeight.Medium,
        )

        val slider = remember { SliderState(45f, 0, 0f..90f) /* value, steps, range */ }
        // Follow the device when the finger is off the slider.
        LaunchedEffect(shown, dragging) { if (dragging == null) slider.value = shown }
        Slider(
            state = slider,
            onValueChange = { v ->
                val snapped = (v * 2).roundToInt() / 2f // 0.5° steps
                if (dragging != null && snapped % 5f == 0f && snapped != dragging) {
                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                }
                slider.value = snapped
                dragging = snapped
                link.drag((snapped * 10).roundToInt())
            },
            onValueChangeFinished = { dragging = null },
            enabled = !busy,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "Horn angle" },
        )

        // Connected button group: the expressive replacement for segmented buttons.
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
        ) {
            PRESETS.forEachIndexed { i, deg ->
                ToggleButton(
                    checked = deviceAngle != null && abs(deviceAngle - deg) < 0.3f && dragging == null,
                    onCheckedChange = {
                        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                        link.moveTo(deg * 10)
                    },
                    enabled = !busy,
                    shapes = when (i) {
                        0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                        PRESETS.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                        else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                    },
                    modifier = Modifier
                        .weight(1f)
                        .semantics { role = Role.RadioButton },
                ) {
                    Text("$deg°")
                }
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BigButton(Icons.Rounded.PlayArrow, "Sweep", enabled = !busy, modifier = Modifier.weight(1f)) {
                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                link.sweep()
            }
            // Off stays available while busy: it's how you stop things.
            BigButton(Icons.Rounded.Close, "Off", tonal = true, modifier = Modifier.weight(1f)) {
                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                link.release()
            }
        }

        StatusCard(status)
    }
}

@Composable
private fun StatusCard(status: DeviceStatus?) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = MaterialTheme.shapes.extraLarge,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            StatusRow("Pulse") {
                Text(status?.let { "${it.us} µs" } ?: "—", style = MaterialTheme.typography.titleMedium)
            }
            StatusRow("Servo") {
                when {
                    status == null -> Pill("—", strong = false)
                    status.sweeping -> Pill("Sweeping", strong = true)
                    status.driving -> Pill("Driving", strong = true)
                    else -> Pill("Released", strong = false)
                }
            }
            val heat = (status?.heatPct ?: 0) / 100f
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusRow("Heat budget") {
                    Text("${status?.heatPct ?: 0}%", style = MaterialTheme.typography.labelLarge)
                }
                LinearProgressIndicator(
                    progress = { heat },
                    color = if (heat > 0.75f) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            AnimatedVisibility(visible = status?.locked == true) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Warning, contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer)
                        Spacer(Modifier.width(12.dp))
                        Text("Cooling down: moves resume in ${status?.cooldownS ?: 0} s",
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

// ---- not connected ----------------------------------------------------------

@Composable
private fun ConnectionCard(link: ActuatorLink, onGrantPermission: () -> Unit, onEnableBluetooth: () -> Unit) {
    val phase = link.phase
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = MaterialTheme.shapes.extraLarge,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 48.dp),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val working = phase in setOf(Phase.Idle, Phase.Searching, Phase.Pairing, Phase.Connecting)
            Box(Modifier.size(96.dp), contentAlignment = Alignment.Center) {
                if (working) {
                    LoadingIndicator(Modifier.size(96.dp))
                } else {
                    Icon(
                        if (phase == Phase.PairFailed) Icons.Rounded.Lock else Icons.Rounded.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(48.dp),
                    )
                }
            }
            Text(
                when (phase) {
                    Phase.Idle, Phase.Searching -> "Looking for the actuator"
                    Phase.Pairing -> "Pairing"
                    Phase.Connecting -> "Connecting"
                    Phase.NotFound -> "No actuator found"
                    Phase.PairFailed -> "Pairing failed"
                    Phase.BluetoothOff -> "Bluetooth is off"
                    Phase.NeedPermission -> "Permission needed"
                    Phase.Connected -> ""
                },
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                when (phase) {
                    Phase.Pairing -> "Enter the actuator's 6-digit PIN when Android asks for it."
                    Phase.NotFound -> "Make sure it's powered and not connected to another device."
                    Phase.PairFailed -> "Wrong PIN, or the pairing was cancelled."
                    Phase.BluetoothOff -> "Turn Bluetooth on to reach the actuator."
                    Phase.NeedPermission -> "Allow Nearby devices so the app can find the actuator."
                    else -> "Keep the actuator powered and within range."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when (phase) {
                Phase.NotFound, Phase.PairFailed -> Button(onClick = { link.connect() }, shapes = ButtonDefaults.shapes()) {
                    Icon(Icons.Rounded.Refresh, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Try again")
                }
                Phase.BluetoothOff -> Button(onClick = onEnableBluetooth, shapes = ButtonDefaults.shapes()) {
                    Text("Turn on Bluetooth")
                }
                Phase.NeedPermission -> Button(onClick = onGrantPermission, shapes = ButtonDefaults.shapes()) {
                    Text("Allow")
                }
                else -> {}
            }
        }
    }
}

@Composable
private fun ConnectionPill(phase: Phase) {
    val (label, on) = when (phase) {
        Phase.Connected -> "Connected" to true
        Phase.Searching, Phase.Idle -> "Searching" to false
        Phase.Pairing -> "Pairing" to false
        Phase.Connecting -> "Connecting" to false
        else -> "Offline" to false
    }
    val dot = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    Pill(label, strong = on, modifier = Modifier.padding(end = 12.dp)) {
        Canvas(Modifier.size(8.dp)) { drawCircle(dot) }
        Spacer(Modifier.width(8.dp))
    }
}

// ---- building blocks ----------------------------------------------------------

/** A medium-height expressive button with a leading icon. */
@Composable
private fun BigButton(
    icon: ImageVector,
    text: String,
    modifier: Modifier = Modifier,
    tonal: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val height = ButtonDefaults.MediumContainerHeight
    val content: @Composable RowScope.() -> Unit = {
        Icon(icon, contentDescription = null, Modifier.size(ButtonDefaults.MediumIconSize))
        Spacer(Modifier.width(ButtonDefaults.MediumIconSpacing))
        Text(text, style = ButtonDefaults.textStyleFor(height))
    }
    val padding = ButtonDefaults.contentPaddingFor(height)
    val sized = modifier.height(height)
    if (tonal) {
        FilledTonalButton(onClick = onClick, shapes = ButtonDefaults.shapes(), modifier = sized, enabled = enabled,
            contentPadding = padding, content = content)
    } else {
        Button(onClick = onClick, shapes = ButtonDefaults.shapes(), modifier = sized, enabled = enabled,
            contentPadding = padding, content = content)
    }
}

/** A muted label on the left, [value] on the right. */
@Composable
private fun StatusRow(label: String, value: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        value()
    }
}

/** A small rounded label; [strong] fills it with the primary container colour. */
@Composable
private fun Pill(
    label: String,
    strong: Boolean,
    modifier: Modifier = Modifier,
    leading: @Composable RowScope.() -> Unit = {},
) {
    Surface(
        shape = CircleShape,
        color = if (strong) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = modifier,
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            leading()
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}
