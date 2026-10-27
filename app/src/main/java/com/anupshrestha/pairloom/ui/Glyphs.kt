package com.anupshrestha.pairloom.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The few icons Pairloom needs, drawn as strokes so the app needs no icon library. */
enum class Glyph { MIC, MIC_OFF, SPEAKER, EARPIECE, HANG_UP, CALL, INFO, CLOSE, BACK, SETTINGS }

/** Decorative: every icon sits next to (or inside) a control whose label says what it does. */
@Composable
fun GlyphIcon(glyph: Glyph, tint: Color, modifier: Modifier = Modifier, size: Dp = 24.dp) {
    Canvas(modifier.size(size).clearAndSetSemantics { }) {
        val u = this.size.minDimension / 24f // drawn on a 24-unit grid
        val line = Stroke(width = 2f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun p(x: Float, y: Float) = Offset(x * u, y * u)
        when (glyph) {
            Glyph.MIC, Glyph.MIC_OFF -> {
                drawRoundRect(tint, p(9f, 3f), Size(6f * u, 11f * u), CornerRadius(3f * u), style = line)
                drawArc(tint, 0f, 180f, false, p(5.5f, 7.5f), Size(13f * u, 9.5f * u), style = line)
                drawLine(tint, p(12f, 17f), p(12f, 21f), line.width, StrokeCap.Round)
                if (glyph == Glyph.MIC_OFF) drawLine(tint, p(4f, 3.5f), p(20f, 20.5f), line.width, StrokeCap.Round)
            }
            Glyph.SPEAKER, Glyph.EARPIECE -> {
                val cone = Path().apply {
                    moveTo(3.5f * u, 9.5f * u); lineTo(7.5f * u, 9.5f * u); lineTo(12f * u, 5f * u)
                    lineTo(12f * u, 19f * u); lineTo(7.5f * u, 14.5f * u); lineTo(3.5f * u, 14.5f * u); close()
                }
                drawPath(cone, tint, style = line)
                drawArc(tint, -45f, 90f, false, p(10f, 8f), Size(8f * u, 8f * u), style = line)
                if (glyph == Glyph.SPEAKER) drawArc(tint, -50f, 100f, false, p(9f, 4f), Size(14f * u, 16f * u), style = line)
            }
            Glyph.CALL, Glyph.HANG_UP -> rotate(if (glyph == Glyph.HANG_UP) 135f else 0f, pivot = p(12f, 12f)) {
                // A handset: two ear pieces joined by a curved handle.
                val handset = Path().apply {
                    moveTo(6f * u, 3.5f * u)
                    cubicTo(4f * u, 4f * u, 3f * u, 6f * u, 3.5f * u, 8.5f * u)
                    cubicTo(5f * u, 14.5f * u, 9.5f * u, 19f * u, 15.5f * u, 20.5f * u)
                    cubicTo(18f * u, 21f * u, 20f * u, 20f * u, 20.5f * u, 18f * u)
                    lineTo(17f * u, 14.5f * u)
                    lineTo(14.5f * u, 16f * u)
                    cubicTo(11.5f * u, 14.5f * u, 9.5f * u, 12.5f * u, 8f * u, 9.5f * u)
                    lineTo(9.5f * u, 7f * u)
                    close()
                }
                drawPath(handset, tint, style = line)
            }
            Glyph.INFO -> {
                drawCircle(tint, 9f * u, p(12f, 12f), style = line)
                drawLine(tint, p(12f, 11f), p(12f, 16.5f), line.width, StrokeCap.Round)
                drawCircle(tint, 1.2f * u, p(12f, 7.8f))
            }
            Glyph.SETTINGS -> {
                // A gear: a ring with eight teeth and a hole.
                drawCircle(tint, 6f * u, p(12f, 12f), style = line)
                drawCircle(tint, 2.4f * u, p(12f, 12f), style = line)
                for (i in 0 until 8) {
                    rotate(i * 45f, pivot = p(12f, 12f)) {
                        drawLine(tint, p(12f, 2.8f), p(12f, 5.6f), 2.6f * u, StrokeCap.Round)
                    }
                }
            }
            Glyph.CLOSE -> {
                drawLine(tint, p(6f, 6f), p(18f, 18f), line.width, StrokeCap.Round)
                drawLine(tint, p(18f, 6f), p(6f, 18f), line.width, StrokeCap.Round)
            }
            Glyph.BACK -> {
                drawLine(tint, p(19f, 12f), p(5f, 12f), line.width, StrokeCap.Round)
                drawLine(tint, p(11f, 6f), p(5f, 12f), line.width, StrokeCap.Round)
                drawLine(tint, p(11f, 18f), p(5f, 12f), line.width, StrokeCap.Round)
            }
        }
    }
}
