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
import java.io.FileWriter;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Handles saving and loading ink annotations as JSON files.
 * Annotations are stored separately from the PDF to preserve the original file.
 */
public class AnnotationStorage {

    private static final String TAG = "AnnotationStorage";
    private static final String ANNOTATIONS_DIR = "pdf_annotations";

    private final Context context;

    public AnnotationStorage(Context context) {
        this.context = context;
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
     * Generate a unique filename for annotations based on PDF path.
     */
    private String getAnnotationFileName(String pdfPath) {
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
     */
    public boolean saveAnnotations(String pdfPath, Map<Integer, List<InkCanvasView.InkStroke>> strokesByPage) {
        try {
            JSONObject root = new JSONObject();
            root.put("pdfPath", pdfPath);
            root.put("version", 1);

            JSONArray pagesArray = new JSONArray();
            for (Map.Entry<Integer, List<InkCanvasView.InkStroke>> entry : strokesByPage.entrySet()) {
                int pageIndex = entry.getKey();
                List<InkCanvasView.InkStroke> strokes = entry.getValue();

                if (strokes.isEmpty()) continue;

                JSONObject pageObj = new JSONObject();
                pageObj.put("pageIndex", pageIndex);

                JSONArray strokesArray = new JSONArray();
                for (InkCanvasView.InkStroke stroke : strokes) {
                    JSONObject strokeObj = new JSONObject();
                    strokeObj.put("color", stroke.color);
                    strokeObj.put("strokeWidth", stroke.strokeWidth);
                    strokeObj.put("brushType", stroke.brushType);

                    JSONArray pointsArray = new JSONArray();
                    for (PointF point : stroke.points) {
                        JSONObject pointObj = new JSONObject();
                        pointObj.put("x", point.x);
                        pointObj.put("y", point.y);
                        pointsArray.put(pointObj);
                    }
                    strokeObj.put("points", pointsArray);
                    strokesArray.put(strokeObj);
                }
                pageObj.put("strokes", strokesArray);
                pagesArray.put(pageObj);
            }
            root.put("pages", pagesArray);

            // Write to file
            File annotationFile = new File(getAnnotationsDir(), getAnnotationFileName(pdfPath));
            FileWriter writer = new FileWriter(annotationFile);
            writer.write(root.toString());
            writer.close();

            Log.d(TAG, "Saved annotations to: " + annotationFile.getAbsolutePath());
            return true;

        } catch (JSONException | IOException e) {
            Log.e(TAG, "Error saving annotations", e);
            return false;
        }
    }

    /**
     * Load annotations for a PDF file.
     * Returns a map of page index to list of strokes.
     */
    public Map<Integer, List<InkCanvasView.InkStroke>> loadAnnotations(String pdfPath) {
        Map<Integer, List<InkCanvasView.InkStroke>> strokesByPage = new HashMap<>();

        File annotationFile = new File(getAnnotationsDir(), getAnnotationFileName(pdfPath));
        if (!annotationFile.exists()) {
            Log.d(TAG, "No annotations file found for: " + pdfPath);
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

            Log.d(TAG, "Loaded annotations from: " + annotationFile.getAbsolutePath());

        } catch (JSONException | IOException e) {
            Log.e(TAG, "Error loading annotations", e);
        }

        return strokesByPage;
    }

    /**
     * Delete annotations for a PDF file.
     */
    public boolean deleteAnnotations(String pdfPath) {
        File annotationFile = new File(getAnnotationsDir(), getAnnotationFileName(pdfPath));
        if (annotationFile.exists()) {
            return annotationFile.delete();
        }
        return true;
    }

    /**
     * Check if annotations exist for a PDF file.
     */
    public boolean hasAnnotations(String pdfPath) {
        File annotationFile = new File(getAnnotationsDir(), getAnnotationFileName(pdfPath));
        return annotationFile.exists();
    }
}
