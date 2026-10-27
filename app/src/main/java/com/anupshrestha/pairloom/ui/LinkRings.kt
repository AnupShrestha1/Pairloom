package com.anupshrestha.pairloom.ui

import android.provider.Settings
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.anupshrestha.pairloom.ui.theme.LocalThreads
import kotlin.math.atan2
import kotlin.math.sqrt

/** What the two rings show. The status text says the same thing in words; the rings are decoration for screen readers. */
enum class RingsMode {
    /** Not connected: the other phone's ring is dashed (not there). */
    APART,

    /** Connecting or reconnecting: the rings draw towards each other and back. */
    SEEKING,

    /** Linked: the rings interlock, like two threads woven together. */
    JOINED,
}

/**
 * The app's mark as its live status (docs/plans/phase6-ux.md): this phone's teal ring on the left,
 * the other phone's saffron ring on the right. [peerLevel] (0..1) and [selfTalking] add a halo
 * behind the ring of whoever is speaking. Motion follows the system's animation setting.
 */
@Composable
fun LinkRings(
    mode: RingsMode,
    modifier: Modifier = Modifier,
    peerLevel: Float = 0f,
    selfTalking: Boolean = false,
    /** The other phone is calling: its ring pulses like a ringing phone. */
    ringing: Boolean = false,
) {
    val threads = LocalThreads.current
    val context = LocalContext.current
    val motion = remember {
        runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) }.getOrDefault(1f) > 0f
    }
    val seeking = rememberInfiniteTransition(label = "seek")
    val sway by seeking.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "sway",
    )
    val dash by seeking.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(4000, easing = LinearEasing)),
        label = "dash",
    )
    // Distance between the centres, in ring radii: 2.7 apart, 1.15 interlocked.
    val target = when (mode) {
        RingsMode.APART -> 2.7f
        RingsMode.SEEKING -> if (motion) 2.5f - 0.9f * sway else 2.0f
        RingsMode.JOINED -> 1.15f
    }
    val spread by animateFloatAsState(
        targetValue = target,
        animationSpec = if (mode == RingsMode.SEEKING) tween(0) else spring(dampingRatio = 0.6f, stiffness = 120f),
        label = "spread",
    )
    val ring by seeking.animateFloat(
        initialValue = 0.15f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(tween(700, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "ring",
    )
    val peerTarget = if (ringing) (if (motion) ring else 0.6f) else peerLevel.coerceIn(0f, 1f)
    val peerPulse by animateFloatAsState(peerTarget, tween(if (ringing) 0 else 120), label = "peer")
    val selfPulse by animateFloatAsState(if (selfTalking) 1f else 0f, tween(160), label = "self")

    Canvas(modifier.size(width = 220.dp, height = 132.dp).clearAndSetSemantics { }) {
        val r = size.height * 0.34f
        val stroke = r * 0.22f
        val cy = size.height / 2
        val half = spread * r / 2
        val left = Offset(size.width / 2 - half, cy)
        val right = Offset(size.width / 2 + half, cy)

        halo(left, r, stroke, threads.self, selfPulse)
        halo(right, r, stroke, threads.peer, peerPulse)

        val peerStyle = if (mode == RingsMode.APART || mode == RingsMode.SEEKING) {
            // A loose thread: short square dashes, so it reads as "not there yet", not as beads.
            val on = stroke * 0.55f
            val off = stroke * 0.65f
            Stroke(stroke * 0.8f, cap = StrokeCap.Butt, pathEffect = PathEffect.dashPathEffect(floatArrayOf(on, off), if (motion) dash * (on + off) * 4 else 0f))
        } else {
            Stroke(stroke, cap = StrokeCap.Round)
        }
        // Neon: a soft glow under each tube, stronger when linked and while that phone talks.
        val base = when (mode) {
            RingsMode.APART -> 0.35f
            RingsMode.SEEKING -> 0.5f
            RingsMode.JOINED -> 0.75f
        }
        neonGlow(left, r, stroke, threads.self, (base + 0.5f * selfPulse).coerceAtMost(1.2f), null)
        neonGlow(right, r, stroke, threads.peer, (base + 0.5f * peerPulse).coerceAtMost(1.2f), if (mode == RingsMode.JOINED) null else dashFor(stroke, motion, dash))
        drawCircle(threads.self, r, left, style = Stroke(stroke))
        drawCircle(threads.peer, r, right, style = peerStyle)
        // The bright core of a lit tube.
        drawCircle(lighter(threads.self), r, left, style = Stroke(stroke * 0.28f))
        if (mode == RingsMode.JOINED) drawCircle(lighter(threads.peer), r, right, style = Stroke(stroke * 0.28f))

        // Woven: where the rings cross at the top, this phone's thread passes over the other's.
        if (spread < 2f) {
            val dx = half
            val h = sqrt((r * r - dx * dx).coerceAtLeast(0f))
            val angle = Math.toDegrees(atan2(-h, dx).toDouble()).toFloat()
            for ((color, width) in listOf(threads.self to stroke, lighter(threads.self) to stroke * 0.28f)) {
                drawArc(
                    color = color,
                    startAngle = angle - 14f,
                    sweepAngle = 28f,
                    useCenter = false,
                    topLeft = Offset(left.x - r, left.y - r),
                    size = Size(2 * r, 2 * r),
                    style = Stroke(width),
                )
            }
        }
    }
}

/** The tube's colour blended halfway to white: the bright line along a lit neon tube. */
private fun lighter(c: Color) = Color(
    red = c.red + (1f - c.red) * 0.55f,
    green = c.green + (1f - c.green) * 0.55f,
    blue = c.blue + (1f - c.blue) * 0.55f,
    alpha = 0.9f,
)

private fun dashFor(stroke: Float, motion: Boolean, dash: Float): android.graphics.PathEffect {
    val on = stroke * 0.55f
    val off = stroke * 0.65f
    return android.graphics.DashPathEffect(floatArrayOf(on, off), if (motion) dash * (on + off) * 4 else 0f)
}

/**
 * A blurred glow under a ring (Android 9+, where hardware drawing supports blur; older phones draw
 * the rings without it). [amount] around 0.35 is a faint edge, 1 a clear neon glow.
 */
private fun DrawScope.neonGlow(center: Offset, r: Float, stroke: Float, color: Color, amount: Float, dash: android.graphics.PathEffect?) {
    if (android.os.Build.VERSION.SDK_INT < 28 || amount <= 0f) return
    drawIntoCanvas { canvas ->
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = stroke * 1.6f
            this.color = color.copy(alpha = (0.55f * amount).coerceIn(0f, 1f)).toArgb()
            maskFilter = android.graphics.BlurMaskFilter(stroke * 1.4f, android.graphics.BlurMaskFilter.Blur.NORMAL)
            pathEffect = dash
        }
        canvas.nativeCanvas.drawCircle(center.x, center.y, r, paint)
    }
}

/** A glow just outside the ring, wider and brighter with [amount]; the inside stays clear. */
private fun DrawScope.halo(center: Offset, r: Float, stroke: Float, color: Color, amount: Float) {
    if (amount <= 0.02f) return
    val reach = stroke * (1.2f + 3.4f * amount)
    val bands = 10
    for (i in 1..bands) {
        val t = i.toFloat() / bands
        drawCircle(
            color.copy(alpha = 0.5f * amount * (1f - t + 0.2f)),
            radius = r + stroke / 2 + reach * t - reach / bands / 2,
            center = center,
            style = Stroke(reach / bands + 1f),
        )
    }
}
