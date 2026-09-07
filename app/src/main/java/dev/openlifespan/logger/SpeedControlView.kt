package dev.openlifespan.logger

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import java.util.Locale
import kotlin.math.roundToInt

class SpeedControlView(context: Context) : View(context) {
    var progress = 21
        set(value) { field = value.coerceIn(0, 36); invalidate(); onChanged?.invoke(field) }
    var onChanged: ((Int) -> Unit)? = null
    var onCommit: ((Int) -> Unit)? = null
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xffcbd5e1.toInt(); strokeWidth = 6f; strokeCap = Paint.Cap.ROUND }
    private val active = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xff06b6d4.toInt(); strokeWidth = 6f; strokeCap = Paint.Cap.ROUND }
    private val thumb = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xffffffff.toInt(); style = Paint.Style.FILL; setShadowLayer(3f, 0f, 1f, 0x55000000) }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (context.getSharedPreferences("settings", 0).getBoolean("lightMode", false)) 0xff0f172a.toInt() else 0xffffffff.toInt(); textSize = 16f; textAlign = Paint.Align.CENTER }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas); val d = resources.displayMetrics.density; val left = 12f*d; val right = width - 12f*d; val y = 15f*d; val x = left + (right-left) * progress / 36f
        canvas.drawLine(left, y, right, y, track); canvas.drawLine(left, y, x, y, active); canvas.drawCircle(x, y, 10f*d, thumb); label.textSize = 16f*d; canvas.drawText(String.format(Locale.US, "%.1f", 0.4 + progress/10.0), x, 43f*d, label)
    }
    override fun onTouchEvent(event: MotionEvent): Boolean { if (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_MOVE || event.action == MotionEvent.ACTION_UP) { val left=12f*resources.displayMetrics.density; val right=width-left; progress=((event.x-left)/(right-left)*36f).roundToInt().coerceIn(0,36); if(event.action==MotionEvent.ACTION_UP) onCommit?.invoke(progress); return true }; return true }
}
