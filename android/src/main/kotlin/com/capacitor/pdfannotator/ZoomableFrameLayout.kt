package com.capacitor.pdfannotator

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Matrix
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.FrameLayout
import kotlin.math.max
import kotlin.math.min

/**
 * A FrameLayout that supports pinch-to-zoom and two-finger pan.
 * Single finger touches pass through to children for drawing.
 * Two finger gestures are intercepted for zoom/pan.
 */
class ZoomableFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    companion object {
        private const val MIN_SCALE = 1.0f
        private const val MAX_SCALE = 5.0f
    }

    // Current transformation state
    private var scaleFactor = 1.0f
    private var focusX = 0f
    private var focusY = 0f
    private var translateX = 0f
    private var translateY = 0f

    // Touch tracking for pan
    private var lastPanX = 0f
    private var lastPanY = 0f
    private var isPanning = false

    // Gesture tracking to prevent leaking to parent ViewPager2
    private var multiTouchGestureStarted = false
    private var gesturePointerCount = 0

    // Gesture detectors
    private val scaleDetector: ScaleGestureDetector
    private val gestureDetector: GestureDetector

    // Listener for zoom changes
    var onZoomChangeListener: ((scale: Float, translateX: Float, translateY: Float) -> Unit)? = null

    init {
        scaleDetector = ScaleGestureDetector(context, ScaleListener())
        gestureDetector = GestureDetector(context, GestureListener())
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // Track gesture pointer count
        gesturePointerCount = ev.pointerCount

        // Always process gestures first for zoom/pan detection
        scaleDetector.onTouchEvent(ev)
        gestureDetector.onTouchEvent(ev)

        // Track when multi-touch gesture starts
        if (ev.pointerCount >= 2 && !multiTouchGestureStarted) {
            multiTouchGestureStarted = true
            // Request parent to not intercept touch events during our gesture
            parent?.requestDisallowInterceptTouchEvent(true)
        }

        // Once a multi-touch gesture has started, consume ALL touch events
        // until the gesture fully ends (all fingers lifted)
        // This prevents gesture leakage to parent ViewPager2
        if (multiTouchGestureStarted) {
            onTouchEvent(ev)

            // Only end the gesture when all fingers are lifted
            if (ev.actionMasked == MotionEvent.ACTION_UP ||
                ev.actionMasked == MotionEvent.ACTION_CANCEL) {
                multiTouchGestureStarted = false
            }
            return true
        }

        // If panning (from previous multi-touch), continue consuming
        if (isPanning) {
            onTouchEvent(ev)
            return true
        }

        return super.dispatchTouchEvent(ev)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                isPanning = false
                // Don't reset multiTouchGestureStarted here - it should persist
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // Two or more fingers - intercept for pan/zoom
                if (ev.pointerCount >= 2) {
                    multiTouchGestureStarted = true
                    return true
                }
            }
        }

        // If a multi-touch gesture was started, continue intercepting until fully released
        if (multiTouchGestureStarted) {
            return true
        }

        // If already panning/zooming, continue intercepting
        if (isPanning || ev.pointerCount >= 2) {
            return true
        }

        // Single touch passes through to children (for drawing)
        return false
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Note: Gesture detectors are also called in dispatchTouchEvent
        // to ensure proper handling when drawing is disabled
        gestureDetector.onTouchEvent(event)
        scaleDetector.onTouchEvent(event)

        val pointerCount = event.pointerCount

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastPanX = event.x
                lastPanY = event.y
                isPanning = false
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (pointerCount >= 2) {
                    // Calculate midpoint for pan reference
                    lastPanX = (event.getX(0) + event.getX(1)) / 2
                    lastPanY = (event.getY(0) + event.getY(1)) / 2
                    isPanning = true
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (pointerCount >= 2 && !scaleDetector.isInProgress) {
                    // Two-finger pan (when not actively scaling)
                    val midX = (event.getX(0) + event.getX(1)) / 2
                    val midY = (event.getY(0) + event.getY(1)) / 2

                    val dx = midX - lastPanX
                    val dy = midY - lastPanY

                    translateX += dx
                    translateY += dy

                    lastPanX = midX
                    lastPanY = midY
                    isPanning = true

                    clampTranslation()
                    applyTransformation()
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isPanning = false
                multiTouchGestureStarted = false
            }

            MotionEvent.ACTION_POINTER_UP -> {
                // Update pan reference point when a finger is lifted
                val remainingIndex = if (event.actionIndex == 0) 1 else 0
                if (remainingIndex < event.pointerCount) {
                    lastPanX = event.getX(remainingIndex)
                    lastPanY = event.getY(remainingIndex)
                }

                // Only stop panning when down to one finger
                // But keep multiTouchGestureStarted true until fully released
                if (pointerCount <= 2) {
                    isPanning = false
                }
            }
        }

        return true
    }

    private fun clampTranslation() {
        if (scaleFactor <= 1.0f) {
            // Reset to center when at or below minimum scale
            translateX = 0f
            translateY = 0f
        } else {
            // Calculate max translation based on scale and view size
            val contentWidth = width.toFloat()
            val contentHeight = height.toFloat()

            val scaledWidth = contentWidth * scaleFactor
            val scaledHeight = contentHeight * scaleFactor

            val maxTranslateX = (scaledWidth - contentWidth) / 2
            val maxTranslateY = (scaledHeight - contentHeight) / 2

            translateX = translateX.coerceIn(-maxTranslateX, maxTranslateX)
            translateY = translateY.coerceIn(-maxTranslateY, maxTranslateY)
        }
    }

    private fun applyTransformation() {
        // Apply scale and translation to the content container (first child)
        if (childCount > 0) {
            val contentChild = getChildAt(0)

            // Set pivot to center of view for proper scaling
            contentChild.pivotX = width / 2f
            contentChild.pivotY = height / 2f

            // Apply scale
            contentChild.scaleX = scaleFactor
            contentChild.scaleY = scaleFactor

            // Apply translation
            contentChild.translationX = translateX
            contentChild.translationY = translateY
        }

        onZoomChangeListener?.invoke(scaleFactor, translateX, translateY)
    }

    /**
     * Reset zoom and pan to default state
     */
    fun resetZoom() {
        scaleFactor = 1.0f
        translateX = 0f
        translateY = 0f
        focusX = 0f
        focusY = 0f
        isPanning = false
        multiTouchGestureStarted = false
        applyTransformation()
    }

    /**
     * Get current scale factor
     */
    fun getScaleFactor(): Float = scaleFactor

    /**
     * Check if currently zoomed in
     */
    fun isZoomedIn(): Boolean = scaleFactor > 1.0f

    /**
     * Double-tap to zoom toggle
     */
    fun toggleZoom(tapX: Float, tapY: Float) {
        if (scaleFactor > 1.1f) {
            // Zoom out to 1x
            scaleFactor = 1.0f
            translateX = 0f
            translateY = 0f
        } else {
            // Zoom in to 2x centered on tap point
            val targetScale = 2.0f

            // Calculate the offset from center
            val centerX = width / 2f
            val centerY = height / 2f

            // Translate so the tap point stays in place after zooming
            translateX = (centerX - tapX) * (targetScale - 1)
            translateY = (centerY - tapY) * (targetScale - 1)

            scaleFactor = targetScale
            clampTranslation()
        }
        applyTransformation()
    }

    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {

        private var initialFocusX = 0f
        private var initialFocusY = 0f
        private var initialTranslateX = 0f
        private var initialTranslateY = 0f
        private var initialScale = 1f

        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            initialFocusX = detector.focusX
            initialFocusY = detector.focusY
            initialTranslateX = translateX
            initialTranslateY = translateY
            initialScale = scaleFactor
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val previousScale = scaleFactor
            scaleFactor *= detector.scaleFactor

            // Clamp scale factor
            scaleFactor = scaleFactor.coerceIn(MIN_SCALE, MAX_SCALE)

            if (scaleFactor != previousScale) {
                // Get current focus point
                val focusX = detector.focusX
                val focusY = detector.focusY

                // Calculate how much the scale changed
                val scaleChange = scaleFactor / previousScale

                // Adjust translation to keep the focus point stationary
                // The focus point in content coordinates should stay at the same screen position
                val centerX = width / 2f
                val centerY = height / 2f

                // Vector from center to focus point
                val focusOffsetX = focusX - centerX
                val focusOffsetY = focusY - centerY

                // Adjust translation
                translateX = (translateX - focusOffsetX) * scaleChange + focusOffsetX
                translateY = (translateY - focusOffsetY) * scaleChange + focusOffsetY

                clampTranslation()
                applyTransformation()
            }

            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            // Snap to 1.0 if close enough
            if (scaleFactor < 1.05f) {
                scaleFactor = 1.0f
                translateX = 0f
                translateY = 0f
                applyTransformation()
            }
        }
    }

    private inner class GestureListener : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            toggleZoom(e.x, e.y)
            return true
        }
    }
}
