package com.capacitor.pdfannotator;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ProgressBar;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * PDF page adapter with AndroidX Ink API annotation support.
 * Uses native Android PdfRenderer for high-quality PDF display
 * and AndroidX Ink for low-latency stylus rendering.
 */
public class PdfPagerAdapter extends RecyclerView.Adapter<PdfPagerAdapter.PageViewHolder> {

    private static final String TAG = "PdfPagerAdapter";
    private static final float RENDER_SCALE = 2.5f;

    private final Context context;
    private final PdfRenderer pdfRenderer;
    private final ParcelFileDescriptor fileDescriptor;
    private final int pageCount;
    private final boolean enableInk;
    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private final Map<Integer, Bitmap> bitmapCache = new HashMap<>();
    private final Map<Integer, AndroidXInkView> inkCanvasMap = new HashMap<>();
    private final Map<Integer, ZoomableFrameLayout> zoomContainerMap = new HashMap<>();

    private int inkColor;
    private float inkWidth;
    private int brushType = AndroidXInkView.BRUSH_PRESSURE_PEN;
    private boolean drawingEnabled = false;
    private boolean eraserMode = false;
    private InkCanvasView.OnInkChangeListener onInkChangeListener;
    private AndroidXInkView.OnDrawingStateListener onDrawingStateListener;
    private ZoomableFrameLayout.OnGestureStateListener onGestureStateListener;

    public PdfPagerAdapter(Context context, File pdfFile, boolean enableInk) throws IOException {
        this.context = context;
        this.fileDescriptor = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY);
        this.pdfRenderer = new PdfRenderer(fileDescriptor);
        this.pageCount = pdfRenderer.getPageCount();
        this.enableInk = enableInk;
    }

    public void setInkColor(int color) {
        this.inkColor = color;
        for (AndroidXInkView canvas : inkCanvasMap.values()) {
            canvas.setInkColor(color);
        }
    }

    public void setInkWidth(float width) {
        this.inkWidth = width;
        for (AndroidXInkView canvas : inkCanvasMap.values()) {
            canvas.setStrokeWidth(width);
        }
    }

    public void setDrawingEnabled(boolean enabled) {
        this.drawingEnabled = enabled;
        for (AndroidXInkView canvas : inkCanvasMap.values()) {
            canvas.setDrawingEnabled(enabled);
        }
    }

    public void setBrushType(int type) {
        this.brushType = type;
        for (AndroidXInkView canvas : inkCanvasMap.values()) {
            canvas.setBrushTypeById(type);
        }
    }

    public int getBrushType() {
        return brushType;
    }

    public void setEraserMode(boolean enabled) {
        this.eraserMode = enabled;
        for (AndroidXInkView canvas : inkCanvasMap.values()) {
            canvas.setEraserMode(enabled);
        }
    }

    public boolean isEraserMode() {
        return eraserMode;
    }

    /**
     * Reset stylus eraser state on all ink canvases.
     * Called when user manually changes modes to prevent auto-eraser interference.
     */
    public void resetStylusEraserState() {
        for (AndroidXInkView canvas : inkCanvasMap.values()) {
            canvas.resetStylusEraserState();
        }
    }

    public void setOnInkChangeListener(InkCanvasView.OnInkChangeListener listener) {
        this.onInkChangeListener = listener;
    }

    public void setOnDrawingStateListener(AndroidXInkView.OnDrawingStateListener listener) {
        this.onDrawingStateListener = listener;
        for (AndroidXInkView canvas : inkCanvasMap.values()) {
            canvas.setOnDrawingStateListener(listener);
        }
    }

    public void setOnGestureStateListener(ZoomableFrameLayout.OnGestureStateListener listener) {
        this.onGestureStateListener = listener;
        for (ZoomableFrameLayout container : zoomContainerMap.values()) {
            container.setOnGestureStateListener(listener);
        }
    }

    public List<InkCanvasView.InkStroke> getAllStrokes() {
        List<InkCanvasView.InkStroke> allStrokes = new ArrayList<>();
        for (AndroidXInkView canvas : inkCanvasMap.values()) {
            allStrokes.addAll(canvas.getStrokesAsInkStrokes());
        }
        return allStrokes;
    }

    /**
     * Get strokes organized by page index for saving.
     */
    public Map<Integer, List<InkCanvasView.InkStroke>> getStrokesByPage() {
        Map<Integer, List<InkCanvasView.InkStroke>> strokesByPage = new HashMap<>();
        for (Map.Entry<Integer, AndroidXInkView> entry : inkCanvasMap.entrySet()) {
            List<InkCanvasView.InkStroke> strokes = entry.getValue().getStrokesAsInkStrokes();
            if (!strokes.isEmpty()) {
                strokesByPage.put(entry.getKey(), strokes);
            }
        }
        return strokesByPage;
    }

    /**
     * Load strokes for pages from saved data.
     */
    public void loadStrokes(Map<Integer, List<InkCanvasView.InkStroke>> strokesByPage) {
        for (Map.Entry<Integer, List<InkCanvasView.InkStroke>> entry : strokesByPage.entrySet()) {
            int pageIndex = entry.getKey();
            List<InkCanvasView.InkStroke> strokes = entry.getValue();

            // Get or create canvas for this page
            AndroidXInkView canvas = inkCanvasMap.get(pageIndex);
            if (canvas == null) {
                canvas = new AndroidXInkView(context);
                canvas.setPageIndex(pageIndex);
                canvas.setInkColor(inkColor);
                canvas.setStrokeWidth(inkWidth);
                canvas.setBrushTypeById(brushType);
                canvas.setDrawingEnabled(drawingEnabled);
                canvas.setOnInkChangeListenerJava(onInkChangeListener);
                inkCanvasMap.put(pageIndex, canvas);
            }

            // Load strokes into canvas
            canvas.loadStrokesFromInkStrokes(strokes);
        }
    }

    /**
     * Check if any page has strokes.
     */
    public boolean hasAnyStrokes() {
        for (AndroidXInkView canvas : inkCanvasMap.values()) {
            if (canvas.hasStrokes()) {
                return true;
            }
        }
        return false;
    }

    public void clearPage(int page) {
        AndroidXInkView canvas = inkCanvasMap.get(page);
        if (canvas != null) {
            canvas.clear();
        }
    }

    /**
     * Clear all strokes from all pages.
     */
    public void clearAllPages() {
        for (AndroidXInkView canvas : inkCanvasMap.values()) {
            canvas.clear();
        }
    }

    /**
     * Undo the last stroke on a specific page.
     */
    public void undoPage(int page) {
        AndroidXInkView canvas = inkCanvasMap.get(page);
        if (canvas != null) {
            canvas.undo();
        }
    }

    /**
     * Redo the last undone stroke on a specific page.
     */
    public void redoPage(int page) {
        AndroidXInkView canvas = inkCanvasMap.get(page);
        if (canvas != null) {
            canvas.redo();
        }
    }

    /**
     * Check if undo is available on a specific page.
     */
    public boolean canUndoPage(int page) {
        AndroidXInkView canvas = inkCanvasMap.get(page);
        return canvas != null && canvas.canUndo();
    }

    /**
     * Check if redo is available on a specific page.
     */
    public boolean canRedoPage(int page) {
        AndroidXInkView canvas = inkCanvasMap.get(page);
        return canvas != null && canvas.canRedo();
    }

    @NonNull
    @Override
    public PageViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_pdf_page, parent, false);
        return new PageViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull PageViewHolder holder, int position) {
        holder.progressBar.setVisibility(View.VISIBLE);
        holder.imageView.setImageBitmap(null);

        // Store zoom container reference
        zoomContainerMap.put(position, holder.zoomContainer);

        // Set gesture state listener for toolbox hiding during pan/zoom
        if (onGestureStateListener != null) {
            holder.zoomContainer.setOnGestureStateListener(onGestureStateListener);
        }

        // Reset zoom when page is bound
        holder.zoomContainer.resetZoom();

        // Check cache first
        Bitmap cached = bitmapCache.get(position);
        if (cached != null && !cached.isRecycled()) {
            holder.imageView.setImageBitmap(cached);
            holder.progressBar.setVisibility(View.GONE);
        } else {
            // Render page in background
            renderPage(position, holder);
        }

        // Setup ink canvas using AndroidX Ink API for low-latency stylus input
        if (enableInk) {
            AndroidXInkView inkCanvas = inkCanvasMap.get(position);
            if (inkCanvas == null) {
                inkCanvas = new AndroidXInkView(context);
                inkCanvas.setPageIndex(position);
                inkCanvas.setInkColor(inkColor);
                inkCanvas.setStrokeWidth(inkWidth);
                inkCanvas.setBrushTypeById(brushType);
                inkCanvas.setDrawingEnabled(drawingEnabled);
                inkCanvas.setEraserMode(eraserMode);
                inkCanvas.setOnInkChangeListenerJava(onInkChangeListener);
                inkCanvas.setOnDrawingStateListener(onDrawingStateListener);
                inkCanvasMap.put(position, inkCanvas);
            }

            // Remove from previous parent if exists
            if (inkCanvas.getParent() != null) {
                ((ViewGroup) inkCanvas.getParent()).removeView(inkCanvas);
            }

            holder.inkContainer.removeAllViews();
            holder.inkContainer.addView(inkCanvas, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
            ));
        }
    }

    private void renderPage(int position, PageViewHolder holder) {
        executor.execute(() -> {
            try {
                synchronized (pdfRenderer) {
                    PdfRenderer.Page page = pdfRenderer.openPage(position);

                    // Calculate bitmap size with scale
                    int width = (int) (page.getWidth() * RENDER_SCALE);
                    int height = (int) (page.getHeight() * RENDER_SCALE);

                    Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                    // Fill with white background
                    bitmap.eraseColor(android.graphics.Color.WHITE);

                    // Render the page
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                    page.close();

                    bitmapCache.put(position, bitmap);

                    holder.imageView.post(() -> {
                        holder.imageView.setImageBitmap(bitmap);
                        holder.progressBar.setVisibility(View.GONE);
                    });
                }
            } catch (Exception e) {
                Log.e(TAG, "Error rendering page " + position, e);
                holder.imageView.post(() -> {
                    holder.progressBar.setVisibility(View.GONE);
                });
            }
        });
    }

    @Override
    public int getItemCount() {
        return pageCount;
    }

    @Override
    public void onViewRecycled(@NonNull PageViewHolder holder) {
        super.onViewRecycled(holder);
        // Don't clear ink canvas - keep strokes
    }

    public int getPageCount() {
        return pageCount;
    }

    public void cleanup() {
        executor.shutdown();
        for (Bitmap bitmap : bitmapCache.values()) {
            if (bitmap != null && !bitmap.isRecycled()) {
                bitmap.recycle();
            }
        }
        bitmapCache.clear();

        // Close native PDF renderer
        if (pdfRenderer != null) {
            pdfRenderer.close();
        }
        if (fileDescriptor != null) {
            try {
                fileDescriptor.close();
            } catch (IOException e) {
                Log.e(TAG, "Error closing file descriptor", e);
            }
        }
    }

    /**
     * Reset zoom on all pages
     */
    public void resetAllZoom() {
        for (ZoomableFrameLayout container : zoomContainerMap.values()) {
            container.resetZoom();
        }
    }

    /**
     * Reset zoom on a specific page
     */
    public void resetZoom(int page) {
        ZoomableFrameLayout container = zoomContainerMap.get(page);
        if (container != null) {
            container.resetZoom();
        }
    }

    static class PageViewHolder extends RecyclerView.ViewHolder {
        ZoomableFrameLayout zoomContainer;
        ImageView imageView;
        FrameLayout inkContainer;
        ProgressBar progressBar;

        PageViewHolder(@NonNull View itemView) {
            super(itemView);
            zoomContainer = (ZoomableFrameLayout) itemView;
            imageView = itemView.findViewById(R.id.pageImage);
            inkContainer = itemView.findViewById(R.id.inkContainer);
            progressBar = itemView.findViewById(R.id.progressBar);
        }
    }
}
