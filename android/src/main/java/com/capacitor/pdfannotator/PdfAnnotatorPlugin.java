package com.capacitor.pdfannotator;

import android.content.Intent;
import android.net.Uri;
import androidx.activity.result.ActivityResult;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;
import org.json.JSONException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@CapacitorPlugin(name = "PdfAnnotator")
public class PdfAnnotatorPlugin extends Plugin {

    private static final String TAG = "PdfAnnotatorPlugin";
    private PluginCall savedCall;
    private AnnotationStorage annotationStorage;

    @PluginMethod
    public void openPdf(PluginCall call) {
        String url = call.getString("url");
        if (url == null || url.isEmpty()) {
            call.reject("URL parameter is required");
            return;
        }

        // Get options with defaults
        boolean enableAnnotations = call.getBoolean("enableAnnotations", true);
        boolean enableInk = call.getBoolean("enableInk", true);
        String inkColor = call.getString("inkColor", "#000000");
        float inkWidth = call.getFloat("inkWidth", 2.0f);
        int initialPage = call.getInt("initialPage", 0);
        String title = call.getString("title", "PDF Viewer");
        boolean enableTextSelection = call.getBoolean("enableTextSelection", true);
        boolean enableSearch = call.getBoolean("enableSearch", true);

        // Get theme colors
        String primaryColor = call.getString("primaryColor");
        String toolbarColor = call.getString("toolbarColor");
        String statusBarColor = call.getString("statusBarColor");

        // Get color palette
        JSArray colorPaletteArray = call.getArray("colorPalette");
        String[] colorPalette = null;
        if (colorPaletteArray != null) {
            try {
                int length = colorPaletteArray.length();
                colorPalette = new String[length];
                for (int i = 0; i < length; i++) {
                    colorPalette[i] = colorPaletteArray.getString(i);
                }
            } catch (JSONException e) {
                // Ignore invalid palette, use defaults
                colorPalette = null;
            }
        }

        savedCall = call;

        // Convert URL to Uri
        Uri pdfUri;
        if (url.startsWith("file://")) {
            pdfUri = Uri.parse(url);
        } else if (url.startsWith("/")) {
            pdfUri = Uri.parse("file://" + url);
        } else {
            pdfUri = Uri.parse(url);
        }

        // Create intent for PDF viewer activity
        Intent intent = new Intent(getContext(), PdfViewerActivity.class);
        intent.putExtra(PdfViewerActivity.EXTRA_PDF_URI, pdfUri.toString());
        intent.putExtra(PdfViewerActivity.EXTRA_ENABLE_ANNOTATIONS, enableAnnotations);
        intent.putExtra(PdfViewerActivity.EXTRA_ENABLE_INK, enableInk);
        intent.putExtra(PdfViewerActivity.EXTRA_INK_COLOR, inkColor);
        intent.putExtra(PdfViewerActivity.EXTRA_INK_WIDTH, inkWidth);
        intent.putExtra(PdfViewerActivity.EXTRA_INITIAL_PAGE, initialPage);
        intent.putExtra(PdfViewerActivity.EXTRA_TITLE, title);
        intent.putExtra(PdfViewerActivity.EXTRA_ENABLE_TEXT_SELECTION, enableTextSelection);
        intent.putExtra(PdfViewerActivity.EXTRA_ENABLE_SEARCH, enableSearch);

        // Add theme colors if provided
        if (primaryColor != null) {
            intent.putExtra(PdfViewerActivity.EXTRA_PRIMARY_COLOR, primaryColor);
        }
        if (toolbarColor != null) {
            intent.putExtra(PdfViewerActivity.EXTRA_TOOLBAR_COLOR, toolbarColor);
        }
        if (statusBarColor != null) {
            intent.putExtra(PdfViewerActivity.EXTRA_STATUS_BAR_COLOR, statusBarColor);
        }

        // Add color palette if provided
        if (colorPalette != null) {
            intent.putExtra(PdfViewerActivity.EXTRA_COLOR_PALETTE, colorPalette);
        }

        startActivityForResult(call, intent, "pdfViewerResult");
    }

    @ActivityCallback
    private void pdfViewerResult(PluginCall call, ActivityResult result) {
        if (call == null) {
            return;
        }

        JSObject ret = new JSObject();
        ret.put("dismissed", true);

        if (result.getResultCode() == android.app.Activity.RESULT_OK && result.getData() != null) {
            Intent data = result.getData();
            boolean saved = data.getBooleanExtra(PdfViewerActivity.RESULT_SAVED, false);
            String savedPath = data.getStringExtra(PdfViewerActivity.RESULT_SAVED_PATH);

            ret.put("saved", saved);
            if (savedPath != null) {
                ret.put("savedPath", savedPath);

                // Notify listeners
                JSObject eventData = new JSObject();
                eventData.put("path", savedPath);
                eventData.put("type", "updated");
                notifyListeners("pdfSaved", eventData);
            }
        }

        call.resolve(ret);
    }

    @PluginMethod
    public void isInkSupported(PluginCall call) {
        JSObject ret = new JSObject();

        // Check if device supports stylus
        boolean hasStylusSupport = getContext().getPackageManager()
                .hasSystemFeature("android.hardware.touchscreen.stylus");

        // AndroidX Ink API is available on API 30+
        boolean lowLatencyInk = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R;

        ret.put("supported", true); // Touch drawing is always supported
        ret.put("stylusConnected", hasStylusSupport);
        ret.put("lowLatencyInk", lowLatencyInk);

        call.resolve(ret);
    }

    /**
     * Export annotations for a PDF file as XFDF string.
     * This can be used to upload annotations to a server for cloud sync.
     *
     * @param call Plugin call with "url" parameter (PDF file path)
     */
    @PluginMethod
    public void exportAnnotations(PluginCall call) {
        String url = call.getString("url");
        if (url == null || url.isEmpty()) {
            call.reject("URL parameter is required");
            return;
        }

        // Convert URL to path
        String pdfPath = urlToPath(url);

        // Initialize storage if needed
        if (annotationStorage == null) {
            annotationStorage = new AnnotationStorage(getContext());
        }

        // Load and export annotations
        Map<Integer, List<InkCanvasView.InkStroke>> strokesByPage = annotationStorage.loadAnnotations(pdfPath);
        String xfdf = annotationStorage.exportAnnotationsAsXfdf(pdfPath, strokesByPage);

        if (xfdf == null) {
            call.reject("Failed to export annotations");
            return;
        }

        JSObject ret = new JSObject();
        ret.put("xfdf", xfdf);
        call.resolve(ret);
    }

    /**
     * Import annotations from XFDF string and save to storage.
     * This can be used to download annotations from a server for cloud sync.
     *
     * @param call Plugin call with "url" (PDF file path) and "xfdf" (XFDF content) parameters
     */
    @PluginMethod
    public void importAnnotations(PluginCall call) {
        String url = call.getString("url");
        String xfdf = call.getString("xfdf");

        if (url == null || url.isEmpty()) {
            call.reject("URL parameter is required");
            return;
        }

        if (xfdf == null || xfdf.isEmpty()) {
            call.reject("XFDF parameter is required");
            return;
        }

        // Convert URL to path
        String pdfPath = urlToPath(url);

        // Initialize storage if needed
        if (annotationStorage == null) {
            annotationStorage = new AnnotationStorage(getContext());
        }

        // Import annotations
        boolean success = annotationStorage.importAnnotationsFromXfdf(pdfPath, xfdf);

        if (!success) {
            call.reject("Failed to import annotations");
            return;
        }

        JSObject ret = new JSObject();
        ret.put("success", true);
        call.resolve(ret);
    }

    /**
     * Check if annotations exist for a PDF file.
     *
     * @param call Plugin call with "url" parameter (PDF file path)
     */
    @PluginMethod
    public void hasAnnotations(PluginCall call) {
        String url = call.getString("url");
        if (url == null || url.isEmpty()) {
            call.reject("URL parameter is required");
            return;
        }

        // Convert URL to path
        String pdfPath = urlToPath(url);

        // Initialize storage if needed
        if (annotationStorage == null) {
            annotationStorage = new AnnotationStorage(getContext());
        }

        boolean exists = annotationStorage.hasAnnotations(pdfPath);

        JSObject ret = new JSObject();
        ret.put("hasAnnotations", exists);
        call.resolve(ret);
    }

    /**
     * Delete annotations for a PDF file.
     *
     * @param call Plugin call with "url" parameter (PDF file path)
     */
    @PluginMethod
    public void deleteAnnotations(PluginCall call) {
        String url = call.getString("url");
        if (url == null || url.isEmpty()) {
            call.reject("URL parameter is required");
            return;
        }

        // Convert URL to path
        String pdfPath = urlToPath(url);

        // Initialize storage if needed
        if (annotationStorage == null) {
            annotationStorage = new AnnotationStorage(getContext());
        }

        boolean success = annotationStorage.deleteAnnotations(pdfPath);

        JSObject ret = new JSObject();
        ret.put("success", success);
        call.resolve(ret);
    }

    /**
     * Convert URL to file path.
     */
    private String urlToPath(String url) {
        if (url.startsWith("file://")) {
            return url.substring(7);
        } else if (url.startsWith("/")) {
            return url;
        }
        return url;
    }
}
