package us.lastra.actuator.wear

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.CompactButton
import androidx.wear.compose.material3.FilledTonalIconButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TextButton
import androidx.wear.compose.material3.TextButtonDefaults
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import us.lastra.actuator.ActuatorLink
import us.lastra.actuator.DeviceStatus
import us.lastra.actuator.Phase
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private val PRESETS = listOf(0, 45, 90)

// The gauge runs around the bezel, leaving the bottom quarter open for buttons
// (Compose arcs: 0° = east, clockwise).
private const val ARC_START = 135f
private const val ARC_SWEEP = 270f

private const val STEP = 0.5f              // degrees per crown step, as the phone's slider
private const val CROWN_HOLD_MS = 700L     // how long the crown owns the readout after it stops
private const val NOTICE_MS = 3000L

@Composable
fun WatchScreen(
    link: ActuatorLink,
    messages: Channel<String>,
    onGrantPermission: () -> Unit,
    onEnableBluetooth: () -> Unit,
) {
    var notice by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(messages) {
        for (m in messages) {
            notice = m
            delay(NOTICE_MS)
            notice = null
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        if (link.phase == Phase.Connected) {
            Dial(
                status = link.status,
                onTurn = link::drag,
                onPreset = link::moveTo,
                onSweep = link::sweep,
                onOff = link::release,
            )
        } else {
            Connection(link.phase, onRetry = link::connect, onGrantPermission, onEnableBluetooth)
        }
        notice?.let { Notice(it) { notice = null } }
    }
}

// ---- connected ------------------------------------------------------------

/**
 * The whole control surface on one screen, so nothing scrolls and the crown is
 * free to set the angle. Sized from the screen, so it fills a 41 mm and a 45 mm
 * watch alike.
 */
@Composable
internal fun Dial(
    status: DeviceStatus?,
    onTurn: (tenths: Int) -> Unit,
    onPreset: (tenths: Int) -> Unit,
    onSweep: () -> Unit,
    onOff: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val deviceAngle = status?.angleTenths?.div(10f)
    val busy = status?.sweeping == true || status?.locked == true

    // While the crown is turning it owns the angle; otherwise the device's
    // reported angle does.
    var turning by remember { mutableStateOf<Float?>(null) }
    var turns by remember { mutableIntStateOf(0) }
    var carry by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(turns) {
        delay(CROWN_HOLD_MS)
        turning = null
        carry = 0f
    }

    val shown = turning ?: deviceAngle
    // One screen height of crown travel is half the range.
    val degPerPx = 45f / LocalWindowInfo.current.containerSize.height.coerceAtLeast(1)

    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .onRotaryScrollEvent { e ->
                if (!busy) {
                    val from = turning ?: deviceAngle?.let { (it / STEP).roundToInt() * STEP } ?: 45f
                    carry += e.verticalScrollPixels * degPerPx
                    val steps = (carry / STEP).toInt()
                    if (steps != 0) {
                        carry -= steps * STEP
                        val to = (from + steps * STEP).coerceIn(0f, 90f)
                        if (to != from) {
                            if (to % 5f == 0f) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            // Coalesced in the link: only the newest position is sent.
                            onTurn((to * 10).roundToInt())
                        }
                        turning = to
                        turns++
                    }
                }
                true
            }
            .focusRequester(focus)
            .focusable(),
        contentAlignment = Alignment.Center,
    ) {
        // Everything is laid out for a 192 dp screen and scaled from there.
        val s = min(maxWidth, maxHeight) / 192.dp

        Gauge(shown, live = turning != null, locked = status?.locked == true, scale = s)

        Column(
            Modifier.offset(y = 12.dp * s),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = shown?.let { "%.1f°".format(it) } ?: "—",
                style = MaterialTheme.typography.numeralSmall.copy(
                    fontSize = fixedSp(32.dp * s),
                    lineHeight = fixedSp(35.dp * s),
                ),
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = stateLabel(status),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = fixedSp(11.dp * s),
                    lineHeight = fixedSp(15.dp * s),
                ),
                color = if (status?.locked == true) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            Spacer(Modifier.height(5.dp * s))
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp * s)) {
                PRESETS.forEach { deg ->
                    val here = deviceAngle != null && abs(deviceAngle - deg) < 0.3f && turning == null
                    TextButton(
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                            onPreset(deg * 10)
                        },
                        enabled = !busy,
                        colors = if (here) TextButtonDefaults.filledTextButtonColors()
                                 else TextButtonDefaults.filledTonalTextButtonColors(),
                        modifier = Modifier.size(42.dp * s),
                    ) {
                        Text("$deg°", fontSize = fixedSp(14.dp * s), fontWeight = FontWeight.Medium)
                    }
                }
            }
            Spacer(Modifier.height(4.dp * s))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp * s)) {
                FilledTonalIconButton(
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                        onSweep()
                    },
                    enabled = !busy,
                    modifier = Modifier.size(38.dp * s),
                ) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = "Sweep", Modifier.size(20.dp * s))
                }
                // Off stays available while busy: it's how you stop things.
                FilledTonalIconButton(
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                        onOff()
                    },
                    modifier = Modifier.size(38.dp * s),
                ) {
                    Icon(Icons.Rounded.Close, contentDescription = "Off", Modifier.size(20.dp * s))
                }
            }
        }
    }
}

/** The bezel arc: track, filled part up to [angle], a tick at 45°, and a thumb. */
@Composable
private fun Gauge(angle: Float?, live: Boolean, locked: Boolean, scale: Float) {
    // The crown moves it directly; presets and sweeps glide.
    val animated by animateFloatAsState(
        targetValue = angle ?: 0f,
        animationSpec = if (live) snap() else spring(dampingRatio = 0.8f, stiffness = 200f),
        label = "gauge",
    )
    val known = angle != null
    val track = MaterialTheme.colorScheme.surfaceContainerHigh
    val active = if (locked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val tick = MaterialTheme.colorScheme.outline
    val gap = MaterialTheme.colorScheme.background

    Canvas(
        Modifier
            .fillMaxSize()
            .semantics { contentDescription = angle?.let { "Horn at %.1f degrees".format(it) } ?: "Horn angle unknown" },
    ) {
        val stroke = 7.dp.toPx() * scale
        val r = size.minDimension / 2 - 4.dp.toPx() * scale - stroke / 2
        val topLeft = Offset(center.x - r, center.y - r)
        val arcSize = Size(2 * r, 2 * r)
        fun at(deg: Float, radius: Float): Offset {
            val a = Math.toRadians((ARC_START + ARC_SWEEP * deg / 90f).toDouble())
            return Offset(center.x + radius * cos(a).toFloat(), center.y + radius * sin(a).toFloat())
        }

        drawArc(track, ARC_START, ARC_SWEEP, useCenter = false,
            topLeft = topLeft, size = arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        if (known) {
            drawArc(active, ARC_START, ARC_SWEEP * animated / 90f, useCenter = false,
                topLeft = topLeft, size = arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        // The ends mark 0° and 90° themselves; only the middle needs a tick.
        drawCircle(tick, radius = stroke * 0.2f, center = at(45f, r - stroke * 1.25f))
        if (known) {
            drawCircle(gap, radius = stroke * 1.15f, center = at(animated, r))
            drawCircle(active, radius = stroke * 0.8f, center = at(animated, r))
        }
    }
}

private fun stateLabel(status: DeviceStatus?): String = when {
    status == null -> "—"
    status.locked -> "Cooling ${status.cooldownS} s"
    else -> {
        val state = when {
            status.sweeping -> "Sweeping"
            status.driving -> "Driving"
            else -> "Released"
        }
        // The heat budget only earns its space once it's worth watching.
        if (status.heatPct >= 50) "$state · heat ${status.heatPct}%" else state
    }
}

// ---- not connected ----------------------------------------------------------

@Composable
private fun Connection(
    phase: Phase,
    onRetry: () -> Unit,
    onGrantPermission: () -> Unit,
    onEnableBluetooth: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 26.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
    ) {
        val working = phase in setOf(Phase.Idle, Phase.Searching, Phase.Pairing, Phase.Connecting)
        Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
            if (working) {
                CircularProgressIndicator(Modifier.size(28.dp))
            } else {
                Icon(
                    if (phase == Phase.PairFailed) Icons.Rounded.Lock else Icons.Rounded.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(26.dp),
                )
            }
        }
        Text(
            when (phase) {
                Phase.Idle, Phase.Searching -> "Searching"
                Phase.Pairing -> "Pairing"
                Phase.Connecting -> "Connecting"
                Phase.NotFound -> "Not found"
                Phase.PairFailed -> "Pairing failed"
                Phase.BluetoothOff -> "Bluetooth is off"
                Phase.NeedPermission -> "Permission needed"
                Phase.Connected -> ""
            },
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            when (phase) {
                Phase.Pairing -> "Enter the actuator's 6-digit PIN."
                Phase.NotFound -> "Is it powered, and is the phone app closed?"
                Phase.PairFailed -> "Wrong PIN, or pairing was canceled."
                Phase.BluetoothOff -> "Turn it on to reach the actuator."
                Phase.NeedPermission -> "Allow Nearby devices to find the actuator."
                else -> "Keep the actuator powered and in range."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        when (phase) {
            Phase.NotFound, Phase.PairFailed -> CompactButton(onClick = onRetry, label = { Text("Try again") })
            Phase.BluetoothOff -> CompactButton(onClick = onEnableBluetooth, label = { Text("Turn on") })
            Phase.NeedPermission -> CompactButton(onClick = onGrantPermission, label = { Text("Allow") })
            else -> {}
        }
    }
}

/** A refusal from the actuator (cooling, busy), over everything for a moment. Tap to dismiss. */
@Composable
internal fun Notice(text: String, onDismiss: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.94f))
            .clickable(onClick = onDismiss)
            .padding(horizontal = 28.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(Icons.Rounded.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(24.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        }
    }
}

/** A text size that tracks the layout, not the system font scale, so the dial always fits. */
@Composable
private fun fixedSp(size: Dp) = with(LocalDensity.current) { size.toSp() }
