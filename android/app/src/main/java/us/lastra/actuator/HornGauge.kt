package us.lastra.actuator

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlin.math.cos
import kotlin.math.sin

/**
 * The horn, drawn: a quarter arc from 0° (pointing right) to 90° (pointing up),
 * pivot at the bottom left, the arm at the current angle. Moves with the
 * theme's expressive spring.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun HornGauge(angle: Float, known: Boolean, modifier: Modifier = Modifier) {
    val animated by animateFloatAsState(
        targetValue = angle,
        animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
        label = "horn",
    )
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val active = MaterialTheme.colorScheme.primary
    val arm = if (known) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    val hub = MaterialTheme.colorScheme.primaryContainer
    val tick = MaterialTheme.colorScheme.outlineVariant

    Canvas(
        modifier
            .aspectRatio(1f)
            .semantics { contentDescription = "Horn at %.1f degrees".format(angle) },
    ) {
        val stroke = size.minDimension * 0.06f
        val pad = stroke * 1.6f
        val pivot = Offset(pad, size.height - pad)
        val r = size.width - 2 * pad
        val arcTopLeft = Offset(pivot.x - r, pivot.y - r)
        val arcSize = Size(2 * r, 2 * r)

        // Track and the filled part (Compose arcs: 0° = east, clockwise).
        drawArc(track, startAngle = -90f, sweepAngle = 90f, useCenter = false,
            topLeft = arcTopLeft, size = arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        if (known) {
            drawArc(active.copy(alpha = 0.35f), startAngle = -animated, sweepAngle = animated, useCenter = false,
                topLeft = arcTopLeft, size = arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        }

        // Ticks at 0, 45, 90.
        for (deg in PRESETS) {
            val a = Math.toRadians(deg.toDouble())
            val inner = r - stroke * 1.4f
            val outer = r - stroke * 0.9f
            drawLine(tick,
                Offset(pivot.x + inner * cos(a).toFloat(), pivot.y - inner * sin(a).toFloat()),
                Offset(pivot.x + outer * cos(a).toFloat(), pivot.y - outer * sin(a).toFloat()),
                strokeWidth = stroke * 0.25f, cap = StrokeCap.Round)
        }

        // The arm.
        val a = Math.toRadians(animated.toDouble())
        val len = r * 0.78f
        drawLine(arm, pivot,
            Offset(pivot.x + len * cos(a).toFloat(), pivot.y - len * sin(a).toFloat()),
            strokeWidth = stroke * 1.4f, cap = StrokeCap.Round)
        drawCircle(arm, radius = stroke * 1.5f, center = pivot)
        drawCircle(hub, radius = stroke * 0.6f, center = pivot)
    }
}
