package com.clipsync.android.ui.conduit

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.clipsync.android.ui.theme.LocalConduitAccents

/**
 * Charter §5.5: one shape, five fills. Ready = full, degraded = 52% half fill, unavailable =
 * gray solid (a fact, not an error), unprobed = dashed outline, needs-action = hollow ochre —
 * and only a beckoning glyph may pulse.
 */
@Composable
fun StatusGlyph(
    status: SegmentStatus,
    beckoning: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 14.dp,
) {
    val flow = MaterialTheme.colorScheme.primary
    val inert = MaterialTheme.colorScheme.outline
    val beckon = LocalConduitAccents.current.beckon

    val pulse = if (beckoning) {
        rememberInfiniteTransition(label = "beckon").animateFloat(
            initialValue = 0.45f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 900, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "beckonAlpha",
        ).value
    } else {
        1f
    }

    Canvas(modifier = modifier.size(size)) {
        val stroke = Stroke(width = this.size.minDimension * 0.14f)
        val inset = stroke.width / 2f
        val diameter = this.size.minDimension - stroke.width
        val topLeft = androidx.compose.ui.geometry.Offset(inset, inset)
        val arcSize = Size(diameter, diameter)
        when (status) {
            SegmentStatus.READY -> drawCircle(color = flow)
            SegmentStatus.DEGRADED -> {
                drawCircle(color = flow, style = stroke, radius = diameter / 2f)
                // 52% fill: must be tell-apart-able at a glance (charter §5.5).
                drawArc(
                    color = flow,
                    startAngle = 90f,
                    sweepAngle = 187f,
                    useCenter = true,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Fill,
                )
            }
            SegmentStatus.UNAVAILABLE -> drawCircle(color = inert)
            SegmentStatus.UNPROBED -> drawCircle(
                color = inert,
                style = Stroke(
                    width = stroke.width,
                    pathEffect = PathEffect.dashPathEffect(
                        floatArrayOf(diameter * 0.35f, diameter * 0.28f),
                    ),
                ),
                radius = diameter / 2f,
            )
            SegmentStatus.NEEDS_ACTION -> drawCircle(
                color = beckon.copy(alpha = beckon.alpha * pulse),
                style = stroke,
                radius = diameter / 2f,
            )
        }
    }
}

@Composable
fun statusLabel(status: SegmentStatus): String = when (status) {
    SegmentStatus.READY -> "就绪"
    SegmentStatus.DEGRADED -> "降级"
    SegmentStatus.UNAVAILABLE -> "不可用"
    SegmentStatus.UNPROBED -> "未探测"
    SegmentStatus.NEEDS_ACTION -> "需要操作"
}

@Composable
fun statusColor(status: SegmentStatus): Color = when (status) {
    SegmentStatus.READY, SegmentStatus.DEGRADED -> MaterialTheme.colorScheme.primary
    SegmentStatus.UNAVAILABLE, SegmentStatus.UNPROBED -> MaterialTheme.colorScheme.onSurfaceVariant
    SegmentStatus.NEEDS_ACTION -> LocalConduitAccents.current.beckon
}
