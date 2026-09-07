package dev.openlifespan.logger

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

class TreadmillLogoView(context: Context) : View(context) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(c: Canvas) {
        p.color = 0xff06b6d4.toInt(); p.style = Paint.Style.FILL
        c.drawRoundRect(RectF(5f, 8f, width - 5f, height / 2f + 3f), 7f, 7f, p)
        p.color = 0xfff8fafc.toInt(); p.strokeWidth = 3f; p.style = Paint.Style.STROKE
        c.drawLine(width * .25f, height / 2f + 3f, width * .25f, height - 5f, p)
        c.drawLine(width * .75f, height / 2f + 3f, width * .75f, height - 5f, p)
        c.drawArc(RectF(width * .32f, 11f, width * .68f, height / 2f), 15f, 150f, false, p)
    }
}
