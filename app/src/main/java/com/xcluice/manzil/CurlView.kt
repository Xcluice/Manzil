package com.xcluice.manzil

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * Right-to-left book with a real rolled (cylindrical) page curl.
 * Next page: the current page rolls up from its LEFT edge towards the right (swipe left -> right).
 * Previous page: the earlier page rolls back in from the RIGHT edge (swipe right -> left).
 */
class CurlView(ctx: Context) : View(ctx) {
    var count = 0
    var loader: (Int) -> Bitmap? = { null }
    var onIndex: (Int) -> Unit = {}
    var index = 0
        private set

    private enum class Mode { NONE, NEXT, PREV }

    private val d = ctx.resources.displayMetrics.density
    private val radius = 15f * d
    private val cols = 40
    private val rows = 56
    private val verts = FloatArray((cols + 1) * (rows + 1) * 2)
    private val shadeCols = IntArray((cols + 1) * (rows + 1))
    private val backCols = IntArray((cols + 1) * (rows + 1))
    private val white1: Bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).also { it.eraseColor(Color.WHITE) }

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
    private val veilPaint = Paint()
    private val shadePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val nightFilter = ColorMatrixColorFilter(
        ColorMatrix(
            floatArrayOf(
                -0.372f, -0.731f, -0.142f, 0f, 263f,
                -0.328f, -0.643f, -0.125f, 0f, 227f,
                -0.236f, -0.464f, -0.090f, 0f, 160f,
                0f, 0f, 0f, 1f, 0f
            )
        )
    )

    /** Night reading: gold ink on dark parchment. */
    var invert = false
        set(v) {
            field = v
            bmpPaint.colorFilter = if (v) nightFilter else null
            shaderPaint.colorFilter = if (v) nightFilter else null
            invalidate()
        }

    private val maxTx get() = 2f * (pw + radius + 6f * d)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val m = 14f * d
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
        drawBookEdges(c)
        c.save()
        c.translate(ox, oy)
        c.clipRect(0f, 0f, pw, ph)
        if (mode == Mode.NONE) drawFlat(c, loader(index)) else drawCurl(c)
        c.restore()
    }

    /** Soft shadow plus the stacks of pages: unread on the left, read on the right (spine side). */
    private fun drawBookEdges(c: Canvas) {
        edgePaint.style = Paint.Style.FILL
        if (!invert) {
            for (i in 1..8) {
                edgePaint.color = Color.argb(13, 0, 0, 0)
                val g = i * 1.6f * d
                c.drawRoundRect(ox - g, oy - g + 2f * d, ox + pw + g, oy + ph + g + 2f * d, g, g, edgePaint)
            }
        }
        val span = maxOf(1, count - 1)
        val left = if (index < count - 1) min(5, 1 + ((count - 1 - index) * 5) / span) else 0
        val right = if (index > 0) min(5, 1 + (index * 5) / span) else 0
        edgePaint.style = Paint.Style.STROKE
        edgePaint.strokeWidth = 1.1f * d
        for (i in 1..left) {
            edgePaint.color = if (i % 2 == 0) Color.argb(200, 120, 92, 40) else Color.argb(200, 188, 150, 78)
            val x = ox - i * 1.7f * d
            c.drawLine(x, oy + i * d, x, oy + ph - i * d * 0.4f, edgePaint)
        }
        for (i in 1..right) {
            edgePaint.color = if (i % 2 == 0) Color.argb(200, 120, 92, 40) else Color.argb(200, 188, 150, 78)
            val x = ox + pw + i * 1.7f * d
            c.drawLine(x, oy + i * d, x, oy + ph - i * d * 0.4f, edgePaint)
        }
        edgePaint.strokeWidth = d
        edgePaint.color = Color.argb(90, 70, 50, 20)
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

    private fun shaderFor(b: Bitmap): Paint {
        val sh = BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        val m = Matrix()
        m.setScale(pw / b.width, ph / b.height)
        sh.setLocalMatrix(m)
        shaderPaint.shader = sh
        return shaderPaint
    }

    /** Wraps the lifted part of the page around a cylinder of [radius] lying on the fold line. */
    private fun buildMesh() {
        val pi = Math.PI.toFloat()
        val backRgb = if (invert) intArrayOf(34, 24, 10) else intArrayOf(236, 208, 142)
        var k = 0
        var ci = 0
        for (j in 0..rows) {
            val py = ph * j / rows
            for (i in 0..cols) {
                val px = pw * i / cols
                val sd = side(px, py)
                if (sd >= 0f) {
                    verts[k++] = px - sd * ux
                    verts[k++] = py - sd * uy
                    shadeCols[ci] = 0
                    backCols[ci] = 0
                } else {
                    val s = -sd
                    val fx = px + s * ux
                    val fy = py + s * uy
                    val th = s / radius
                    val along = if (th <= pi) -radius * sin(th) else s - pi * radius
                    verts[k++] = fx + ux * along
                    verts[k++] = fy + uy * along
                    val sn = if (th < pi) sin(th) else 0f
                    shadeCols[ci] = Color.argb((120f * sn).toInt(), 0, 0, 0)
                    val ba = 0.64f * ((th - pi / 2f) / 0.7f).coerceIn(0f, 1f)
                    backCols[ci] = Color.argb((255f * ba).toInt(), backRgb[0], backRgb[1], backRgb[2])
                }
                ci++
            }
        }
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
        if (tx >= maxTx - 0.5f) { drawFlat(c, under); return }
        ux = ddx / len
        uy = ddy / len
        mx = tx / 2f
        my = (cy + ty) / 2f

        // page underneath, shadowed by the roll
        drawFlat(c, under)
        val corner = clip(-1f)
        val flat = clip(1f)
        if (corner != null) {
            shadePaint.shader = LinearGradient(
                mx, my, mx - ux * radius * 3.4f, my - uy * radius * 3.4f,
                Color.argb(150, 0, 0, 0), Color.argb(0, 0, 0, 0), Shader.TileMode.CLAMP
            )
            c.drawPath(corner, shadePaint)
        }

        // part still lying flat
        if (flat != null && peeled != null) c.drawPath(flat, shaderFor(peeled))

        // rolled part + underside of the paper
        if (peeled != null) {
            buildMesh()
            c.drawBitmapMesh(peeled, cols, rows, verts, 0, null, 0, bmpPaint)
            c.drawBitmapMesh(white1, cols, rows, verts, 0, backCols, 0, veilPaint)
            c.drawBitmapMesh(white1, cols, rows, verts, 0, shadeCols, 0, veilPaint)
        }
    }

    private fun begin(m: Mode) {
        mode = m
        bottom = (downY - oy) > ph / 2f
    }

    private fun follow(x: Float, y: Float) {
        val dx = x - baseX
        val mt = maxTx
        tx = if (mode == Mode.NEXT) (dx * 1.8f).coerceIn(2f, mt) else (mt + dx * 1.8f).coerceIn(2f, mt)
        val cy = if (bottom) ph else 0f
        val raw = ((y - oy) - cy) * 0.3f
        val lim = tx * 0.6f
        ty = (cy + raw.coerceIn(-lim, lim)).coerceIn(0f, ph)
        invalidate()
    }

    private fun settle(complete: Boolean) {
        val next = mode == Mode.NEXT
        val cy = if (bottom) ph else 0f
        val mt = maxTx
        val toTx = if (next) (if (complete) mt else 1f) else (if (complete) 1f else mt)
        val fromTx = tx
        val fromTy = ty
        val frac = abs(toTx - fromTx) / mt
        val a = ValueAnimator.ofFloat(0f, 1f)
        a.duration = (260 + 300 * frac).toLong()
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
                if (complete) {
                    onIndex(index)
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                }
            }
        })
        anim = a
        a.start()
    }

    private fun autoFlip(m: Mode) {
        downY = oy + ph
        begin(m)
        bottom = true
        if (m == Mode.NEXT) { tx = 2f; ty = ph } else { tx = maxTx; ty = ph }
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
