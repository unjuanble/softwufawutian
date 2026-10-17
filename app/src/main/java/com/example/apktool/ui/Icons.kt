package com.example.apktool.ui

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
import androidx.compose.ui.unit.dp

/**
 * 几个手绘小图标。
 *
 * 刻意不用 material-icons 扩展包（那会多一个依赖），也不用「☰ ▸ ↑ ⚙」这类字符
 * —— 系统字体缺字形时它们会渲染成豆腐块。这里全部用 Canvas 画，任何设备都一致。
 */

/** 展开/收起三角：down=true 朝下（已展开），false 朝右（已折叠）。 */
@Composable
fun Caret(down: Boolean, color: Color, modifier: Modifier = Modifier.size(14.dp)) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val p = Path()
        if (down) {
            p.moveTo(0f, h * 0.28f); p.lineTo(w, h * 0.28f); p.lineTo(w / 2f, h * 0.82f)
        } else {
            p.moveTo(w * 0.32f, 0f); p.lineTo(w * 0.32f, h); p.lineTo(w * 0.86f, h / 2f)
        }
        p.close()
        drawPath(p, color)
    }
}

/** 文件夹。 */
@Composable
fun FolderIcon(color: Color, modifier: Modifier = Modifier.size(22.dp)) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        drawRoundRect(
            color = color,
            topLeft = Offset(0f, h * 0.20f),
            size = Size(w, h * 0.72f),
            cornerRadius = CornerRadius(w * 0.16f),
        )
        drawRoundRect(
            color = color,
            topLeft = Offset(w * 0.06f, h * 0.02f),
            size = Size(w * 0.46f, h * 0.3f),
            cornerRadius = CornerRadius(w * 0.1f),
        )
    }
}

/** 汉堡菜单（左上角 ☰）。 */
@Composable
fun HamburgerIcon(color: Color, modifier: Modifier = Modifier.size(26.dp)) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        for (i in 0..2) {
            drawRoundRect(
                color = color,
                topLeft = Offset(0f, h * (0.10f + i * 0.34f)),
                size = Size(w, h * 0.13f),
                cornerRadius = CornerRadius(h * 0.07f),
            )
        }
    }
}

/** 输入栏右侧的圆形发送按钮里的向上箭头。 */
@Composable
fun ArrowUpIcon(color: Color, modifier: Modifier = Modifier.size(20.dp)) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        drawLine(
            color = color,
            start = Offset(w / 2f, h * 0.92f),
            end = Offset(w / 2f, h * 0.22f),
            strokeWidth = w * 0.14f,
            cap = StrokeCap.Round,
        )
        val p = Path()
        p.moveTo(w * 0.16f, h * 0.42f)
        p.lineTo(w * 0.84f, h * 0.42f)
        p.lineTo(w / 2f, h * 0.02f)
        p.close()
        drawPath(p, color)
    }
}

/** 返回箭头（自己画，避免依赖 material-icons 与字体字形）。 */
@Composable
fun BackArrow(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val sw = w * 0.11f
        drawLine(color, Offset(w * 0.64f, h * 0.16f), Offset(w * 0.32f, h * 0.50f), strokeWidth = sw, cap = StrokeCap.Round)
        drawLine(color, Offset(w * 0.32f, h * 0.50f), Offset(w * 0.64f, h * 0.84f), strokeWidth = sw, cap = StrokeCap.Round)
    }
}
