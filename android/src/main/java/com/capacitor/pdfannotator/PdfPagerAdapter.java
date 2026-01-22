package com.capacitor.pdfannotator;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ProgressBar;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.tom_roush.pdfbox.rendering.ImageType;
import com.tom_roush.pdfbox.rendering.PDFRenderer;
import com.tom_roush.pdfbox.rendering.RenderDestination;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PdfPagerAdapter extends RecyclerView.Adapter<PdfPagerAdapter.PageViewHolder> {

    private static final String TAG = "PdfPagerAdapter";
    private static final float RENDER_SCALE = 2.0f;

    private final Context context;
    private final PDFRenderer pdfRenderer;
    private final int pageCount;
    private final boolean enableInk;
    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private final Map<Integer, Bitmap> bitmapCache = new HashMap<>();
    private final Map<Integer, InkCanvasView> inkCanvasMap = new HashMap<>();

    private int inkColor;
    private float inkWidth;
    private boolean drawingEnabled = false;
    private InkCanvasView.OnInkChangeListener onInkChangeListener;

    public PdfPagerAdapter(Context context, PDFRenderer pdfRenderer, int pageCount, boolean enableInk) {
        this.context = context;
        this.pdfRenderer = pdfRenderer;
        this.pageCount = pageCount;
        this.enableInk = enableInk;
    }

    public void setInkColor(int color) {
        this.inkColor = color;
        for (InkCanvasView canvas : inkCanvasMap.values()) {
            canvas.setInkColor(color);
        }
    }

    public void setInkWidth(float width) {
        this.inkWidth = width;
        for (InkCanvasView canvas : inkCanvasMap.values()) {
            canvas.setStrokeWidth(width);
        }
    }

    public void setDrawingEnabled(boolean enabled) {
        this.drawingEnabled = enabled;
        for (InkCanvasView canvas : inkCanvasMap.values()) {
            canvas.setDrawingEnabled(enabled);
        }
    }

    public void setOnInkChangeListener(InkCanvasView.OnInkChangeListener listener) {
        this.onInkChangeListener = listener;
    }

    public List<InkCanvasView.InkStroke> getAllStrokes() {
        List<InkCanvasView.InkStroke> allStrokes = new ArrayList<>();
        for (InkCanvasView canvas : inkCanvasMap.values()) {
            allStrokes.addAll(canvas.getStrokes());
        }
        return allStrokes;
    }

    public void clearPage(int page) {
        InkCanvasView canvas = inkCanvasMap.get(page);
        if (canvas != null) {
            canvas.clear();
        }
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

        // Check cache first
        Bitmap cached = bitmapCache.get(position);
        if (cached != null && !cached.isRecycled()) {
            holder.imageView.setImageBitmap(cached);
            holder.progressBar.setVisibility(View.GONE);
        } else {
            // Render page in background
            renderPage(position, holder);
        }

        // Setup ink canvas
        if (enableInk) {
            InkCanvasView inkCanvas = inkCanvasMap.get(position);
            if (inkCanvas == null) {
                inkCanvas = new InkCanvasView(context, position);
                inkCanvas.setInkColor(inkColor);
                inkCanvas.setStrokeWidth(inkWidth);
                inkCanvas.setDrawingEnabled(drawingEnabled);
                inkCanvas.setOnInkChangeListener(onInkChangeListener);
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
                    pdfRenderer.setDefaultDestination(RenderDestination.VIEW);
                    Bitmap bitmap = pdfRenderer.renderImage(position, RENDER_SCALE, ImageType.RGB);
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

    public void cleanup() {
        executor.shutdown();
        for (Bitmap bitmap : bitmapCache.values()) {
            if (bitmap != null && !bitmap.isRecycled()) {
                bitmap.recycle();
            }
        }
        bitmapCache.clear();
    }

    static class PageViewHolder extends RecyclerView.ViewHolder {
        ImageView imageView;
        FrameLayout inkContainer;
        ProgressBar progressBar;

        PageViewHolder(@NonNull View itemView) {
            super(itemView);
            imageView = itemView.findViewById(R.id.pageImage);
            inkContainer = itemView.findViewById(R.id.inkContainer);
            progressBar = itemView.findViewById(R.id.progressBar);
        }
    }
}
