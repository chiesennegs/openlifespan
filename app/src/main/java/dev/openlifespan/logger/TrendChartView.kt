package dev.openlifespan.logger

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import kotlin.math.max

class TrendChartView(context: Context) : View(context) {
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xff2563eb.toInt(); strokeWidth = 7f; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x332563eb; style = Paint.Style.FILL }
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xffdbe4f0.toInt(); strokeWidth = 1f }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xff526174.toInt(); textSize = 26f; textAlign = Paint.Align.CENTER }
    var points: List<TrendPoint> = emptyList(); var title: String = "Distance"
    override fun onDraw(canvas: Canvas) {
        if (points.isEmpty()) return
        val left = 56f; val right = width - 24f; val top = 36f; val bottom = height - 58f
        val maxValue = max(0.1, points.maxOf { it.distance })
        for (i in 0..3) { val y = bottom - (bottom - top) * i / 3f; canvas.drawLine(left, y, right, y, grid) }
        val path = Path(); val area = Path(); val step = if (points.size == 1) 0f else (right - left) / (points.size - 1)
        points.forEachIndexed { index, point ->
            val x = left + index * step; val y = bottom - (point.distance / maxValue * (bottom - top)).toFloat()
            if (index == 0) { path.moveTo(x, y); area.moveTo(x, bottom); area.lineTo(x, y) } else { path.lineTo(x, y); area.lineTo(x, y) }
            canvas.drawCircle(x, y, 8f, line); canvas.drawText(point.label, x, height - 18f, text)
        }
        area.lineTo(right, bottom); area.close(); canvas.drawPath(area, fill); canvas.drawPath(path, line)
        text.textAlign = Paint.Align.LEFT; text.textSize = 28f; canvas.drawText(title + " (mi)", left, 24f, text); text.textAlign = Paint.Align.CENTER
    }
}
