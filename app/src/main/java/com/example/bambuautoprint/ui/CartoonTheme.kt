package com.example.bambuautoprint.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

val Mint = Color(0xFF347A69)
val SoftMint = Color(0xFFE2F1E9)
val Peach = Color(0xFFFFE4D4)
val Cream = Color(0xFFFFF9F1)
val Ink = Color(0xFF253F38)

@Composable fun CartoonTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(primary = Mint, onPrimary = Color.White, primaryContainer = SoftMint,
            secondary = Color(0xFFB86B48), secondaryContainer = Peach, background = Cream, surface = Color.White,
            surfaceVariant = Color(0xFFF0F3EC), onSurface = Ink, onBackground = Ink, outline = Color(0xFFADBFB2)),
        shapes = Shapes(small = RoundedCornerShape(14.dp), medium = RoundedCornerShape(22.dp), large = RoundedCornerShape(30.dp)),
        typography = Typography().let { it.copy(headlineLarge = it.headlineLarge.copy(fontWeight = FontWeight.Bold),
            headlineMedium = it.headlineMedium.copy(fontWeight = FontWeight.Bold), titleLarge = it.titleLarge.copy(fontWeight = FontWeight.Bold),
            titleMedium = it.titleMedium.copy(fontWeight = FontWeight.SemiBold)) },
        content = content
    )
}

/** 原生 Canvas 卡通插画，随屏幕缩放，不依赖网络图片或字体图标。 */
@Composable fun PandaPrinter(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val u = size.width / 240f
        fun p(x: Float, y: Float) = Offset(x * u, y * u)
        fun s(w: Float, h: Float) = Size(w * u, h * u)
        val charcoal = Ink
        drawOval(Color(0xFFD3E6D7), p(24f, 178f), s(196f, 18f))
        drawCircle(Peach, 65 * u, p(124f, 103f))
        drawRoundRect(charcoal, p(45f, 78f), s(150f, 111f), CornerRadius(20*u))
        drawRoundRect(Color(0xFFFDFBF3), p(53f, 86f), s(134f, 94f), CornerRadius(14*u))
        drawRoundRect(SoftMint, p(68f, 99f), s(104f, 56f), CornerRadius(9*u))
        drawLine(charcoal, p(120f, 99f), p(120f, 123f), 4*u)
        drawRoundRect(charcoal, p(110f, 117f), s(20f, 12f), CornerRadius(3*u))
        drawRoundRect(Color(0xFFF1A27E), p(101f, 140f), s(38f, 11f), CornerRadius(5*u))
        drawLine(charcoal, p(76f, 155f), p(164f, 155f), 3*u)
        drawCircle(Mint, 4*u, p(82f, 168f)); drawCircle(Color(0xFFF1A27E), 4*u, p(95f, 168f))
        // 熊猫探头：耳朵、眼圈、鼻子和微笑。
        drawCircle(charcoal, 13*u, p(84f, 43f)); drawCircle(charcoal, 13*u, p(155f, 43f))
        drawOval(Color.White, p(78f, 38f), s(86f, 63f))
        drawOval(charcoal, p(92f, 56f), s(20f, 24f)); drawOval(charcoal, p(133f, 56f), s(20f, 24f))
        drawCircle(Color.White, 3*u, p(103f, 63f)); drawCircle(Color.White, 3*u, p(144f, 63f))
        drawCircle(charcoal, 4*u, p(123f, 78f))
        drawArc(charcoal, 10f, 160f, false, p(117f, 79f), s(12f, 9f), style = Stroke(2*u))
        drawCircle(Peach, 5*u, p(91f, 82f)); drawCircle(Peach, 5*u, p(155f, 82f))
        drawLine(Color(0xFFF1A27E), p(198f, 47f), p(210f, 47f), 3*u)
        drawLine(Color(0xFFF1A27E), p(204f, 41f), p(204f, 53f), 3*u)
        drawCircle(Mint, 3*u, p(37f, 62f))
    }
}

@Composable fun SectionTitle(title: String, subtitle: String = "") {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable fun SoftCard(modifier: Modifier = Modifier, color: Color = Color.White, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier.fillMaxWidth(), shape = RoundedCornerShape(26.dp), colors = CardDefaults.cardColors(containerColor = color)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}
