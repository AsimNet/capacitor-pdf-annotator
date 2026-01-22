package com.capacitor.pdfannotator

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.PointF
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.annotation.ColorInt
import androidx.annotation.UiThread
import androidx.ink.authoring.InProgressStrokeId
import androidx.ink.authoring.InProgressStrokesFinishedListener
import androidx.ink.authoring.InProgressStrokesView
import androidx.ink.brush.Brush
import androidx.ink.brush.BrushFamily
import androidx.ink.brush.StockBrushes
import androidx.ink.geometry.AffineTransform
import androidx.ink.geometry.Intersection.intersects
import androidx.ink.geometry.MutableParallelogram
import androidx.ink.geometry.MutableSegment
import androidx.ink.geometry.MutableVec
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.strokes.Stroke

/**
 * High-performance ink view using AndroidX Ink API 1.0.0.
 * Provides low-latency stylus rendering with pressure sensitivity and palm rejection.
 */
class AndroidXInkView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr), InProgressStrokesFinishedListener {

    companion object {
        private const val TAG = "AndroidXInkView"

        // Brush type constants for Java compatibility
        const val BRUSH_PRESSURE_PEN = 0
        const val BRUSH_MARKER = 1
        const val BRUSH_HIGHLIGHTER = 2
        const val BRUSH_DASHED_LINE = 3
    }

    /**
     * Brush types available in the ink view
     */
    enum class BrushType(val id: Int) {
        PRESSURE_PEN(BRUSH_PRESSURE_PEN),
        MARKER(BRUSH_MARKER),
        HIGHLIGHTER(BRUSH_HIGHLIGHTER),
        DASHED_LINE(BRUSH_DASHED_LINE);

        companion object {
            fun fromId(id: Int): BrushType = entries.find { it.id == id } ?: PRESSURE_PEN
        }
    }

    // AndroidX Ink components
    private val inProgressStrokesView: InProgressStrokesView = InProgressStrokesView(context)
    private val canvasStrokeRenderer: CanvasStrokeRenderer = CanvasStrokeRenderer.create()
    private val finishedStrokesView: FinishedStrokesView

    // Stroke storage
    private val finishedStrokes = mutableListOf<Stroke>()
    private val undoStack = mutableListOf<Stroke>()

    // Current brush settings
    private var currentBrush: Brush
    @ColorInt private var inkColor: Int = Color.BLACK
    private var strokeWidth: Float = 5f
    private var brushType: BrushType = BrushType.PRESSURE_PEN

    // Touch tracking
    private val pointerIdToStrokeId = mutableMapOf<Int, InProgressStrokeId>()
    private var isMultiTouchActive = false
    private var primaryPointerId = MotionEvent.INVALID_POINTER_ID

    // Eraser mode
    private var isEraserMode = false
    private var previousErasePoint: MutableVec? = null
    private val eraserPadding = 50f  // Hit-testing padding for eraser

    // State
    var drawingEnabled: Boolean = false
        set(value) {
            field = value
            if (!value) {
                // Cancel any in-progress strokes when drawing is disabled
                cancelAllStrokes()
            }
        }

    var pageIndex: Int = 0

    // Callbacks
    private var onInkChangeListener: (() -> Unit)? = null

    init {
        Log.d(TAG, "🖊️ AndroidXInkView initialized - using AndroidX Ink API 1.0.0 with low-latency stylus support")

        // Create initial brush
        currentBrush = createBrush(inkColor, strokeWidth)

        // Configure InProgressStrokesView
        inProgressStrokesView.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)

        // Set up finished strokes listener
        inProgressStrokesView.addFinishedStrokesListener(this)

        // Create finished strokes view first
        finishedStrokesView = FinishedStrokesView(context)

        // Add views in order: finished strokes (bottom), in-progress strokes (top)
        addView(finishedStrokesView)
        addView(inProgressStrokesView)
    }

    private fun createBrush(@ColorInt color: Int, size: Float): Brush {
        // Convert ARGB int to ColorLong
        val colorLong = Color.pack(color)

        // Select brush family based on brush type
        val brushFamily: BrushFamily = when (brushType) {
            BrushType.PRESSURE_PEN -> StockBrushes.pressurePen()
            BrushType.MARKER -> StockBrushes.marker()
            BrushType.HIGHLIGHTER -> StockBrushes.highlighter()
            BrushType.DASHED_LINE -> StockBrushes.dashedLine()
        }

        // For highlighter, make color semi-transparent
        val finalColor = if (brushType == BrushType.HIGHLIGHTER) {
            Color.pack(
                Color.red(color) / 255f,
                Color.green(color) / 255f,
                Color.blue(color) / 255f,
                0.5f // 50% opacity for highlighter
            )
        } else {
            colorLong
        }

        return Brush.createWithColorLong(
            family = brushFamily,
            colorLong = finalColor,
            size = size,
            epsilon = 0.1f
        )
    }

    @UiThread
    override fun onStrokesFinished(strokes: Map<InProgressStrokeId, Stroke>) {
        Log.d(TAG, "🖊️ AndroidX Ink: ${strokes.size} stroke(s) finished with pressure sensitivity")
        for ((_, stroke) in strokes) {
            finishedStrokes.add(stroke)
            undoStack.clear() // Clear redo stack on new stroke
        }

        // Remove finished strokes from InProgressStrokesView
        inProgressStrokesView.removeFinishedStrokes(strokes.keys)

        onInkChangeListener?.invoke()
        finishedStrokesView.invalidate()
    }

    fun setInkColor(@ColorInt color: Int) {
        inkColor = color
        currentBrush = createBrush(color, strokeWidth)
    }

    fun setStrokeWidth(width: Float) {
        strokeWidth = width
        currentBrush = createBrush(inkColor, width)
    }

    /**
     * Set the brush type
     */
    fun setBrushType(type: BrushType) {
        brushType = type
        currentBrush = createBrush(inkColor, strokeWidth)
        Log.d(TAG, "Brush type changed to: ${type.name}")
    }

    /**
     * Set the brush type by ID (Java compatibility)
     */
    fun setBrushTypeById(typeId: Int) {
        setBrushType(BrushType.fromId(typeId))
    }

    /**
     * Get current brush type ID
     */
    fun getBrushTypeId(): Int = brushType.id

    /**
     * Set eraser mode
     */
    fun setEraserMode(enabled: Boolean) {
        isEraserMode = enabled
        previousErasePoint = null
        Log.d(TAG, "Eraser mode: $enabled")
    }

    /**
     * Check if eraser mode is active
     */
    fun isEraserModeActive(): Boolean = isEraserMode

    /**
     * Handle eraser touch - remove strokes that intersect with the eraser path
     * Uses the same approach as Cahier: segment-based parallelogram hit-testing
     */
    @SuppressLint("RestrictedApi")
    private fun handleErase(x: Float, y: Float): Boolean {
        val prev = previousErasePoint
        previousErasePoint = MutableVec(x, y)

        if (prev == null) return true

        // Create segment from previous to current point (Cahier approach)
        val segment = MutableSegment(prev, MutableVec(x, y))
        val parallelogram = MutableParallelogram()
            .populateFromSegmentAndPadding(segment, eraserPadding)

        // Find intersecting strokes using AndroidX Ink geometry
        val strokesToRemove = finishedStrokes.filter { stroke ->
            stroke.shape.intersects(parallelogram, AffineTransform.IDENTITY)
        }

        if (strokesToRemove.isNotEmpty()) {
            finishedStrokes.removeAll(strokesToRemove.toSet())
            finishedStrokesView.setStrokes(finishedStrokes)
            finishedStrokesView.invalidate()
            onInkChangeListener?.invoke()
            Log.d(TAG, "Eraser removed ${strokesToRemove.size} stroke(s)")
        }

        return true
    }

    fun setOnInkChangeListener(listener: (() -> Unit)?) {
        onInkChangeListener = listener
    }

    // Java-compatible listener interface
    fun setOnInkChangeListenerJava(listener: InkCanvasView.OnInkChangeListener?) {
        onInkChangeListener = if (listener != null) {
            { listener.onInkChanged() }
        } else {
            null
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!drawingEnabled) {
            return false
        }

        // If multi-touch is active, don't handle - let parent handle pan/zoom
        if (isMultiTouchActive) {
            return false
        }

        // Handle eraser mode separately
        if (isEraserMode) {
            return handleEraserTouch(event)
        }

        requestUnbufferedDispatch(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                isMultiTouchActive = false
                val pointerId = event.getPointerId(event.actionIndex)
                primaryPointerId = pointerId

                // Request parent to not intercept while we're drawing
                parent?.requestDisallowInterceptTouchEvent(true)

                val strokeId = inProgressStrokesView.startStroke(
                    event = event,
                    pointerId = pointerId,
                    brush = currentBrush
                )
                pointerIdToStrokeId[pointerId] = strokeId
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                // Second finger touched - cancel current stroke and switch to pan/zoom mode
                isMultiTouchActive = true
                cancelAllStrokes()

                // Allow parent to intercept for pan/zoom
                parent?.requestDisallowInterceptTouchEvent(false)

                // Return false to let parent handle multi-touch
                return false
            }

            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount > 1) {
                    // Multi-touch detected during move - cancel and pass to parent
                    isMultiTouchActive = true
                    cancelAllStrokes()

                    // Allow parent to intercept for pan/zoom
                    parent?.requestDisallowInterceptTouchEvent(false)

                    return false
                }

                for (i in 0 until event.pointerCount) {
                    val pointerId = event.getPointerId(i)
                    val strokeId = pointerIdToStrokeId[pointerId] ?: continue
                    inProgressStrokesView.addToStroke(
                        event = event,
                        pointerId = pointerId,
                        strokeId = strokeId,
                        prediction = null
                    )
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                val pointerId = event.getPointerId(event.actionIndex)
                val strokeId = pointerIdToStrokeId.remove(pointerId) ?: return false
                inProgressStrokesView.finishStroke(
                    event = event,
                    pointerId = pointerId,
                    strokeId = strokeId
                )
                primaryPointerId = MotionEvent.INVALID_POINTER_ID
                isMultiTouchActive = false
                return true
            }

            MotionEvent.ACTION_POINTER_UP -> {
                // A finger was lifted but others remain - stay in multi-touch mode
                return false
            }

            MotionEvent.ACTION_CANCEL -> {
                cancelAllStrokes()
                primaryPointerId = MotionEvent.INVALID_POINTER_ID
                isMultiTouchActive = false
                return true
            }
        }

        return false
    }

    /**
     * Handle touch events when in eraser mode
     */
    private fun handleEraserTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                previousErasePoint = null
                // Request parent to not intercept while erasing
                parent?.requestDisallowInterceptTouchEvent(true)
                handleErase(event.x, event.y)
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                // Multi-touch in eraser mode - switch to pan/zoom
                if (event.pointerCount >= 2) {
                    isMultiTouchActive = true
                    previousErasePoint = null
                    // Allow parent to intercept for pan/zoom
                    parent?.requestDisallowInterceptTouchEvent(false)
                    return false
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount > 1) {
                    isMultiTouchActive = true
                    previousErasePoint = null
                    // Allow parent to intercept for pan/zoom
                    parent?.requestDisallowInterceptTouchEvent(false)
                    return false
                }
                handleErase(event.x, event.y)
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                previousErasePoint = null
                isMultiTouchActive = false
                return true
            }

            MotionEvent.ACTION_POINTER_UP -> {
                return false
            }
        }
        return false
    }

    /**
     * Cancel all in-progress strokes (used when switching to pan/zoom mode)
     */
    private fun cancelAllStrokes() {
        for ((_, strokeId) in pointerIdToStrokeId) {
            try {
                // Create a minimal cancel event
                val cancelEvent = MotionEvent.obtain(
                    System.currentTimeMillis(),
                    System.currentTimeMillis(),
                    MotionEvent.ACTION_CANCEL,
                    0f, 0f, 0
                )
                inProgressStrokesView.cancelStroke(strokeId, cancelEvent)
                cancelEvent.recycle()
            } catch (e: Exception) {
                Log.w(TAG, "Error canceling stroke: ${e.message}")
            }
        }
        pointerIdToStrokeId.clear()
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        // Don't intercept if drawing is disabled
        if (!drawingEnabled) {
            return false
        }

        // Don't intercept multi-touch - let parent handle pan/zoom
        if (ev.pointerCount > 1 || isMultiTouchActive) {
            return false
        }

        // Intercept single touch for drawing
        return true
    }

    /**
     * Get strokes as InkStroke list for JSON serialization (Java compatibility)
     */
    fun getStrokesAsInkStrokes(): List<InkCanvasView.InkStroke> {
        return finishedStrokes.map { stroke ->
            // Extract points from the stroke for JSON serialization
            val points = mutableListOf<PointF>()
            val inputs = stroke.inputs
            for (i in 0 until inputs.size) {
                val input = inputs.get(i)
                points.add(PointF(input.x, input.y))
            }

            // Get brush color
            val brushColor = Color.toArgb(stroke.brush.colorLong)

            InkCanvasView.InkStroke(pageIndex, brushColor, stroke.brush.size).apply {
                for (point in points) {
                    this.points.add(point)
                }
                // Rebuild path from points
                if (this.points.isNotEmpty()) {
                    path.moveTo(this.points[0].x, this.points[0].y)
                    for (i in 1 until this.points.size) {
                        path.lineTo(this.points[i].x, this.points[i].y)
                    }
                }
            }
        }
    }

    /**
     * Load strokes from InkStroke list (Java compatibility)
     * Note: We store InkStrokes but render them with the canvas path.
     * Full AndroidX Stroke reconstruction requires MutableStrokeInputBatch which is complex.
     */
    fun loadStrokesFromInkStrokes(inkStrokes: List<InkCanvasView.InkStroke>) {
        // For loading, we'll keep the data but not convert to full AndroidX Strokes
        // The strokes will be stored but rendered via InkCanvasView compatibility
        finishedStrokes.clear()
        undoStack.clear()
        finishedStrokesView.setLegacyStrokes(inkStrokes)
        finishedStrokesView.invalidate()
    }

    /**
     * Check if there are any strokes
     */
    fun hasStrokes(): Boolean = finishedStrokes.isNotEmpty() || finishedStrokesView.hasLegacyStrokes()

    /**
     * Clear all strokes
     */
    fun clear() {
        undoStack.addAll(finishedStrokes)
        finishedStrokes.clear()
        finishedStrokesView.clearLegacyStrokes()
        finishedStrokesView.invalidate()
        onInkChangeListener?.invoke()
    }

    /**
     * Undo last stroke
     */
    fun undo() {
        if (finishedStrokes.isNotEmpty()) {
            val lastStroke = finishedStrokes.removeAt(finishedStrokes.size - 1)
            undoStack.add(lastStroke)
            finishedStrokesView.invalidate()
            onInkChangeListener?.invoke()
        }
    }

    /**
     * Redo last undone stroke
     */
    fun redo() {
        if (undoStack.isNotEmpty()) {
            val stroke = undoStack.removeAt(undoStack.size - 1)
            finishedStrokes.add(stroke)
            finishedStrokesView.invalidate()
            onInkChangeListener?.invoke()
        }
    }

    /**
     * Check if redo is available
     */
    fun canRedo(): Boolean = undoStack.isNotEmpty()

    /**
     * Check if undo is available
     */
    fun canUndo(): Boolean = finishedStrokes.isNotEmpty()

    /**
     * Inner view for rendering finished strokes
     */
    private inner class FinishedStrokesView(context: Context) : View(context) {

        private val identityMatrix = Matrix()
        private var legacyStrokes: List<InkCanvasView.InkStroke> = emptyList()

        init {
            setWillNotDraw(false)
        }

        fun setLegacyStrokes(strokes: List<InkCanvasView.InkStroke>) {
            legacyStrokes = strokes.toList()
        }

        fun clearLegacyStrokes() {
            legacyStrokes = emptyList()
        }

        fun hasLegacyStrokes(): Boolean = legacyStrokes.isNotEmpty()

        fun setStrokes(strokes: List<Stroke>) {
            // This method triggers a re-render of the finished strokes
            // The finishedStrokes list is already updated by the caller
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)

            // Draw AndroidX Ink strokes
            for (stroke in finishedStrokes) {
                canvasStrokeRenderer.draw(
                    stroke = stroke,
                    canvas = canvas,
                    strokeToScreenTransform = identityMatrix
                )
            }

            // Draw legacy strokes (loaded from JSON)
            for (inkStroke in legacyStrokes) {
                val paint = android.graphics.Paint().apply {
                    color = inkStroke.color
                    strokeWidth = inkStroke.strokeWidth
                    style = android.graphics.Paint.Style.STROKE
                    strokeCap = android.graphics.Paint.Cap.ROUND
                    strokeJoin = android.graphics.Paint.Join.ROUND
                    isAntiAlias = true
                }
                canvas.drawPath(inkStroke.path, paint)
            }
        }
    }
}
