package com.example.bambuautoprint.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** 统一 24dp 画布、20dp 外轮廓和线宽，避免字体符号的大小随字形变化。 */
@Composable fun BottomNavIcon(index: Int) {
    val color = LocalContentColor.current
    Canvas(Modifier.size(24.dp)) {
        scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) {
            val stroke = Stroke(1.8f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            when (index) {
                0 -> drawPath(Path().apply {
                    moveTo(2f, 11f); lineTo(12f, 2f); lineTo(22f, 11f)
                    moveTo(5f, 9f); lineTo(5f, 22f); lineTo(10f, 22f)
                    lineTo(10f, 15f); lineTo(14f, 15f); lineTo(14f, 22f)
                    lineTo(19f, 22f); lineTo(19f, 9f)
                }, color, style = stroke)
                1 -> {
                    drawCircle(color, 10f, Offset(12f, 12f), style = stroke)
                    drawPath(Path().apply { moveTo(12f, 6f); lineTo(12f, 12f); lineTo(16f, 15f) }, color, style = stroke)
                }
                2 -> {
                    // 四格资源图标与其余图标共享同样的视觉边界。
                    listOf(Offset(2f, 2f), Offset(14f, 2f), Offset(2f, 14f), Offset(14f, 14f)).forEach {
                        drawRoundRect(color, it, Size(8f, 8f), androidx.compose.ui.geometry.CornerRadius(1.5f), style = stroke)
                    }
                }
                3 -> {
                    drawCircle(color, 10f, Offset(12f, 12f), style = stroke)
                    drawCircle(color, 3f, Offset(12f, 9f), style = stroke)
                    drawPath(Path().apply { moveTo(6f, 19f); cubicTo(6f, 12f, 18f, 12f, 18f, 19f) }, color, style = stroke)
                }
            }
        }
    }
}

/** 状态标记固定为正方形，边框和勾/叉均居中，不依赖文本宽度。 */
@Composable fun SquareStatusIcon(ready: Boolean) {
    val color = if (ready) Mint else MaterialTheme.colorScheme.error
    Canvas(Modifier.size(36.dp)
        .background(if (ready) SoftMint else Peach, RectangleShape)
        .border(1.5.dp, color, RectangleShape)
        .semantics { contentDescription = if (ready) "已就绪" else "未就绪，需要设置" }) {
        val strokeWidth = 2.dp.toPx()
        fun point(x: Float, y: Float) = Offset(size.width * x, size.height * y)
        if (ready) {
            drawLine(color, point(.27f, .50f), point(.44f, .67f), strokeWidth, StrokeCap.Round)
            drawLine(color, point(.44f, .67f), point(.74f, .33f), strokeWidth, StrokeCap.Round)
        } else {
            drawLine(color, point(.33f, .33f), point(.67f, .67f), strokeWidth, StrokeCap.Round)
            drawLine(color, point(.67f, .33f), point(.33f, .67f), strokeWidth, StrokeCap.Round)
        }
    }
}
