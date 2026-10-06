package com.xcluice.manzil

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Right-to-left page-curl book.
 * Next page: the current page peels from its LEFT edge towards the right (swipe left -> right).
 * Previous page: the earlier page curls back in from the RIGHT edge (swipe right -> left).
 */
class CurlView(ctx: Context) : View(ctx) {
    var count = 0
    var loader: (Int) -> Bitmap? = { null }
    var onIndex: (Int) -> Unit = {}
    var index = 0
        private set

    private enum class Mode { NONE, NEXT, PREV }

    private var mode = Mode.NONE
    private var pw = 0f
    private var ph = 0f
    private var ox = 0f
    private var oy = 0f
    private var tx = 0f
    private var ty = 0f
    private var bottom = true
    private var downX = 0f
    private var downY = 0f
    private var baseX = 0f
    private var vt: VelocityTracker? = null
    private var anim: ValueAnimator? = null
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private val ratio = 754f / 1190f

    // fold line state
    private var mx = 0f
    private var my = 0f
    private var ux = 1f
    private var uy = 0f

    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val shaderPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val backPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 255, 255, 255) }
    private val shadePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val invertFilter = ColorMatrixColorFilter(
        ColorMatrix(floatArrayOf(
            -1f, 0f, 0f, 0f, 255f,
            0f, -1f, 0f, 0f, 255f,
            0f, 0f, -1f, 0f, 255f,
            0f, 0f, 0f, 1f, 0f
        ))
    )
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    var invert = false
        set(v) {
            field = v
            val f = if (v) invertFilter else null
            bmpPaint.colorFilter = f
            shaderPaint.colorFilter = f
            backPaint.color = if (v) Color.argb(150, 0, 0, 0) else Color.argb(150, 255, 255, 255)
            invalidate()
        }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val m = resources.displayMetrics.density * 14f
        var cw = w - 2f * m
        var ch = cw / ratio
        if (ch > h - 2f * m) {
            ch = h - 2f * m
            cw = ch * ratio
        }
        pw = cw
        ph = ch
        ox = (w - pw) / 2f
        oy = (h - ph) / 2f
    }

    fun jumpTo(i: Int) {
        anim?.cancel()
        anim = null
        mode = Mode.NONE
        index = i.coerceIn(0, maxOf(0, count - 1))
        onIndex(index)
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        if (pw <= 0f || count == 0) return
        drawShadow(c)
        c.save()
        c.translate(ox, oy)
        c.clipRect(0f, 0f, pw, ph)
        if (mode == Mode.NONE) drawFlat(c, loader(index)) else drawCurl(c)
        c.restore()
    }

    private fun drawShadow(c: Canvas) {
        val d = resources.displayMetrics.density
        edgePaint.style = Paint.Style.FILL
        for (i in 1..8) {
            edgePaint.color = Color.argb(if (invert) 0 else 14, 0, 0, 0)
            val g = i * 1.6f * d
            c.drawRoundRect(ox - g, oy - g + 2f * d, ox + pw + g, oy + ph + g + 2f * d, g, g, edgePaint)
        }
        edgePaint.style = Paint.Style.STROKE
        edgePaint.strokeWidth = d
        edgePaint.color = Color.argb(70, 128, 128, 128)
        c.drawRect(ox - d / 2, oy - d / 2, ox + pw + d / 2, oy + ph + d / 2, edgePaint)
    }

    private fun drawFlat(c: Canvas, b: Bitmap?) {
        if (b == null) return
        c.drawBitmap(b, null, RectF(0f, 0f, pw, ph), bmpPaint)
    }

    private fun side(px: Float, py: Float) = (px - mx) * ux + (py - my) * uy

    private fun clip(sgn: Float): Path? {
        val pts = floatArrayOf(0f, 0f, pw, 0f, pw, ph, 0f, ph)
        val out = ArrayList<Float>()
        for (i in 0 until 4) {
            val ax = pts[2 * i]
            val ay = pts[2 * i + 1]
            val bx = pts[2 * ((i + 1) % 4)]
            val by = pts[2 * ((i + 1) % 4) + 1]
            val fa = side(ax, ay) * sgn
            val fb = side(bx, by) * sgn
            if (fa >= 0f) { out.add(ax); out.add(ay) }
            if ((fa >= 0f) != (fb >= 0f)) {
                val t = fa / (fa - fb)
                out.add(ax + (bx - ax) * t)
                out.add(ay + (by - ay) * t)
            }
        }
        if (out.size < 6) return null
        val p = Path()
        p.moveTo(out[0], out[1])
        var i = 2
        while (i < out.size) { p.lineTo(out[i], out[i + 1]); i += 2 }
        p.close()
        return p
    }

    private fun shaderFor(b: Bitmap, refl: Matrix?): Paint {
        val sh = BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        val m = Matrix()
        m.setScale(pw / b.width, ph / b.height)
        if (refl != null) m.postConcat(refl)
        sh.setLocalMatrix(m)
        shaderPaint.shader = sh
        return shaderPaint
    }

    private fun drawCurl(c: Canvas) {
        val next = mode == Mode.NEXT
        val peeled = loader(if (next) index else index - 1)
        val under = loader(if (next) index + 1 else index)
        val cy = if (bottom) ph else 0f
        val ddx = tx
        val ddy = ty - cy
        val len = hypot(ddx, ddy)
        if (len < 1.5f) { drawFlat(c, peeled); return }
        if (tx >= 2f * pw - 0.5f) { drawFlat(c, under); return }
        ux = ddx / len
        uy = ddy / len
        mx = tx / 2f
        my = (cy + ty) / 2f
        val md = mx * ux + my * uy

        // 1) the page underneath, with a soft shadow where the fold lifts away
        drawFlat(c, under)
        val corner = clip(-1f)
        val flat = clip(1f)
        if (corner != null) {
            shadePaint.shader = LinearGradient(
                mx, my, mx - ux * 42f, my - uy * 42f,
                Color.argb(120, 0, 0, 0), Color.argb(0, 0, 0, 0), Shader.TileMode.CLAMP
            )
            c.drawPath(corner, shadePaint)
        }

        // 2) the part of the page that is still lying flat
        if (flat != null && peeled != null) c.drawPath(flat, shaderFor(peeled, null))

        // 3) the lifted flap (mirror image of the folded part, paper back shows through)
        if (corner != null && peeled != null) {
            val refl = Matrix()
            refl.setValues(
                floatArrayOf(
                    1f - 2f * ux * ux, -2f * ux * uy, 2f * md * ux,
                    -2f * ux * uy, 1f - 2f * uy * uy, 2f * md * uy,
                    0f, 0f, 1f
                )
            )
            val flap = Path(corner)
            flap.transform(refl)
            c.drawPath(flap, shaderFor(peeled, refl))
            c.drawPath(flap, backPaint)
            shadePaint.shader = LinearGradient(
                mx, my, mx + ux * 64f, my + uy * 64f,
                intArrayOf(
                    Color.argb(150, 0, 0, 0), Color.argb(0, 0, 0, 0),
                    Color.argb(70, 255, 255, 255), Color.argb(0, 255, 255, 255)
                ),
                floatArrayOf(0f, 0.38f, 0.58f, 1f), Shader.TileMode.CLAMP
            )
            c.drawPath(flap, shadePaint)
        }
    }

    private fun begin(m: Mode) {
        mode = m
        bottom = (downY - oy) > ph / 2f
    }

    private fun follow(x: Float, y: Float) {
        val dx = x - baseX
        tx = if (mode == Mode.NEXT) (dx * 1.8f).coerceIn(2f, 2f * pw)
        else (2f * pw + dx * 1.8f).coerceIn(2f, 2f * pw)
        val cy = if (bottom) ph else 0f
        val raw = ((y - oy) - cy) * 0.3f
        val lim = tx * 0.6f
        ty = (cy + raw.coerceIn(-lim, lim)).coerceIn(0f, ph)
        invalidate()
    }

    private fun settle(complete: Boolean) {
        val next = mode == Mode.NEXT
        val cy = if (bottom) ph else 0f
        val toTx = if (next) (if (complete) 2f * pw else 1f) else (if (complete) 1f else 2f * pw)
        val fromTx = tx
        val fromTy = ty
        val frac = abs(toTx - fromTx) / (2f * pw)
        val a = ValueAnimator.ofFloat(0f, 1f)
        a.duration = (230 + 260 * frac).toLong()
        a.interpolator = DecelerateInterpolator(1.3f)
        a.addUpdateListener {
            val t = it.animatedValue as Float
            tx = fromTx + (toTx - fromTx) * t
            ty = fromTy + (cy - fromTy) * t
            invalidate()
        }
        a.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(an: Animator) {
                anim = null
                if (complete) {
                    index += if (next) 1 else -1
                    index = index.coerceIn(0, count - 1)
                }
                mode = Mode.NONE
                invalidate()
                if (complete) onIndex(index)
            }
        })
        anim = a
        a.start()
    }

    private fun autoFlip(m: Mode) {
        downY = oy + ph
        begin(m)
        bottom = true
        if (m == Mode.NEXT) { tx = 2f; ty = ph } else { tx = 2f * pw; ty = ph }
        settle(true)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (count == 0) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                anim?.end()
                downX = e.x
                downY = e.y
                vt?.recycle()
                vt = VelocityTracker.obtain()
                vt?.addMovement(e)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                vt?.addMovement(e)
                val dx = e.x - downX
                val dy = e.y - downY
                if (mode == Mode.NONE && anim == null && abs(dx) > slop && abs(dx) > abs(dy)) {
                    if (dx > 0 && index < count - 1) { begin(Mode.NEXT); baseX = e.x }
                    else if (dx < 0 && index > 0) { begin(Mode.PREV); baseX = e.x }
                }
                if (mode != Mode.NONE && anim == null) follow(e.x, e.y)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                vt?.addMovement(e)
                vt?.computeCurrentVelocity(1000)
                val vx = vt?.xVelocity ?: 0f
                vt?.recycle()
                vt = null
                if (anim != null) return true
                if (mode == Mode.NONE) {
                    if (e.actionMasked == MotionEvent.ACTION_UP &&
                        abs(e.x - downX) < slop && abs(e.y - downY) < slop
                    ) {
                        if (e.x < width * 0.3f && index < count - 1) autoFlip(Mode.NEXT)
                        else if (e.x > width * 0.7f && index > 0) autoFlip(Mode.PREV)
                    }
                } else if (mode == Mode.NEXT) {
                    settle(tx > pw * 0.8f || vx > 900f)
                } else {
                    settle(tx < pw * 1.2f || vx < -900f)
                }
                return true
            }
        }
        return true
    }
}
