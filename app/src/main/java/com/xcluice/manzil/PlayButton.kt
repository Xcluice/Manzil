package com.xcluice.manzil

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import kotlin.math.min

/** Round outline play / pause button, drawn in one colour. */
class PlayButton(ctx: Context) : View(ctx) {
    var playing = false
        set(v) { field = v; invalidate() }
    var tint = 0xFF111111.toInt()
        set(v) { field = v; invalidate() }
    private val d = ctx.resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f - 1.5f * d
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1.5f * d
        p.color = tint
        c.drawCircle(cx, cy, r, p)
        p.style = Paint.Style.FILL
        val u = r * 0.42f
        if (playing) {
            c.drawRoundRect(cx - u * 0.9f, cy - u, cx - u * 0.2f, cy + u, 2f * d, 2f * d, p)
            c.drawRoundRect(cx + u * 0.2f, cy - u, cx + u * 0.9f, cy + u, 2f * d, 2f * d, p)
        } else {
            path.reset()
            path.moveTo(cx - u * 0.6f, cy - u)
            path.lineTo(cx + u * 1.0f, cy)
            path.lineTo(cx - u * 0.6f, cy + u)
            path.close()
            c.drawPath(path, p)
        }
    }
}
