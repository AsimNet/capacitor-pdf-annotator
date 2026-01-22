package com.capacitor.pdfannotator;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Custom view for drawing ink annotations on PDF pages.
 * Supports stylus input with pressure sensitivity.
 */
public class InkCanvasView extends View {

    private static final String TAG = "InkCanvasView";

    // Drawing state
    private final List<InkStroke> strokes = new ArrayList<>();
    private final List<InkStroke> undoStack = new ArrayList<>();
    private InkStroke currentStroke = null;
    private Path currentPath = new Path();
    private Paint currentPaint;

    // Settings
    private int inkColor = Color.BLACK;
    private float strokeWidth = 5f;
    private boolean drawingEnabled = false;

    // Page info
    private int pageIndex;

    // Listener
    private OnInkChangeListener onInkChangeListener;

    public interface OnInkChangeListener {
        void onInkChanged();
    }

    public static class InkStroke {
        public int pageIndex;
        public int color;
        public float strokeWidth;
        public List<PointF> points;
        public Path path;

        public InkStroke(int pageIndex, int color, float strokeWidth) {
            this.pageIndex = pageIndex;
            this.color = color;
            this.strokeWidth = strokeWidth;
            this.points = new ArrayList<>();
            this.path = new Path();
        }
    }

    public InkCanvasView(Context context, int pageIndex) {
        super(context);
        this.pageIndex = pageIndex;
        init();
    }

    public InkCanvasView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public InkCanvasView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        currentPaint = createPaint(inkColor, strokeWidth);
        setLayerType(LAYER_TYPE_HARDWARE, null);
    }

    private Paint createPaint(int color, float width) {
        Paint paint = new Paint();
        paint.setColor(color);
        paint.setStrokeWidth(width);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setAntiAlias(true);
        paint.setDither(true);
        return paint;
    }

    public void setInkColor(int color) {
        this.inkColor = color;
        currentPaint = createPaint(color, strokeWidth);
    }

    public void setStrokeWidth(float width) {
        this.strokeWidth = width;
        currentPaint = createPaint(inkColor, width);
    }

    public void setDrawingEnabled(boolean enabled) {
        this.drawingEnabled = enabled;
    }

    public void setOnInkChangeListener(OnInkChangeListener listener) {
        this.onInkChangeListener = listener;
    }

    public List<InkStroke> getStrokes() {
        return new ArrayList<>(strokes);
    }

    public void clear() {
        undoStack.addAll(strokes); // Allow redo after clear
        strokes.clear();
        invalidate();
        notifyInkChanged();
    }

    /**
     * Load strokes from saved data.
     */
    public void loadStrokes(List<InkStroke> savedStrokes) {
        strokes.clear();
        strokes.addAll(savedStrokes);
        invalidate();
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);

        // Draw completed strokes
        for (InkStroke stroke : strokes) {
            Paint paint = createPaint(stroke.color, stroke.strokeWidth);
            canvas.drawPath(stroke.path, paint);
        }

        // Draw current stroke
        if (currentStroke != null) {
            canvas.drawPath(currentPath, currentPaint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!drawingEnabled) {
            return false;
        }

        float x = event.getX();
        float y = event.getY();

        // Handle pressure for stylus
        float pressure = event.getPressure();
        float adjustedWidth = strokeWidth * Math.max(0.5f, Math.min(pressure * 1.5f, 2f));

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                startStroke(x, y, adjustedWidth);
                return true;

            case MotionEvent.ACTION_MOVE:
                continueStroke(x, y, event);
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                endStroke();
                return true;
        }

        return super.onTouchEvent(event);
    }

    private void startStroke(float x, float y, float width) {
        currentStroke = new InkStroke(pageIndex, inkColor, width);
        currentStroke.points.add(new PointF(x, y));

        currentPath.reset();
        currentPath.moveTo(x, y);

        invalidate();
    }

    private void continueStroke(float x, float y, MotionEvent event) {
        if (currentStroke == null) return;

        // Get historical points for smoother lines
        int historySize = event.getHistorySize();
        for (int i = 0; i < historySize; i++) {
            float hx = event.getHistoricalX(i);
            float hy = event.getHistoricalY(i);
            currentStroke.points.add(new PointF(hx, hy));
            currentPath.lineTo(hx, hy);
        }

        currentStroke.points.add(new PointF(x, y));
        currentPath.lineTo(x, y);

        invalidate();
    }

    private void endStroke() {
        if (currentStroke != null && currentStroke.points.size() > 1) {
            // Copy path to stroke
            currentStroke.path = new Path(currentPath);
            strokes.add(currentStroke);
            undoStack.clear(); // Clear redo stack when new stroke is added
            notifyInkChanged();
        }

        currentStroke = null;
        currentPath.reset();
        invalidate();
    }

    private void notifyInkChanged() {
        if (onInkChangeListener != null) {
            onInkChangeListener.onInkChanged();
        }
    }

    /**
     * Undo the last stroke
     */
    public void undo() {
        if (!strokes.isEmpty()) {
            InkStroke lastStroke = strokes.remove(strokes.size() - 1);
            undoStack.add(lastStroke);
            invalidate();
            notifyInkChanged();
        }
    }

    /**
     * Redo the last undone stroke
     */
    public void redo() {
        if (!undoStack.isEmpty()) {
            InkStroke stroke = undoStack.remove(undoStack.size() - 1);
            strokes.add(stroke);
            invalidate();
            notifyInkChanged();
        }
    }

    /**
     * Check if undo is available
     */
    public boolean canUndo() {
        return !strokes.isEmpty();
    }

    /**
     * Check if redo is available
     */
    public boolean canRedo() {
        return !undoStack.isEmpty();
    }

    /**
     * Check if there are any strokes
     */
    public boolean hasStrokes() {
        return !strokes.isEmpty();
    }
}
