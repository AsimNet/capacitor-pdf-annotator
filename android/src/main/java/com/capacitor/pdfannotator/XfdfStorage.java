package com.capacitor.pdfannotator;

import android.content.Context;
import android.graphics.PointF;
import android.graphics.RectF;
import android.util.Log;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

/**
 * Handles saving and loading ink annotations in XFDF format (ISO 19444-1).
 * XFDF is a standard format for PDF annotations that provides cross-platform
 * compatibility between iOS and Android.
 *
 * Brush type mapping:
 *   0 (Pressure Pen) -> subject="Pressure Pen", opacity=1.0
 *   1 (Marker) -> subject="Marker", opacity=1.0
 *   2 (Highlighter) -> subject="Highlighter", opacity=0.5
 *   3 (Dashed Line) -> subject="Dashed Line", opacity=1.0
 */
public class XfdfStorage {

    private static final String TAG = "XfdfStorage";
    private static final String ANNOTATIONS_DIR = "pdf_annotations";
    private static final String XFDF_NAMESPACE = "http://ns.adobe.com/xfdf/";
    private static final String XFDF_TRANSITION_NAMESPACE = "http://ns.adobe.com/xfdf-transition/";
    private static final int XFDF_VERSION = 1;
    private static final String APP_NAME = "capacitor-pdf-annotator";
    private static final String APP_VERSION = "1.4.0";

    // Brush type constants
    public static final int BRUSH_PRESSURE_PEN = 0;
    public static final int BRUSH_MARKER = 1;
    public static final int BRUSH_HIGHLIGHTER = 2;
    public static final int BRUSH_DASHED_LINE = 3;

    // Brush subject strings
    private static final String SUBJECT_PRESSURE_PEN = "Pressure Pen";
    private static final String SUBJECT_MARKER = "Marker";
    private static final String SUBJECT_HIGHLIGHTER = "Highlighter";
    private static final String SUBJECT_DASHED_LINE = "Dashed Line";

    // Opacity values
    private static final float OPACITY_FULL = 1.0f;
    private static final float OPACITY_HIGHLIGHTER = 0.5f;

    private final Context context;

    public XfdfStorage(Context context) {
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
            return hexString.toString() + ".xfdf";
        } catch (NoSuchAlgorithmException e) {
            // Fallback to simple hash
            return Math.abs(pdfPath.hashCode()) + ".xfdf";
        }
    }

    /**
     * Get the legacy JSON filename (for migration).
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
            return Math.abs(pdfPath.hashCode()) + ".json";
        }
    }

    /**
     * Check if legacy JSON annotations exist for a PDF file.
     */
    public boolean hasLegacyJsonAnnotations(String pdfPath) {
        File jsonFile = new File(getAnnotationsDir(), getLegacyJsonFileName(pdfPath));
        return jsonFile.exists();
    }

    /**
     * Delete the legacy JSON annotations file after successful migration.
     */
    public boolean deleteLegacyJsonAnnotations(String pdfPath) {
        File jsonFile = new File(getAnnotationsDir(), getLegacyJsonFileName(pdfPath));
        if (jsonFile.exists()) {
            boolean deleted = jsonFile.delete();
            if (deleted) {
                Log.d(TAG, "Deleted legacy JSON file: " + jsonFile.getAbsolutePath());
            }
            return deleted;
        }
        return true;
    }

    /**
     * Save annotations for a PDF file in XFDF format.
     */
    public boolean saveAnnotations(String pdfPath, Map<Integer, List<InkCanvasView.InkStroke>> strokesByPage) {
        try {
            String xfdfContent = generateXfdf(pdfPath, strokesByPage);

            File annotationFile = new File(getAnnotationsDir(), getAnnotationFileName(pdfPath));
            FileWriter writer = new FileWriter(annotationFile);
            writer.write(xfdfContent);
            writer.close();

            Log.d(TAG, "Saved XFDF annotations to: " + annotationFile.getAbsolutePath());
            return true;

        } catch (Exception e) {
            Log.e(TAG, "Error saving XFDF annotations", e);
            return false;
        }
    }

    /**
     * Load annotations for a PDF file from XFDF format.
     * Returns a map of page index to list of strokes.
     */
    public Map<Integer, List<InkCanvasView.InkStroke>> loadAnnotations(String pdfPath) {
        Map<Integer, List<InkCanvasView.InkStroke>> strokesByPage = new HashMap<>();

        File annotationFile = new File(getAnnotationsDir(), getAnnotationFileName(pdfPath));
        if (!annotationFile.exists()) {
            Log.d(TAG, "No XFDF annotations file found for: " + pdfPath);
            return strokesByPage;
        }

        try {
            // Read file content
            StringBuilder content = new StringBuilder();
            BufferedReader reader = new BufferedReader(new FileReader(annotationFile));
            String line;
            while ((line = reader.readLine()) != null) {
                content.append(line).append("\n");
            }
            reader.close();

            strokesByPage = parseXfdf(content.toString());

            Log.d(TAG, "Loaded XFDF annotations from: " + annotationFile.getAbsolutePath());

        } catch (Exception e) {
            Log.e(TAG, "Error loading XFDF annotations", e);
        }

        return strokesByPage;
    }

    /**
     * Check if XFDF annotations exist for a PDF file.
     */
    public boolean hasAnnotations(String pdfPath) {
        File annotationFile = new File(getAnnotationsDir(), getAnnotationFileName(pdfPath));
        return annotationFile.exists();
    }

    /**
     * Delete XFDF annotations for a PDF file.
     */
    public boolean deleteAnnotations(String pdfPath) {
        File annotationFile = new File(getAnnotationsDir(), getAnnotationFileName(pdfPath));
        if (annotationFile.exists()) {
            return annotationFile.delete();
        }
        return true;
    }

    /**
     * Export annotations as XFDF string for server upload.
     */
    public String exportAnnotationsAsString(String pdfPath, Map<Integer, List<InkCanvasView.InkStroke>> strokesByPage) {
        try {
            return generateXfdf(pdfPath, strokesByPage);
        } catch (Exception e) {
            Log.e(TAG, "Error exporting XFDF annotations", e);
            return null;
        }
    }

    /**
     * Import annotations from XFDF string and save to storage.
     */
    public boolean importAnnotationsFromString(String pdfPath, String xfdfContent) {
        try {
            // Validate the XFDF content by parsing it
            Map<Integer, List<InkCanvasView.InkStroke>> strokesByPage = parseXfdf(xfdfContent);

            // Save to file
            File annotationFile = new File(getAnnotationsDir(), getAnnotationFileName(pdfPath));
            FileWriter writer = new FileWriter(annotationFile);
            writer.write(xfdfContent);
            writer.close();

            Log.d(TAG, "Imported XFDF annotations to: " + annotationFile.getAbsolutePath());
            return true;

        } catch (Exception e) {
            Log.e(TAG, "Error importing XFDF annotations", e);
            return false;
        }
    }

    /**
     * Generate XFDF XML content from strokes.
     */
    private String generateXfdf(String pdfPath, Map<Integer, List<InkCanvasView.InkStroke>> strokesByPage)
            throws ParserConfigurationException, TransformerException {

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.newDocument();

        // Create root element
        Element xfdfElement = doc.createElementNS(XFDF_NAMESPACE, "xfdf");
        xfdfElement.setAttribute("xmlns", XFDF_NAMESPACE);
        xfdfElement.setAttribute("xml:space", "preserve");
        doc.appendChild(xfdfElement);

        // Create pdf-info element with version
        Element pdfInfoElement = doc.createElementNS(XFDF_TRANSITION_NAMESPACE, "pdf-info");
        pdfInfoElement.setAttribute("xmlns", XFDF_TRANSITION_NAMESPACE);

        Element versionElement = doc.createElement("VersionID");
        versionElement.setTextContent(String.valueOf(XFDF_VERSION));
        pdfInfoElement.appendChild(versionElement);

        Element appNameElement = doc.createElement("AppName");
        appNameElement.setTextContent(APP_NAME);
        pdfInfoElement.appendChild(appNameElement);

        Element appVersionElement = doc.createElement("AppVersion");
        appVersionElement.setTextContent(APP_VERSION);
        pdfInfoElement.appendChild(appVersionElement);

        xfdfElement.appendChild(pdfInfoElement);

        // Create annots element
        Element annotsElement = doc.createElement("annots");
        xfdfElement.appendChild(annotsElement);

        // Add ink annotations for each page
        String creationDate = formatPdfDate(new Date());

        for (Map.Entry<Integer, List<InkCanvasView.InkStroke>> entry : strokesByPage.entrySet()) {
            int pageIndex = entry.getKey();
            List<InkCanvasView.InkStroke> strokes = entry.getValue();

            for (InkCanvasView.InkStroke stroke : strokes) {
                Element inkElement = createInkElement(doc, stroke, pageIndex, creationDate);
                annotsElement.appendChild(inkElement);
            }
        }

        // Transform to string
        TransformerFactory transformerFactory = TransformerFactory.newInstance();
        Transformer transformer = transformerFactory.newTransformer();
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");

        StringWriter writer = new StringWriter();
        transformer.transform(new DOMSource(doc), new StreamResult(writer));

        return writer.toString();
    }

    /**
     * Create an ink element for a stroke.
     */
    private Element createInkElement(Document doc, InkCanvasView.InkStroke stroke, int pageIndex, String creationDate) {
        Element inkElement = doc.createElement("ink");

        // Basic attributes
        inkElement.setAttribute("page", String.valueOf(pageIndex));
        inkElement.setAttribute("rect", calculateRect(stroke.points));
        inkElement.setAttribute("color", colorToHex(stroke.color));
        inkElement.setAttribute("width", String.valueOf(stroke.strokeWidth));

        // Brush-specific attributes
        float opacity = getOpacityForBrushType(stroke.brushType);
        String subject = getSubjectForBrushType(stroke.brushType);

        inkElement.setAttribute("opacity", String.valueOf(opacity));
        inkElement.setAttribute("subject", subject);

        // Metadata
        inkElement.setAttribute("creationdate", creationDate);
        inkElement.setAttribute("name", generateAnnotationId());

        // Create inklist element
        Element inklistElement = doc.createElement("inklist");
        inkElement.appendChild(inklistElement);

        // Create gesture element with points
        Element gestureElement = doc.createElement("gesture");
        gestureElement.setTextContent(pointsToGestureString(stroke.points));
        inklistElement.appendChild(gestureElement);

        // Empty popup element (required by some readers)
        Element popupElement = doc.createElement("popup");
        inkElement.appendChild(popupElement);

        return inkElement;
    }

    /**
     * Parse XFDF XML content into strokes.
     */
    private Map<Integer, List<InkCanvasView.InkStroke>> parseXfdf(String xfdfContent)
            throws ParserConfigurationException, IOException, SAXException {

        Map<Integer, List<InkCanvasView.InkStroke>> strokesByPage = new HashMap<>();

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(new InputSource(new StringReader(xfdfContent)));

        // Get all ink elements
        NodeList inkElements = doc.getElementsByTagName("ink");

        for (int i = 0; i < inkElements.getLength(); i++) {
            Element inkElement = (Element) inkElements.item(i);

            // Parse page index
            int pageIndex = Integer.parseInt(inkElement.getAttribute("page"));

            // Parse color
            String colorHex = inkElement.getAttribute("color");
            int color = hexToColor(colorHex);

            // Parse stroke width
            float strokeWidth = Float.parseFloat(inkElement.getAttribute("width"));

            // Parse brush type from subject and opacity
            String subject = inkElement.getAttribute("subject");
            String opacityStr = inkElement.getAttribute("opacity");
            float opacity = opacityStr.isEmpty() ? 1.0f : Float.parseFloat(opacityStr);
            int brushType = getBrushTypeFromSubjectAndOpacity(subject, opacity);

            // Create stroke
            InkCanvasView.InkStroke stroke = new InkCanvasView.InkStroke(pageIndex, color, strokeWidth, brushType);

            // Parse gesture points
            NodeList gestureElements = inkElement.getElementsByTagName("gesture");
            for (int j = 0; j < gestureElements.getLength(); j++) {
                Element gestureElement = (Element) gestureElements.item(j);
                String gestureStr = gestureElement.getTextContent();
                List<PointF> points = parseGestureString(gestureStr);
                stroke.points.addAll(points);
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

            // Add to page map
            if (!strokesByPage.containsKey(pageIndex)) {
                strokesByPage.put(pageIndex, new ArrayList<>());
            }
            strokesByPage.get(pageIndex).add(stroke);
        }

        return strokesByPage;
    }

    /**
     * Convert ARGB color int to hex string (#RRGGBB).
     */
    private String colorToHex(int color) {
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        return String.format("#%02X%02X%02X", r, g, b);
    }

    /**
     * Convert hex string (#RRGGBB or #RGB) to ARGB color int.
     */
    private int hexToColor(String hex) {
        if (hex == null || hex.isEmpty()) {
            return 0xFF000000; // Default to black
        }

        if (hex.startsWith("#")) {
            hex = hex.substring(1);
        }

        try {
            int r, g, b;
            if (hex.length() == 3) {
                r = Integer.parseInt(hex.substring(0, 1) + hex.substring(0, 1), 16);
                g = Integer.parseInt(hex.substring(1, 2) + hex.substring(1, 2), 16);
                b = Integer.parseInt(hex.substring(2, 3) + hex.substring(2, 3), 16);
            } else if (hex.length() == 6) {
                r = Integer.parseInt(hex.substring(0, 2), 16);
                g = Integer.parseInt(hex.substring(2, 4), 16);
                b = Integer.parseInt(hex.substring(4, 6), 16);
            } else {
                return 0xFF000000; // Default to black
            }
            return 0xFF000000 | (r << 16) | (g << 8) | b;
        } catch (NumberFormatException e) {
            return 0xFF000000; // Default to black
        }
    }

    /**
     * Calculate bounding rect from points.
     * Returns "left,bottom,right,top" format (PDF coordinate system).
     */
    private String calculateRect(List<PointF> points) {
        if (points.isEmpty()) {
            return "0,0,0,0";
        }

        float minX = Float.MAX_VALUE;
        float minY = Float.MAX_VALUE;
        float maxX = Float.MIN_VALUE;
        float maxY = Float.MIN_VALUE;

        for (PointF point : points) {
            minX = Math.min(minX, point.x);
            minY = Math.min(minY, point.y);
            maxX = Math.max(maxX, point.x);
            maxY = Math.max(maxY, point.y);
        }

        // Add small padding
        float padding = 2.0f;
        minX -= padding;
        minY -= padding;
        maxX += padding;
        maxY += padding;

        return String.format(Locale.US, "%.2f,%.2f,%.2f,%.2f", minX, minY, maxX, maxY);
    }

    /**
     * Convert points list to gesture string (x1,y1;x2,y2;...).
     */
    private String pointsToGestureString(List<PointF> points) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < points.size(); i++) {
            if (i > 0) {
                sb.append(";");
            }
            PointF point = points.get(i);
            sb.append(String.format(Locale.US, "%.2f,%.2f", point.x, point.y));
        }
        return sb.toString();
    }

    /**
     * Parse gesture string to points list.
     */
    private List<PointF> parseGestureString(String gestureStr) {
        List<PointF> points = new ArrayList<>();

        if (gestureStr == null || gestureStr.isEmpty()) {
            return points;
        }

        String[] pairs = gestureStr.split(";");
        for (String pair : pairs) {
            String[] coords = pair.split(",");
            if (coords.length >= 2) {
                try {
                    float x = Float.parseFloat(coords[0].trim());
                    float y = Float.parseFloat(coords[1].trim());
                    points.add(new PointF(x, y));
                } catch (NumberFormatException e) {
                    Log.w(TAG, "Invalid point in gesture: " + pair);
                }
            }
        }

        return points;
    }

    /**
     * Generate unique annotation ID.
     */
    private String generateAnnotationId() {
        return "ink-" + UUID.randomUUID().toString();
    }

    /**
     * Format date in PDF date format (D:YYYYMMDDHHmmss).
     */
    private String formatPdfDate(Date date) {
        SimpleDateFormat sdf = new SimpleDateFormat("'D:'yyyyMMddHHmmss", Locale.US);
        return sdf.format(date);
    }

    /**
     * Get opacity value for brush type.
     */
    private float getOpacityForBrushType(int brushType) {
        if (brushType == BRUSH_HIGHLIGHTER) {
            return OPACITY_HIGHLIGHTER;
        }
        return OPACITY_FULL;
    }

    /**
     * Get subject string for brush type.
     */
    private String getSubjectForBrushType(int brushType) {
        switch (brushType) {
            case BRUSH_MARKER:
                return SUBJECT_MARKER;
            case BRUSH_HIGHLIGHTER:
                return SUBJECT_HIGHLIGHTER;
            case BRUSH_DASHED_LINE:
                return SUBJECT_DASHED_LINE;
            case BRUSH_PRESSURE_PEN:
            default:
                return SUBJECT_PRESSURE_PEN;
        }
    }

    /**
     * Get brush type from subject string and opacity.
     */
    private int getBrushTypeFromSubjectAndOpacity(String subject, float opacity) {
        if (subject == null || subject.isEmpty()) {
            // Use opacity as fallback
            if (opacity < 0.8f) {
                return BRUSH_HIGHLIGHTER;
            }
            return BRUSH_PRESSURE_PEN;
        }

        switch (subject) {
            case SUBJECT_MARKER:
                return BRUSH_MARKER;
            case SUBJECT_HIGHLIGHTER:
                return BRUSH_HIGHLIGHTER;
            case SUBJECT_DASHED_LINE:
                return BRUSH_DASHED_LINE;
            case SUBJECT_PRESSURE_PEN:
            default:
                return BRUSH_PRESSURE_PEN;
        }
    }
}
