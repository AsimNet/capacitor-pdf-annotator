package com.capacitor.pdfannotator

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.os.Build
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
import androidx.input.motionprediction.MotionEventPredictor

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

    // Stylus tool type tracking for auto-eraser
    private var wasEraserModeBeforeStylus: Boolean? = null
    private var isUsingEraserTip = false

    // Motion prediction for reduced latency (~20-40ms improvement)
    private var motionEventPredictor: MotionEventPredictor? = null

    // Hover preview
    private var hoverX = -1f
    private var hoverY = -1f
    private var isHovering = false
    private val hoverCursorPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        isAntiAlias = true
    }

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
    private var onDrawingStateListener: OnDrawingStateListener? = null

    /**
     * Interface to listen for drawing start/end events.
     * Useful for hiding UI elements while user is actively drawing.
     */
    interface OnDrawingStateListener {
        fun onDrawingStarted()
        fun onDrawingEnded()
    }

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

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Initialize motion prediction for reduced latency
        motionEventPredictor = MotionEventPredictor.newInstance(this)
        Log.d(TAG, "Motion prediction initialized for reduced stylus latency")
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        motionEventPredictor = null
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

        var removedCount = 0

        // Find intersecting AndroidX strokes using Ink geometry
        val strokesToRemove = finishedStrokes.filter { stroke ->
            stroke.shape.intersects(parallelogram, AffineTransform.IDENTITY)
        }

        if (strokesToRemove.isNotEmpty()) {
            finishedStrokes.removeAll(strokesToRemove.toSet())
            finishedStrokesView.setStrokes(finishedStrokes)
            removedCount += strokesToRemove.size
        }

        // Also erase legacy strokes (loaded from JSON)
        val eraserRect = RectF(
            x - eraserPadding, y - eraserPadding,
            x + eraserPadding, y + eraserPadding
        )

        val legacyToRemove = finishedStrokesView.getLegacyStrokes().filter { stroke ->
            val bounds = RectF()
            stroke.path.computeBounds(bounds, true)
            RectF.intersects(bounds, eraserRect) || pathIntersectsPoint(stroke.path, x, y, eraserPadding)
        }

        if (legacyToRemove.isNotEmpty()) {
            finishedStrokesView.removeLegacyStrokes(legacyToRemove)
            removedCount += legacyToRemove.size
        }

        if (removedCount > 0) {
            finishedStrokesView.invalidate()
            onInkChangeListener?.invoke()
            Log.d(TAG, "Eraser removed $removedCount stroke(s)")
        }

        return true
    }

    /**
     * Check if a path intersects with a point within a given radius
     */
    private fun pathIntersectsPoint(path: android.graphics.Path, x: Float, y: Float, radius: Float): Boolean {
        val bounds = RectF()
        path.computeBounds(bounds, true)

        // Quick bounds check first
        if (!bounds.intersects(x - radius, y - radius, x + radius, y + radius)) {
            return false
        }

        // Sample points along the path using PathMeasure for more accurate hit testing
        val pathMeasure = android.graphics.PathMeasure(path, false)
        val coords = floatArrayOf(0f, 0f)
        val length = pathMeasure.length
        val step = 5f // Sample every 5 pixels

        var distance = 0f
        while (distance <= length) {
            pathMeasure.getPosTan(distance, coords, null)
            val dx = coords[0] - x
            val dy = coords[1] - y
            if (dx * dx + dy * dy <= radius * radius) {
                return true
            }
            distance += step
        }

        return false
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

    /**
     * Set listener for drawing state changes (start/end)
     */
    fun setOnDrawingStateListener(listener: OnDrawingStateListener?) {
        onDrawingStateListener = listener
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!drawingEnabled) {
            return false
        }

        // Enhanced palm rejection for Android 13+ (TIRAMISU)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val isCanceled = (event.flags and MotionEvent.FLAG_CANCELED) != 0
            if (isCanceled) {
                // This pointer was palm-rejected, cancel any strokes from it
                val pointerId = event.getPointerId(event.actionIndex)
                cancelStrokeForPointer(pointerId)
                Log.d(TAG, "Palm rejection: stroke canceled for pointer $pointerId")
                return true
            }
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

                // Detect stylus tool type for auto-eraser
                val toolType = event.getToolType(event.actionIndex)
                when (toolType) {
                    MotionEvent.TOOL_TYPE_ERASER -> {
                        // Auto-enable eraser mode when using stylus eraser tip
                        if (!isUsingEraserTip) {
                            wasEraserModeBeforeStylus = isEraserMode
                            isUsingEraserTip = true
                            isEraserMode = true
                            Log.d(TAG, "Stylus eraser tip detected - auto-enabling eraser mode")
                        }
                        // Handle as eraser touch
                        onDrawingStateListener?.onDrawingStarted()
                        previousErasePoint = null
                        handleErase(event.x, event.y)
                        return true
                    }
                    MotionEvent.TOOL_TYPE_STYLUS -> {
                        // Restore previous eraser mode when using stylus pen tip
                        if (isUsingEraserTip) {
                            wasEraserModeBeforeStylus?.let { previousMode ->
                                isEraserMode = previousMode
                            }
                            isUsingEraserTip = false
                            wasEraserModeBeforeStylus = null
                            Log.d(TAG, "Stylus pen tip detected - restored previous mode")
                        }
                    }
                }

                // Notify drawing started
                onDrawingStateListener?.onDrawingStarted()

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

                // Record event for motion prediction
                motionEventPredictor?.record(event)

                // Get predicted motion for reduced latency
                val predictedEvent = motionEventPredictor?.predict()

                for (i in 0 until event.pointerCount) {
                    val pointerId = event.getPointerId(i)
                    val strokeId = pointerIdToStrokeId[pointerId] ?: continue
                    inProgressStrokesView.addToStroke(
                        event = event,
                        pointerId = pointerId,
                        strokeId = strokeId,
                        prediction = predictedEvent
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

                // Notify drawing ended
                onDrawingStateListener?.onDrawingEnded()
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

                // Notify drawing ended
                onDrawingStateListener?.onDrawingEnded()
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
                // Notify drawing started (erasing counts as active drawing)
                onDrawingStateListener?.onDrawingStarted()
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
                    // Notify drawing ended
                    onDrawingStateListener?.onDrawingEnded()
                    return false
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount > 1) {
                    isMultiTouchActive = true
                    previousErasePoint = null
                    // Allow parent to intercept for pan/zoom
                    parent?.requestDisallowInterceptTouchEvent(false)
                    // Notify drawing ended
                    onDrawingStateListener?.onDrawingEnded()
                    return false
                }
                handleErase(event.x, event.y)
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                previousErasePoint = null
                isMultiTouchActive = false
                // Notify drawing ended
                onDrawingStateListener?.onDrawingEnded()
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

    /**
     * Cancel stroke for a specific pointer (used for palm rejection)
     */
    private fun cancelStrokeForPointer(pointerId: Int) {
        val strokeId = pointerIdToStrokeId.remove(pointerId) ?: return
        try {
            val cancelEvent = MotionEvent.obtain(
                System.currentTimeMillis(),
                System.currentTimeMillis(),
                MotionEvent.ACTION_CANCEL,
                0f, 0f, 0
            )
            inProgressStrokesView.cancelStroke(strokeId, cancelEvent)
            cancelEvent.recycle()
            onDrawingStateListener?.onDrawingEnded()
        } catch (e: Exception) {
            Log.w(TAG, "Error canceling stroke for pointer $pointerId: ${e.message}")
        }
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
     * Handle stylus hover events to show brush preview cursor
     */
    override fun onHoverEvent(event: MotionEvent): Boolean {
        if (!drawingEnabled) {
            if (isHovering) {
                isHovering = false
                hoverX = -1f
                hoverY = -1f
                finishedStrokesView.invalidate()
            }
            return false
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER,
            MotionEvent.ACTION_HOVER_MOVE -> {
                // Show brush cursor at hover position
                hoverX = event.x
                hoverY = event.y
                isHovering = true
                finishedStrokesView.invalidate()
                return true
            }
            MotionEvent.ACTION_HOVER_EXIT -> {
                // Hide brush cursor
                isHovering = false
                hoverX = -1f
                hoverY = -1f
                finishedStrokesView.invalidate()
                return true
            }
        }
        return super.onHoverEvent(event)
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
        private var legacyStrokes: MutableList<InkCanvasView.InkStroke> = mutableListOf()

        init {
            setWillNotDraw(false)
        }

        fun setLegacyStrokes(strokes: List<InkCanvasView.InkStroke>) {
            legacyStrokes = strokes.toMutableList()
        }

        fun clearLegacyStrokes() {
            legacyStrokes.clear()
        }

        fun hasLegacyStrokes(): Boolean = legacyStrokes.isNotEmpty()

        /**
         * Get legacy strokes for eraser hit-testing
         */
        fun getLegacyStrokes(): List<InkCanvasView.InkStroke> = legacyStrokes.toList()

        /**
         * Remove legacy strokes (used by eraser)
         */
        fun removeLegacyStrokes(strokesToRemove: List<InkCanvasView.InkStroke>) {
            legacyStrokes.removeAll(strokesToRemove.toSet())
        }

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

            // Draw legacy strokes (loaded from JSON) with proper blend mode for highlighter
            for (inkStroke in legacyStrokes) {
                val paint = Paint().apply {
                    color = inkStroke.color
                    strokeWidth = inkStroke.strokeWidth
                    style = Paint.Style.STROKE
                    strokeCap = Paint.Cap.ROUND
                    strokeJoin = Paint.Join.ROUND
                    isAntiAlias = true

                    // Detect highlighter by checking alpha < 255
                    // Use MULTIPLY blend mode to prevent multiple passes from becoming opaque
                    if (Color.alpha(inkStroke.color) < 255) {
                        xfermode = PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)
                    }
                }
                canvas.drawPath(inkStroke.path, paint)
            }

            // Draw hover cursor when stylus is hovering
            if (isHovering && hoverX >= 0 && hoverY >= 0) {
                val cursorRadius = if (isEraserMode) eraserPadding else strokeWidth / 2 + 2f
                hoverCursorPaint.color = if (isEraserMode) {
                    Color.argb(128, 255, 0, 0) // Semi-transparent red for eraser
                } else {
                    Color.argb(128, Color.red(inkColor), Color.green(inkColor), Color.blue(inkColor))
                }
                canvas.drawCircle(hoverX, hoverY, cursorRadius, hoverCursorPaint)
            }
        }
    }
}
