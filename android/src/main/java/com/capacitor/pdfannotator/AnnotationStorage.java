package com.capacitor.pdfannotator;

import android.content.Context;
import android.graphics.PointF;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Handles saving and loading ink annotations.
 * This class now delegates to XfdfStorage for the new XFDF format (ISO 19444-1)
 * while providing backward compatibility for legacy JSON files.
 *
 * Migration strategy:
 * 1. On load, first check for XFDF file
 * 2. If not found, check for legacy JSON file
 * 3. If JSON exists, convert to XFDF and delete the JSON file
 * 4. All new saves use XFDF format
 */
public class AnnotationStorage {

    private static final String TAG = "AnnotationStorage";
    private static final String ANNOTATIONS_DIR = "pdf_annotations";

    private final Context context;
    private final XfdfStorage xfdfStorage;

    public AnnotationStorage(Context context) {
        this.context = context;
        this.xfdfStorage = new XfdfStorage(context);
    }

    /**
     * Get the annotations directory, creating it if necessary.
     */
    private File getAnnotationsDir() {
        File dir = new File(context.getFilesDir(), ANNOTATIONS_DIR);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    /**
     * Generate a unique filename for legacy JSON annotations based on PDF path.
     */
    private String getLegacyJsonFileName(String pdfPath) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] hash = digest.digest(pdfPath.getBytes());
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString() + ".json";
        } catch (NoSuchAlgorithmException e) {
            // Fallback to simple hash
            return Math.abs(pdfPath.hashCode()) + ".json";
        }
    }

    /**
     * Save annotations for a PDF file.
     * Now uses XFDF format for cross-platform compatibility.
     */
    public boolean saveAnnotations(String pdfPath, Map<Integer, List<InkCanvasView.InkStroke>> strokesByPage) {
        return xfdfStorage.saveAnnotations(pdfPath, strokesByPage);
    }

    /**
     * Load annotations for a PDF file.
     * Checks for XFDF first, then falls back to legacy JSON with automatic migration.
     *
     * @param pdfPath Path to the PDF file
     * @return Map of page index to list of strokes
     */
    public Map<Integer, List<InkCanvasView.InkStroke>> loadAnnotations(String pdfPath) {
        // First, try to load from XFDF format
        if (xfdfStorage.hasAnnotations(pdfPath)) {
            Log.d(TAG, "Loading annotations from XFDF format");
            return xfdfStorage.loadAnnotations(pdfPath);
        }

        // Check for legacy JSON file
        File legacyJsonFile = new File(getAnnotationsDir(), getLegacyJsonFileName(pdfPath));
        if (legacyJsonFile.exists()) {
            Log.d(TAG, "Found legacy JSON annotations, migrating to XFDF");
            Map<Integer, List<InkCanvasView.InkStroke>> strokesByPage = loadLegacyJsonAnnotations(pdfPath);

            if (!strokesByPage.isEmpty()) {
                // Migrate to XFDF format
                boolean migrated = xfdfStorage.saveAnnotations(pdfPath, strokesByPage);
                if (migrated) {
                    // Delete legacy JSON file after successful migration
                    if (legacyJsonFile.delete()) {
                        Log.d(TAG, "Successfully migrated to XFDF and deleted legacy JSON file");
                    } else {
                        Log.w(TAG, "Migrated to XFDF but failed to delete legacy JSON file");
                    }
                } else {
                    Log.w(TAG, "Failed to migrate to XFDF, keeping legacy JSON file");
                }
            }

            return strokesByPage;
        }

        Log.d(TAG, "No annotations file found for: " + pdfPath);
        return new HashMap<>();
    }

    /**
     * Load annotations from legacy JSON format.
     * This method is kept for backward compatibility during migration.
     */
    private Map<Integer, List<InkCanvasView.InkStroke>> loadLegacyJsonAnnotations(String pdfPath) {
        Map<Integer, List<InkCanvasView.InkStroke>> strokesByPage = new HashMap<>();

        File annotationFile = new File(getAnnotationsDir(), getLegacyJsonFileName(pdfPath));
        if (!annotationFile.exists()) {
            return strokesByPage;
        }

        try {
            // Read file content
            StringBuilder content = new StringBuilder();
            BufferedReader reader = new BufferedReader(new FileReader(annotationFile));
            String line;
            while ((line = reader.readLine()) != null) {
                content.append(line);
            }
            reader.close();

            // Parse JSON
            JSONObject root = new JSONObject(content.toString());
            JSONArray pagesArray = root.getJSONArray("pages");

            for (int i = 0; i < pagesArray.length(); i++) {
                JSONObject pageObj = pagesArray.getJSONObject(i);
                int pageIndex = pageObj.getInt("pageIndex");
                JSONArray strokesArray = pageObj.getJSONArray("strokes");

                List<InkCanvasView.InkStroke> strokes = new ArrayList<>();
                for (int j = 0; j < strokesArray.length(); j++) {
                    JSONObject strokeObj = strokesArray.getJSONObject(j);
                    int color = strokeObj.getInt("color");
                    float strokeWidth = (float) strokeObj.getDouble("strokeWidth");
                    int brushType = strokeObj.optInt("brushType", 0); // Default to pressure pen (0) for legacy strokes

                    InkCanvasView.InkStroke stroke = new InkCanvasView.InkStroke(pageIndex, color, strokeWidth, brushType);

                    JSONArray pointsArray = strokeObj.getJSONArray("points");
                    for (int k = 0; k < pointsArray.length(); k++) {
                        JSONObject pointObj = pointsArray.getJSONObject(k);
                        float x = (float) pointObj.getDouble("x");
                        float y = (float) pointObj.getDouble("y");
                        stroke.points.add(new PointF(x, y));
                    }

                    // Rebuild the path from points
                    if (!stroke.points.isEmpty()) {
                        PointF first = stroke.points.get(0);
                        stroke.path.moveTo(first.x, first.y);
                        for (int k = 1; k < stroke.points.size(); k++) {
                            PointF point = stroke.points.get(k);
                            stroke.path.lineTo(point.x, point.y);
                        }
                    }

                    strokes.add(stroke);
                }

                strokesByPage.put(pageIndex, strokes);
            }

            Log.d(TAG, "Loaded legacy JSON annotations from: " + annotationFile.getAbsolutePath());

        } catch (JSONException | IOException e) {
            Log.e(TAG, "Error loading legacy JSON annotations", e);
        }

        return strokesByPage;
    }

    /**
     * Delete annotations for a PDF file.
     * Deletes both XFDF and legacy JSON files if they exist.
     */
    public boolean deleteAnnotations(String pdfPath) {
        boolean xfdfDeleted = xfdfStorage.deleteAnnotations(pdfPath);

        // Also delete legacy JSON file if it exists
        File legacyJsonFile = new File(getAnnotationsDir(), getLegacyJsonFileName(pdfPath));
        boolean jsonDeleted = true;
        if (legacyJsonFile.exists()) {
            jsonDeleted = legacyJsonFile.delete();
        }

        return xfdfDeleted && jsonDeleted;
    }

    /**
     * Check if annotations exist for a PDF file.
     * Checks both XFDF and legacy JSON formats.
     */
    public boolean hasAnnotations(String pdfPath) {
        if (xfdfStorage.hasAnnotations(pdfPath)) {
            return true;
        }

        // Check legacy JSON file
        File legacyJsonFile = new File(getAnnotationsDir(), getLegacyJsonFileName(pdfPath));
        return legacyJsonFile.exists();
    }

    /**
     * Export annotations as XFDF string for server upload.
     *
     * @param pdfPath Path to the PDF file
     * @param strokesByPage Current strokes (if available), otherwise loads from storage
     * @return XFDF XML string
     */
    public String exportAnnotationsAsXfdf(String pdfPath, Map<Integer, List<InkCanvasView.InkStroke>> strokesByPage) {
        if (strokesByPage == null || strokesByPage.isEmpty()) {
            strokesByPage = loadAnnotations(pdfPath);
        }
        return xfdfStorage.exportAnnotationsAsString(pdfPath, strokesByPage);
    }

    /**
     * Import annotations from XFDF string and save to storage.
     *
     * @param pdfPath Path to the PDF file
     * @param xfdfContent XFDF XML string
     * @return true if import was successful
     */
    public boolean importAnnotationsFromXfdf(String pdfPath, String xfdfContent) {
        return xfdfStorage.importAnnotationsFromString(pdfPath, xfdfContent);
    }

    /**
     * Get the XfdfStorage instance for direct access to XFDF operations.
     */
    public XfdfStorage getXfdfStorage() {
        return xfdfStorage;
    }
}
