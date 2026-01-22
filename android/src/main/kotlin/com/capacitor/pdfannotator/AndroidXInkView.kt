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
    private val strokeBrushTypes = mutableMapOf<Stroke, Int>() // Track brush type for each stroke

    // Action-based undo/redo system (supports both drawing and erasing)
    sealed class UndoableAction {
        data class AddStroke(val stroke: Stroke, val brushType: Int) : UndoableAction()
        data class AddLegacyStroke(val stroke: InkCanvasView.InkStroke) : UndoableAction()
        data class EraseStrokes(
            val erasedStrokes: List<Stroke>,
            val erasedBrushTypes: Map<Stroke, Int>,
            val erasedLegacyStrokes: List<InkCanvasView.InkStroke>
        ) : UndoableAction()
    }

    private val undoStack = mutableListOf<UndoableAction>()
    private val redoStack = mutableListOf<UndoableAction>()

    // Track strokes erased in current erase gesture (to batch them into one undo action)
    private var currentEraseAction: MutableList<Stroke> = mutableListOf()
    private var currentEraseBrushTypes: MutableMap<Stroke, Int> = mutableMapOf()
    private var currentEraseLegacyStrokes: MutableList<InkCanvasView.InkStroke> = mutableListOf()

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
            val currentBrushTypeId = brushType.id
            strokeBrushTypes[stroke] = currentBrushTypeId
            // Add to undo stack and clear redo stack
            undoStack.add(UndoableAction.AddStroke(stroke, currentBrushTypeId))
            redoStack.clear()
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
     * Reset stylus eraser state when user manually changes modes.
     * This prevents the auto-eraser feature from interfering with manual mode changes.
     */
    fun resetStylusEraserState() {
        wasEraserModeBeforeStylus = null
        isUsingEraserTip = false
        Log.d(TAG, "Stylus eraser state reset")
    }

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
            // Track erased strokes for undo
            for (stroke in strokesToRemove) {
                currentEraseAction.add(stroke)
                strokeBrushTypes[stroke]?.let { currentEraseBrushTypes[stroke] = it }
                strokeBrushTypes.remove(stroke)
            }
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
            // Track erased legacy strokes for undo
            currentEraseLegacyStrokes.addAll(legacyToRemove)
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
                // Start a new erase session for undo
                startEraseSession()
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
                    // Finalize erase session before switching
                    finalizeEraseSession()
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
                    // Finalize erase session before switching
                    finalizeEraseSession()
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
                // Finalize erase session for undo
                finalizeEraseSession()
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

    private fun startEraseSession() {
        currentEraseAction.clear()
        currentEraseBrushTypes.clear()
        currentEraseLegacyStrokes.clear()
    }

    private fun finalizeEraseSession() {
        if (currentEraseAction.isNotEmpty() || currentEraseLegacyStrokes.isNotEmpty()) {
            // Add the erase action to undo stack
            undoStack.add(UndoableAction.EraseStrokes(
                erasedStrokes = currentEraseAction.toList(),
                erasedBrushTypes = currentEraseBrushTypes.toMap(),
                erasedLegacyStrokes = currentEraseLegacyStrokes.toList()
            ))
            redoStack.clear()
            Log.d(TAG, "Finalized erase session: ${currentEraseAction.size} AndroidX strokes, ${currentEraseLegacyStrokes.size} legacy strokes")
        }
        currentEraseAction.clear()
        currentEraseBrushTypes.clear()
        currentEraseLegacyStrokes.clear()
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

            // Get brush type for this stroke (default to pressure pen if not tracked)
            val strokeBrushType = strokeBrushTypes[stroke] ?: BRUSH_PRESSURE_PEN

            InkCanvasView.InkStroke(pageIndex, brushColor, stroke.brush.size, strokeBrushType).apply {
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
        strokeBrushTypes.clear()
        undoStack.clear()
        redoStack.clear()
        finishedStrokesView.setLegacyStrokes(inkStrokes)
        finishedStrokesView.invalidate()
    }

    /**
     * Check if there are any strokes
     */
    fun hasStrokes(): Boolean = finishedStrokes.isNotEmpty() || finishedStrokesView.hasLegacyStrokes()

    /**
     * Clear all strokes (creates a single undo action for all erased strokes)
     */
    fun clear() {
        if (!hasStrokes()) return

        // Create an erase action containing all strokes
        val erasedStrokes = finishedStrokes.toList()
        val erasedBrushTypes = strokeBrushTypes.toMap()
        val erasedLegacyStrokes = finishedStrokesView.getLegacyStrokes().toList()

        // Add to undo stack
        undoStack.add(UndoableAction.EraseStrokes(erasedStrokes, erasedBrushTypes, erasedLegacyStrokes))
        redoStack.clear()

        // Clear the strokes
        finishedStrokes.clear()
        strokeBrushTypes.clear()
        finishedStrokesView.clearLegacyStrokes()
        finishedStrokesView.invalidate()
        onInkChangeListener?.invoke()
    }

    /**
     * Undo last action (draw or erase)
     */
    fun undo() {
        if (undoStack.isEmpty()) return

        val action = undoStack.removeAt(undoStack.size - 1)
        when (action) {
            is UndoableAction.AddStroke -> {
                // Undo drawing: remove the stroke
                finishedStrokes.remove(action.stroke)
                strokeBrushTypes.remove(action.stroke)
                redoStack.add(action)
            }
            is UndoableAction.AddLegacyStroke -> {
                // Undo legacy stroke add: remove it
                finishedStrokesView.removeLegacyStrokes(listOf(action.stroke))
                redoStack.add(action)
            }
            is UndoableAction.EraseStrokes -> {
                // Undo erasing: restore the erased strokes
                finishedStrokes.addAll(action.erasedStrokes)
                strokeBrushTypes.putAll(action.erasedBrushTypes)
                finishedStrokesView.addLegacyStrokes(action.erasedLegacyStrokes)
                redoStack.add(action)
            }
        }
        finishedStrokesView.invalidate()
        onInkChangeListener?.invoke()
        Log.d(TAG, "Undo: ${action::class.simpleName}")
    }

    /**
     * Redo last undone action
     */
    fun redo() {
        if (redoStack.isEmpty()) return

        val action = redoStack.removeAt(redoStack.size - 1)
        when (action) {
            is UndoableAction.AddStroke -> {
                // Redo drawing: add the stroke back
                finishedStrokes.add(action.stroke)
                strokeBrushTypes[action.stroke] = action.brushType
                undoStack.add(action)
            }
            is UndoableAction.AddLegacyStroke -> {
                // Redo legacy stroke add: add it back
                finishedStrokesView.addLegacyStrokes(listOf(action.stroke))
                undoStack.add(action)
            }
            is UndoableAction.EraseStrokes -> {
                // Redo erasing: remove the strokes again
                finishedStrokes.removeAll(action.erasedStrokes.toSet())
                for (stroke in action.erasedStrokes) {
                    strokeBrushTypes.remove(stroke)
                }
                finishedStrokesView.removeLegacyStrokes(action.erasedLegacyStrokes)
                undoStack.add(action)
            }
        }
        finishedStrokesView.invalidate()
        onInkChangeListener?.invoke()
        Log.d(TAG, "Redo: ${action::class.simpleName}")
    }

    /**
     * Check if redo is available
     */
    fun canRedo(): Boolean = redoStack.isNotEmpty()

    /**
     * Check if undo is available
     */
    fun canUndo(): Boolean = undoStack.isNotEmpty()

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

        fun addLegacyStrokes(strokes: List<InkCanvasView.InkStroke>) {
            legacyStrokes.addAll(strokes)
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

            // Draw legacy strokes (loaded from JSON) with proper layer-based rendering for highlighter
            for (inkStroke in legacyStrokes) {
                val isHighlighter = inkStroke.brushType == BRUSH_HIGHLIGHTER ||
                        (inkStroke.brushType == 0 && Color.alpha(inkStroke.color) < 255) // Legacy detection

                val paint = Paint().apply {
                    strokeWidth = inkStroke.strokeWidth
                    style = Paint.Style.STROKE
                    strokeCap = Paint.Cap.ROUND
                    strokeJoin = Paint.Join.ROUND
                    isAntiAlias = true
                }

                if (isHighlighter) {
                    // Use layer-based rendering for highlighter to ensure consistent alpha
                    // This prevents darkening when overlapping or after save/load
                    val alpha = Color.alpha(inkStroke.color)
                    paint.color = Color.argb(255, Color.red(inkStroke.color),
                        Color.green(inkStroke.color), Color.blue(inkStroke.color))

                    // Calculate bounds for the path
                    val bounds = RectF()
                    inkStroke.path.computeBounds(bounds, true)
                    // Expand bounds slightly for stroke width
                    bounds.inset(-inkStroke.strokeWidth, -inkStroke.strokeWidth)

                    // Save layer with alpha - this ensures proper transparency
                    val saveCount = canvas.saveLayerAlpha(bounds, alpha)
                    canvas.drawPath(inkStroke.path, paint)
                    canvas.restoreToCount(saveCount)
                } else {
                    // Regular stroke rendering
                    paint.color = inkStroke.color
                    canvas.drawPath(inkStroke.path, paint)
                }
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
