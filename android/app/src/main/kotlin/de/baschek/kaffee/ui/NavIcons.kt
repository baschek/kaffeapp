package de.baschek.kaffee.ui

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
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/** Line icons of the navigation (same drawings as the website), painted on a 24 x 24 grid with a 2 unit stroke. */
@Composable
fun NavIcon(tab: Tab, color: Color, size: Dp = 22.dp) {
    Canvas(Modifier.size(size)) {
        scale(scaleX = this.size.width / 24f, scaleY = this.size.height / 24f, pivot = Offset.Zero) {
            val stroke = Stroke(width = 2f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            when (tab) {
                Tab.DASHBOARD -> drawPath(Path().apply { moveTo(4f, 11f); lineTo(12f, 4f); lineTo(20f, 11f); lineTo(20f, 20f); lineTo(4f, 20f); close() }, color, style = stroke)
                Tab.JARS -> {
                    drawRoundRect(color, Offset(6f, 3f), Size(12f, 3f), CornerRadius(1f), style = stroke)
                    drawPath(Path().apply {
                        moveTo(5f, 6f); lineTo(19f, 6f); lineTo(19f, 18f); cubicTo(19f, 19.66f, 17.66f, 21f, 16f, 21f); lineTo(8f, 21f)
                        cubicTo(6.34f, 21f, 5f, 19.66f, 5f, 18f); close()
                    }, color, style = stroke)
                }
                Tab.COFFEE -> {
                    drawPath(Path().apply {
                        moveTo(5f, 8f); lineTo(17f, 8f); lineTo(17f, 14f); cubicTo(17f, 16.76f, 14.76f, 19f, 12f, 19f); lineTo(10f, 19f)
                        cubicTo(7.24f, 19f, 5f, 16.76f, 5f, 14f); close()
                    }, color, style = stroke)
                    drawPath(Path().apply { moveTo(17f, 10f); lineTo(19f, 10f); cubicTo(21.2f, 10f, 21.2f, 14f, 19f, 14f); lineTo(17f, 14f) }, color, style = stroke)
                }
                Tab.HISTORY -> {
                    drawCircle(color, radius = 8f, center = Offset(12f, 12f), style = stroke)
                    drawPath(Path().apply { moveTo(12f, 8f); lineTo(12f, 12f); lineTo(15f, 14f) }, color, style = stroke)
                }
                Tab.SETTINGS -> {
                    drawCircle(color, radius = 3f, center = Offset(12f, 12f), style = stroke)
                    drawCircle(color, radius = 6.5f, center = Offset(12f, 12f), style = stroke)
                    for (i in 0 until 8) {
                        val a = i * Math.PI / 4
                        drawLine(color, Offset(12f + 6.5f * cos(a).toFloat(), 12f + 6.5f * sin(a).toFloat()),
                            Offset(12f + 9.5f * cos(a).toFloat(), 12f + 9.5f * sin(a).toFloat()), strokeWidth = 2.4f, cap = StrokeCap.Round)
                    }
                }
            }
        }
    }
}
