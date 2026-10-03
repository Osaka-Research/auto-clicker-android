package com.harvest.autoclicker

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.core.content.ContextCompat
import kotlin.math.hypot
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** Full-screen marker layer. In edit mode it captures touches: tap empty space
 *  to add a point, drag an existing point to reposition it, tap a point without
 *  dragging to open its word/remove picker (via [onPointTapped]); drag from
 *  empty space instead draws a swipe gesture line (tap an existing line to
 *  remove it). Outside edit mode the host makes the window untouchable, so
 *  this view is display-only and never blocks the app below. Everything is
 *  stored as fractions (0..1) of the real screen so gestures replay at the
 *  exact same spot regardless of which app is in front. */
class ClickCanvasView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    private val points = mutableListOf<ClickPoint>()
    private val swipes = mutableListOf<SwipeGesture>()
    private val touchSlopPx = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val dotRadiusPx = resources.displayMetrics.density * 16f
    private val swipeHitPx = resources.displayMetrics.density * 20f

    private var dragIndex = -1
    private var swipeHitIndex = -1
    private var downX = 0f
    private var downY = 0f
    private var dragCurrentX = 0f
    private var dragCurrentY = 0f
    private var moved = false
    private val swipePathSamples = mutableListOf<Pair<Float, Float>>()
    private val minSamplePx = resources.displayMetrics.density * 6f
    private var swipeDrawStartMs = 0L

    private val tintPaint = Paint().apply { color = 0x22FFFFFF }
    private val dotPalette = intArrayOf(
        0xFFF44336.toInt(), 0xFF2196F3.toInt(), 0xFF4CAF50.toInt(), 0xFFFF9800.toInt(),
        0xFF9C27B0.toInt(), 0xFF00BCD4.toInt(), 0xFFFFEB3B.toInt(), 0xFFE91E63.toInt(),
        0xFF8BC34A.toInt(), 0xFF3F51B5.toInt()
    )
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val wordRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 3f
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.point_label)
        textAlign = Paint.Align.CENTER
        textSize = resources.displayMetrics.density * 14f
    }
    private val ocrRegionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 2f
    }
    private val swipePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF00E5FF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 3f
    }
    private val swipeEndDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF00E5FF.toInt() }

    private data class TapFlash(val nx: Float, val ny: Float, val startedAtMs: Long)
    private val flashes = mutableListOf<TapFlash>()
    private val flashDurationMs = 400L
    private val flashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFEB3B.toInt()
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 3f
    }

    /** Briefly rings the exact spot a tap just fired at — this is the only
     *  thing shown outside edit mode, so the screen stays uncluttered while
     *  the clicker runs but you can still see it's alive and where. */
    fun flashTapAt(nx: Float, ny: Float) {
        flashes.add(TapFlash(nx, ny, System.currentTimeMillis()))
        postInvalidateOnAnimation()
    }

    var editing: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    /** False when hosted over a real screenshot image (the picture itself
     *  is already visible) instead of over the live app underneath. */
    var drawTint: Boolean = true

    /** When >= 0, all touches define an OCR wait-region for that point index
     *  instead of the normal add/drag/tap handling. */
    private var boxDrawIndex = -1
    private var boxStartX = 0f
    private var boxStartY = 0f
    private var boxCurrentX = 0f
    private var boxCurrentY = 0f

    var onPointsChanged: ((List<ClickPoint>) -> Unit)? = null
    var onPointTapped: ((index: Int, screenX: Float, screenY: Float) -> Unit)? = null
    var onOcrBoxDrawn: ((index: Int, region: OcrRegion) -> Unit)? = null
    var onSwipeDrawn: ((SwipeGesture) -> Unit)? = null
    var onSwipeTapped: ((index: Int) -> Unit)? = null

    fun setPoints(newPoints: List<ClickPoint>) {
        points.clear()
        points.addAll(newPoints)
        invalidate()
    }

    fun setSwipes(newSwipes: List<SwipeGesture>) {
        swipes.clear()
        swipes.addAll(newSwipes)
        invalidate()
    }

    fun startDrawingOcrBox(index: Int) {
        boxDrawIndex = index
    }

    /** Shortest distance from point (px,py) to segment (x1,y1)-(x2,y2). */
    private fun distanceToSegment(px: Float, py: Float, x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x2 - x1
        val dy = y2 - y1
        val lengthSq = dx * dx + dy * dy
        val t = if (lengthSq == 0f) 0f else (((px - x1) * dx + (py - y1) * dy) / lengthSq).coerceIn(0f, 1f)
        val closestX = x1 + t * dx
        val closestY = y1 + t * dy
        return hypot((px - closestX).toDouble(), (py - closestY).toDouble()).toFloat()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (editing) {
            if (drawTint) {
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), tintPaint)
            }
            points.forEachIndexed { index, point ->
                val x = point.nx * width
                val y = point.ny * height
                val color = dotPalette[index % dotPalette.size]
                dotPaint.color = color
                point.ocrRegion?.let { region ->
                    ocrRegionPaint.color = color
                    canvas.drawRect(
                        region.left * width, region.top * height,
                        region.right * width, region.bottom * height,
                        ocrRegionPaint
                    )
                }
                canvas.drawCircle(x, y, dotRadiusPx, dotPaint)
                if (point.wordId != null) {
                    canvas.drawCircle(x, y, dotRadiusPx, wordRingPaint)
                }
                canvas.drawText((index + 1).toString(), x, y + labelPaint.textSize / 3, labelPaint)
            }
            swipes.forEach { swipe ->
                val screenPath = swipe.path?.map { (px, py) -> px * width to py * height }
                drawSwipe(canvas, swipe.startNx * width, swipe.startNy * height, swipe.endNx * width, swipe.endNy * height, screenPath)
            }
        }
        if (boxDrawIndex >= 0) {
            ocrRegionPaint.color = dotPalette[boxDrawIndex % dotPalette.size]
            canvas.drawRect(
                minOf(boxStartX, boxCurrentX), minOf(boxStartY, boxCurrentY),
                maxOf(boxStartX, boxCurrentX), maxOf(boxStartY, boxCurrentY),
                ocrRegionPaint
            )
        }
        if (boxDrawIndex < 0 && dragIndex < 0 && swipeHitIndex < 0 && moved) {
            val preview = swipePathSamples.map { (px, py) -> px * width to py * height }
            drawSwipe(canvas, downX, downY, dragCurrentX, dragCurrentY, preview)
        }

        val now = System.currentTimeMillis()
        flashes.removeAll { now - it.startedAtMs > flashDurationMs }
        flashes.forEach { flash ->
            val progress = ((now - flash.startedAtMs) / flashDurationMs.toFloat()).coerceIn(0f, 1f)
            flashPaint.alpha = ((1f - progress) * 255).toInt().coerceIn(0, 255)
            canvas.drawCircle(
                flash.nx * width, flash.ny * height,
                dotRadiusPx * (0.6f + progress * 0.8f),
                flashPaint
            )
        }
        if (flashes.isNotEmpty()) postInvalidateOnAnimation()
    }

    private fun drawSwipe(canvas: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, path: List<Pair<Float, Float>>? = null) {
        if (path != null && path.size >= 2) {
            val curve = android.graphics.Path()
            curve.moveTo(path[0].first, path[0].second)
            for (i in 1 until path.size) curve.lineTo(path[i].first, path[i].second)
            canvas.drawPath(curve, swipePaint)
        } else {
            canvas.drawLine(x1, y1, x2, y2, swipePaint)
        }
        canvas.drawCircle(x1, y1, dotRadiusPx * 0.4f, swipeEndDotPaint)
        val angle = atan2((y2 - y1).toDouble(), (x2 - x1).toDouble())
        val arrowLen = dotRadiusPx * 0.9f
        val leftAngle = angle + Math.PI * 0.8
        val rightAngle = angle - Math.PI * 0.8
        canvas.drawLine(x2, y2, x2 + (arrowLen * cos(leftAngle)).toFloat(), y2 + (arrowLen * sin(leftAngle)).toFloat(), swipePaint)
        canvas.drawLine(x2, y2, x2 + (arrowLen * cos(rightAngle)).toFloat(), y2 + (arrowLen * sin(rightAngle)).toFloat(), swipePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (boxDrawIndex >= 0) {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    boxStartX = event.x
                    boxStartY = event.y
                    boxCurrentX = event.x
                    boxCurrentY = event.y
                }
                MotionEvent.ACTION_MOVE -> {
                    boxCurrentX = event.x
                    boxCurrentY = event.y
                    invalidate()
                }
                MotionEvent.ACTION_UP -> {
                    val index = boxDrawIndex
                    boxDrawIndex = -1
                    val region = OcrRegion(
                        (minOf(boxStartX, boxCurrentX) / width).coerceIn(0f, 1f),
                        (minOf(boxStartY, boxCurrentY) / height).coerceIn(0f, 1f),
                        (maxOf(boxStartX, boxCurrentX) / width).coerceIn(0f, 1f),
                        (maxOf(boxStartY, boxCurrentY) / height).coerceIn(0f, 1f)
                    )
                    invalidate()
                    onOcrBoxDrawn?.invoke(index, region)
                }
            }
            return true
        }
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                dragCurrentX = event.x
                dragCurrentY = event.y
                moved = false
                dragIndex = points.indexOfFirst { point ->
                    val px = point.nx * width
                    val py = point.ny * height
                    hypot((event.x - px).toDouble(), (event.y - py).toDouble()) <= dotRadiusPx * 1.5
                }
                swipeHitIndex = if (dragIndex >= 0) -1 else swipes.indexOfFirst { swipe ->
                    distanceToSegment(
                        event.x, event.y,
                        swipe.startNx * width, swipe.startNy * height,
                        swipe.endNx * width, swipe.endNy * height
                    ) <= swipeHitPx
                }
                swipePathSamples.clear()
                if (dragIndex < 0 && swipeHitIndex < 0) {
                    swipePathSamples.add((event.x / width) to (event.y / height))
                    swipeDrawStartMs = System.currentTimeMillis()
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (hypot((event.x - downX).toDouble(), (event.y - downY).toDouble()) > touchSlopPx) {
                    moved = true
                }
                dragCurrentX = event.x
                dragCurrentY = event.y
                if (dragIndex >= 0 && moved) {
                    points[dragIndex] = points[dragIndex].copy(
                        nx = (event.x / width).coerceIn(0f, 1f),
                        ny = (event.y / height).coerceIn(0f, 1f)
                    )
                } else if (dragIndex < 0 && swipeHitIndex < 0) {
                    val last = swipePathSamples.lastOrNull()
                    val lastPx = (last?.first ?: 0f) * width
                    val lastPy = (last?.second ?: 0f) * height
                    if (last == null || hypot((event.x - lastPx).toDouble(), (event.y - lastPy).toDouble()) >= minSamplePx) {
                        swipePathSamples.add((event.x / width) to (event.y / height))
                    }
                }
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                when {
                    dragIndex >= 0 && moved -> onPointsChanged?.invoke(points.toList())
                    dragIndex >= 0 -> onPointTapped?.invoke(dragIndex, event.x, event.y)
                    swipeHitIndex >= 0 && !moved -> onSwipeTapped?.invoke(swipeHitIndex)
                    swipeHitIndex < 0 && moved -> {
                        swipePathSamples.add((event.x / width) to (event.y / height))
                        // Replay at the speed you actually dragged it, clamped to a
                        // range that still reads as a real fling (fast apps like
                        // paging feeds need real velocity, not a slow drag).
                        val actualDragMs = System.currentTimeMillis() - swipeDrawStartMs
                        val swipe = SwipeGesture(
                            (downX / width).coerceIn(0f, 1f), (downY / height).coerceIn(0f, 1f),
                            (event.x / width).coerceIn(0f, 1f), (event.y / height).coerceIn(0f, 1f),
                            durationMs = actualDragMs.coerceIn(80L, 600L),
                            recordedAtMs = System.currentTimeMillis(),
                            path = swipePathSamples.toList().takeIf { it.size >= 3 }
                        )
                        invalidate()
                        onSwipeDrawn?.invoke(swipe)
                    }
                    !moved -> {
                        points.add(ClickPoint(event.x / width, event.y / height, recordedAtMs = System.currentTimeMillis()))
                        invalidate()
                        onPointsChanged?.invoke(points.toList())
                    }
                }
                dragIndex = -1
                swipeHitIndex = -1
                moved = false
                swipePathSamples.clear()
            }
        }
        return true
    }
}
