package com.tvsmartconnect

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

/** Lớp phủ trong suốt vẽ con trỏ chuột; không nhận sự kiện chạm. */
class CursorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var cursorX = 0f
        private set
    var cursorY = 0f
        private set

    private val d = resources.displayMetrics.density
    private val arrow = Path().apply {
        moveTo(0f, 0f)
        lineTo(0f, 22 * d)
        lineTo(6 * d, 16.5f * d)
        lineTo(10 * d, 25 * d)
        lineTo(14 * d, 23 * d)
        lineTo(10 * d, 15 * d)
        lineTo(17 * d, 15 * d)
        close()
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF000000.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * d
        strokeJoin = Paint.Join.ROUND
    }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xAAFF9900.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 3 * d
    }
    private var clickFlashUntil = 0L

    private val hide = Runnable { animate().alpha(0f).setDuration(400).start() }

    init {
        isClickable = false
        isFocusable = false
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (oldw == 0) {
            cursorX = w / 2f
            cursorY = h / 2f
        }
    }

    /** Dịch con trỏ; trả về phần dịch bị "tràn" ra ngoài mép (để cuộn trang). */
    fun moveBy(dx: Float, dy: Float): Pair<Float, Float> {
        val nx = cursorX + dx
        val ny = cursorY + dy
        cursorX = nx.coerceIn(0f, (width - 1).toFloat())
        cursorY = ny.coerceIn(0f, (height - 1).toFloat())
        wake()
        return Pair(nx - cursorX, ny - cursorY)
    }

    fun flashClick() {
        clickFlashUntil = System.currentTimeMillis() + 250
        wake()
        postDelayed({ invalidate() }, 260)
    }

    fun wake() {
        removeCallbacks(hide)
        animate().cancel()
        alpha = 1f
        invalidate()
        postDelayed(hide, 5000)
    }

    override fun onDraw(canvas: Canvas) {
        if (System.currentTimeMillis() < clickFlashUntil) {
            canvas.drawCircle(cursorX, cursorY, 16 * d, ring)
        }
        canvas.save()
        canvas.translate(cursorX, cursorY)
        canvas.drawPath(arrow, fill)
        canvas.drawPath(arrow, stroke)
        canvas.restore()
    }
}
