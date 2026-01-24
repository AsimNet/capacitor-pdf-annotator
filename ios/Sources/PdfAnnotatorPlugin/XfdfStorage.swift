import Foundation
import PDFKit
import CommonCrypto

/// Handles saving and loading ink annotations in XFDF format (ISO 19444-1).
/// XFDF is a standard format for PDF annotations that provides cross-platform
/// compatibility between iOS and Android.
///
/// Brush type mapping:
///   0 (Pressure Pen) -> subject="Pressure Pen", opacity=1.0
///   1 (Marker) -> subject="Marker", opacity=1.0
///   2 (Highlighter) -> subject="Highlighter", opacity=0.5
///   3 (Dashed Line) -> subject="Dashed Line", opacity=1.0
public class XfdfStorage {

    private static let annotationsDir = "pdf_annotations"
    private static let xfdfNamespace = "http://ns.adobe.com/xfdf/"
    private static let xfdfTransitionNamespace = "http://ns.adobe.com/xfdf-transition/"
    private static let xfdfVersion = 1
    private static let appName = "capacitor-pdf-annotator"
    private static let appVersion = "1.4.0"

    // Brush type constants
    public static let brushPressurePen = 0
    public static let brushMarker = 1
    public static let brushHighlighter = 2
    public static let brushDashedLine = 3

    // Brush subject strings
    private static let subjectPressurePen = "Pressure Pen"
    private static let subjectMarker = "Marker"
    private static let subjectHighlighter = "Highlighter"
    private static let subjectDashedLine = "Dashed Line"

    // Opacity values
    private static let opacityFull: CGFloat = 1.0
    private static let opacityHighlighter: CGFloat = 0.5

    /// Get the annotations directory URL, creating it if necessary.
    private static func getAnnotationsDir() -> URL? {
        guard let documentsDir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first else {
            return nil
        }

        let annotationsDir = documentsDir.appendingPathComponent(self.annotationsDir)

        if !FileManager.default.fileExists(atPath: annotationsDir.path) {
            do {
                try FileManager.default.createDirectory(at: annotationsDir, withIntermediateDirectories: true)
            } catch {
                print("XfdfStorage: Failed to create annotations directory: \(error)")
                return nil
            }
        }

        return annotationsDir
    }

    /// Generate a unique filename for annotations based on PDF path using MD5 hash.
    private static func getAnnotationFileName(pdfPath: String) -> String {
        let data = Data(pdfPath.utf8)
        var hash = [UInt8](repeating: 0, count: Int(CC_MD5_DIGEST_LENGTH))
        data.withUnsafeBytes {
            _ = CC_MD5($0.baseAddress, CC_LONG(data.count), &hash)
        }
        let hexString = hash.map { String(format: "%02x", $0) }.joined()
        return "\(hexString).xfdf"
    }

    /// Check if XFDF annotations exist for a PDF file.
    public static func hasAnnotations(pdfPath: String) -> Bool {
        guard let annotationsDir = getAnnotationsDir() else { return false }
        let fileName = getAnnotationFileName(pdfPath: pdfPath)
        let filePath = annotationsDir.appendingPathComponent(fileName)
        return FileManager.default.fileExists(atPath: filePath.path)
    }

    /// Delete XFDF annotations for a PDF file.
    public static func deleteAnnotations(pdfPath: String) -> Bool {
        guard let annotationsDir = getAnnotationsDir() else { return false }
        let fileName = getAnnotationFileName(pdfPath: pdfPath)
        let filePath = annotationsDir.appendingPathComponent(fileName)

        if FileManager.default.fileExists(atPath: filePath.path) {
            do {
                try FileManager.default.removeItem(at: filePath)
                return true
            } catch {
                print("XfdfStorage: Failed to delete annotations: \(error)")
                return false
            }
        }
        return true
    }

    /// Export annotations from a PDF file as XFDF string.
    public static func exportAnnotations(pdfPath: String) -> String? {
        // Convert path to URL
        let pdfURL: URL
        if pdfPath.hasPrefix("file://") {
            guard let url = URL(string: pdfPath) else { return nil }
            pdfURL = url
        } else {
            pdfURL = URL(fileURLWithPath: pdfPath)
        }

        // Load PDF document
        guard let pdfDocument = PDFDocument(url: pdfURL) else {
            print("XfdfStorage: Failed to load PDF document")
            return nil
        }

        // Generate XFDF from PDF annotations
        return generateXfdf(from: pdfDocument)
    }

    /// Import annotations from XFDF string and apply to PDF.
    /// Returns the path to the modified PDF (or original if in-place modification).
    public static func importAnnotations(pdfPath: String, xfdfContent: String) -> Bool {
        // Convert path to URL
        let pdfURL: URL
        if pdfPath.hasPrefix("file://") {
            guard let url = URL(string: pdfPath) else { return false }
            pdfURL = url
        } else {
            pdfURL = URL(fileURLWithPath: pdfPath)
        }

        // Load PDF document
        guard let pdfDocument = PDFDocument(url: pdfURL) else {
            print("XfdfStorage: Failed to load PDF document")
            return false
        }

        // Parse XFDF and apply annotations
        guard applyXfdf(xfdfContent, to: pdfDocument) else {
            return false
        }

        // Save modified PDF
        return pdfDocument.write(to: pdfURL)
    }

    /// Save XFDF content to storage for a PDF file.
    public static func saveXfdfToStorage(pdfPath: String, xfdfContent: String) -> Bool {
        guard let annotationsDir = getAnnotationsDir() else { return false }
        let fileName = getAnnotationFileName(pdfPath: pdfPath)
        let filePath = annotationsDir.appendingPathComponent(fileName)

        do {
            try xfdfContent.write(to: filePath, atomically: true, encoding: .utf8)
            return true
        } catch {
            print("XfdfStorage: Failed to save XFDF: \(error)")
            return false
        }
    }

    /// Load XFDF content from storage for a PDF file.
    public static func loadXfdfFromStorage(pdfPath: String) -> String? {
        guard let annotationsDir = getAnnotationsDir() else { return nil }
        let fileName = getAnnotationFileName(pdfPath: pdfPath)
        let filePath = annotationsDir.appendingPathComponent(fileName)

        guard FileManager.default.fileExists(atPath: filePath.path) else { return nil }

        do {
            return try String(contentsOf: filePath, encoding: .utf8)
        } catch {
            print("XfdfStorage: Failed to load XFDF: \(error)")
            return nil
        }
    }

    // MARK: - XFDF Generation

    /// Generate XFDF XML content from PDF document annotations.
    private static func generateXfdf(from pdfDocument: PDFDocument) -> String {
        var xml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <xfdf xmlns="\(xfdfNamespace)" xml:space="preserve">
          <pdf-info xmlns="\(xfdfTransitionNamespace)">
            <VersionID>\(xfdfVersion)</VersionID>
            <AppName>\(appName)</AppName>
            <AppVersion>\(appVersion)</AppVersion>
          </pdf-info>
          <annots>
        """

        let dateFormatter = DateFormatter()
        dateFormatter.dateFormat = "'D:'yyyyMMddHHmmss"
        let creationDate = dateFormatter.string(from: Date())

        // Iterate through all pages
        for pageIndex in 0..<pdfDocument.pageCount {
            guard let page = pdfDocument.page(at: pageIndex) else { continue }

            // Get all annotations on this page
            for annotation in page.annotations {
                // Only process ink annotations
                if annotation.type == "Ink" || annotation.type == PDFAnnotationSubtype.ink.rawValue {
                    if let inkXml = generateInkElement(annotation: annotation, pageIndex: pageIndex, creationDate: creationDate) {
                        xml += inkXml
                    }
                }
            }
        }

        xml += """
          </annots>
        </xfdf>
        """

        return xml
    }

    /// Generate XML element for an ink annotation.
    private static func generateInkElement(annotation: PDFAnnotation, pageIndex: Int, creationDate: String) -> String? {
        // Get paths from annotation
        guard let paths = annotation.paths, !paths.isEmpty else { return nil }

        let bounds = annotation.bounds
        let rect = String(format: "%.2f,%.2f,%.2f,%.2f", bounds.minX, bounds.minY, bounds.maxX, bounds.maxY)

        // Get color
        let color = annotation.color ?? .black
        let colorHex = colorToHex(color)

        // Get stroke width
        let border = annotation.border
        let width = border?.lineWidth ?? 2.0

        // Determine brush type from annotation properties
        var red: CGFloat = 0, green: CGFloat = 0, blue: CGFloat = 0, alpha: CGFloat = 1
        color.getRed(&red, green: &green, blue: &blue, alpha: &alpha)
        let opacity = alpha
        let subject = annotation.value(forAnnotationKey: PDFAnnotationKey(rawValue: "subject")) as? String ?? subjectPressurePen
        let brushType = getBrushType(from: subject, opacity: opacity)
        let opacityValue = getOpacity(for: brushType)

        // Generate annotation ID
        let name = annotation.annotationKeyValues[PDFAnnotationKey.name] as? String ?? "ink-\(UUID().uuidString)"

        var xml = """
            <ink page="\(pageIndex)" rect="\(rect)" color="\(colorHex)" width="\(String(format: "%.1f", width))" opacity="\(String(format: "%.1f", opacityValue))" subject="\(getSubject(for: brushType))" creationdate="\(creationDate)" name="\(name)">
              <inklist>
        """

        // Convert paths to gesture strings
        for path in paths {
            let gestureString = pathToGestureString(path)
            if !gestureString.isEmpty {
                xml += "        <gesture>\(gestureString)</gesture>\n"
            }
        }

        xml += """
              </inklist>
              <popup/>
            </ink>
        """

        return xml
    }

    /// Convert UIBezierPath to gesture string (x1,y1;x2,y2;...).
    private static func pathToGestureString(_ path: UIBezierPath) -> String {
        var points: [String] = []

        // Extract points from path using CGPath
        path.cgPath.applyWithBlock { element in
            let point: CGPoint
            switch element.pointee.type {
            case .moveToPoint, .addLineToPoint:
                point = element.pointee.points[0]
                points.append(String(format: "%.2f,%.2f", point.x, point.y))
            case .addQuadCurveToPoint:
                point = element.pointee.points[1]
                points.append(String(format: "%.2f,%.2f", point.x, point.y))
            case .addCurveToPoint:
                point = element.pointee.points[2]
                points.append(String(format: "%.2f,%.2f", point.x, point.y))
            case .closeSubpath:
                break
            @unknown default:
                break
            }
        }

        return points.joined(separator: ";")
    }

    // MARK: - XFDF Parsing

    /// Apply XFDF annotations to PDF document.
    private static func applyXfdf(_ xfdfContent: String, to pdfDocument: PDFDocument) -> Bool {
        guard let data = xfdfContent.data(using: .utf8) else { return false }

        let parser = XfdfParser()
        guard parser.parse(data: data) else {
            print("XfdfStorage: Failed to parse XFDF")
            return false
        }

        // Remove existing ink annotations first (to avoid duplicates)
        for pageIndex in 0..<pdfDocument.pageCount {
            guard let page = pdfDocument.page(at: pageIndex) else { continue }
            let inkAnnotations = page.annotations.filter { $0.type == "Ink" || $0.type == PDFAnnotationSubtype.ink.rawValue }
            for annotation in inkAnnotations {
                page.removeAnnotation(annotation)
            }
        }

        // Apply parsed annotations
        for inkData in parser.inkAnnotations {
            guard inkData.pageIndex < pdfDocument.pageCount,
                  let page = pdfDocument.page(at: inkData.pageIndex) else { continue }

            // Create ink annotation
            let annotation = PDFAnnotation(bounds: inkData.bounds, forType: .ink, withProperties: nil)

            // Set color with opacity
            var color = hexToColor(inkData.colorHex)
            if inkData.opacity < 1.0 {
                color = color.withAlphaComponent(inkData.opacity)
            }
            annotation.color = color

            // Set border/stroke width
            let border = PDFBorder()
            border.lineWidth = inkData.strokeWidth
            annotation.border = border

            // Set subject for brush type
            annotation.setValue(inkData.subject, forAnnotationKey: PDFAnnotationKey(rawValue: "subject"))

            // Add paths
            for points in inkData.gestures {
                if points.count >= 2 {
                    let path = UIBezierPath()
                    path.move(to: points[0])
                    for i in 1..<points.count {
                        path.addLine(to: points[i])
                    }
                    annotation.add(path)
                }
            }

            page.addAnnotation(annotation)
        }

        return true
    }

    // MARK: - Color Conversion

    /// Convert UIColor to hex string (#RRGGBB).
    private static func colorToHex(_ color: UIColor) -> String {
        var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
        color.getRed(&r, green: &g, blue: &b, alpha: &a)
        return String(format: "#%02X%02X%02X", Int(r * 255), Int(g * 255), Int(b * 255))
    }

    /// Convert hex string (#RRGGBB or #RGB) to UIColor.
    private static func hexToColor(_ hex: String) -> UIColor {
        var hexString = hex.trimmingCharacters(in: .whitespacesAndNewlines)
        if hexString.hasPrefix("#") {
            hexString.removeFirst()
        }

        var rgb: UInt64 = 0
        Scanner(string: hexString).scanHexInt64(&rgb)

        let r, g, b: CGFloat
        if hexString.count == 3 {
            r = CGFloat((rgb >> 8) & 0xF) / 15.0
            g = CGFloat((rgb >> 4) & 0xF) / 15.0
            b = CGFloat(rgb & 0xF) / 15.0
        } else {
            r = CGFloat((rgb >> 16) & 0xFF) / 255.0
            g = CGFloat((rgb >> 8) & 0xFF) / 255.0
            b = CGFloat(rgb & 0xFF) / 255.0
        }

        return UIColor(red: r, green: g, blue: b, alpha: 1.0)
    }

    // MARK: - Brush Type Helpers

    private static func getBrushType(from subject: String, opacity: CGFloat) -> Int {
        switch subject {
        case subjectMarker:
            return brushMarker
        case subjectHighlighter:
            return brushHighlighter
        case subjectDashedLine:
            return brushDashedLine
        case subjectPressurePen:
            return brushPressurePen
        default:
            // Fallback to opacity-based detection
            return opacity < 0.8 ? brushHighlighter : brushPressurePen
        }
    }

    private static func getSubject(for brushType: Int) -> String {
        switch brushType {
        case brushMarker:
            return subjectMarker
        case brushHighlighter:
            return subjectHighlighter
        case brushDashedLine:
            return subjectDashedLine
        default:
            return subjectPressurePen
        }
    }

    private static func getOpacity(for brushType: Int) -> CGFloat {
        return brushType == brushHighlighter ? opacityHighlighter : opacityFull
    }
}

// MARK: - XFDF Parser

/// Simple XML parser for XFDF ink annotations.
private class XfdfParser: NSObject, XMLParserDelegate {

    struct InkAnnotationData {
        var pageIndex: Int
        var bounds: CGRect
        var colorHex: String
        var strokeWidth: CGFloat
        var opacity: CGFloat
        var subject: String
        var gestures: [[CGPoint]]
    }

    var inkAnnotations: [InkAnnotationData] = []

    private var currentInk: InkAnnotationData?
    private var currentGestures: [[CGPoint]] = []
    private var currentElement: String = ""
    private var currentText: String = ""

    func parse(data: Data) -> Bool {
        let parser = XMLParser(data: data)
        parser.delegate = self
        return parser.parse()
    }

    func parser(_ parser: XMLParser, didStartElement elementName: String, namespaceURI: String?, qualifiedName qName: String?, attributes attributeDict: [String : String] = [:]) {
        currentElement = elementName
        currentText = ""

        if elementName == "ink" {
            // Parse ink element attributes
            let pageIndex = Int(attributeDict["page"] ?? "0") ?? 0
            let bounds = parseRect(attributeDict["rect"] ?? "0,0,0,0")
            let colorHex = attributeDict["color"] ?? "#000000"
            let strokeWidth = CGFloat(Float(attributeDict["width"] ?? "2.0") ?? 2.0)
            let opacity = CGFloat(Float(attributeDict["opacity"] ?? "1.0") ?? 1.0)
            let subject = attributeDict["subject"] ?? "Pressure Pen"

            currentInk = InkAnnotationData(
                pageIndex: pageIndex,
                bounds: bounds,
                colorHex: colorHex,
                strokeWidth: strokeWidth,
                opacity: opacity,
                subject: subject,
                gestures: []
            )
            currentGestures = []
        }
    }

    func parser(_ parser: XMLParser, foundCharacters string: String) {
        currentText += string
    }

    func parser(_ parser: XMLParser, didEndElement elementName: String, namespaceURI: String?, qualifiedName qName: String?) {
        if elementName == "gesture" {
            // Parse gesture points
            let points = parseGestureString(currentText.trimmingCharacters(in: .whitespacesAndNewlines))
            if !points.isEmpty {
                currentGestures.append(points)
            }
        } else if elementName == "ink" {
            if var ink = currentInk {
                ink.gestures = currentGestures

                // Recalculate bounds from gestures if needed
                if ink.bounds == .zero && !currentGestures.isEmpty {
                    ink.bounds = calculateBounds(from: currentGestures)
                }

                inkAnnotations.append(ink)
            }
            currentInk = nil
            currentGestures = []
        }

        currentElement = ""
        currentText = ""
    }

    private func parseRect(_ rectString: String) -> CGRect {
        let parts = rectString.split(separator: ",").compactMap { CGFloat(Float($0.trimmingCharacters(in: .whitespaces)) ?? 0) }
        guard parts.count == 4 else { return .zero }
        return CGRect(x: parts[0], y: parts[1], width: parts[2] - parts[0], height: parts[3] - parts[1])
    }

    private func parseGestureString(_ gestureString: String) -> [CGPoint] {
        var points: [CGPoint] = []
        let pairs = gestureString.split(separator: ";")

        for pair in pairs {
            let coords = pair.split(separator: ",").compactMap { CGFloat(Float($0.trimmingCharacters(in: .whitespaces)) ?? 0) }
            if coords.count >= 2 {
                points.append(CGPoint(x: coords[0], y: coords[1]))
            }
        }

        return points
    }

    private func calculateBounds(from gestures: [[CGPoint]]) -> CGRect {
        var minX = CGFloat.greatestFiniteMagnitude
        var minY = CGFloat.greatestFiniteMagnitude
        var maxX = -CGFloat.greatestFiniteMagnitude
        var maxY = -CGFloat.greatestFiniteMagnitude

        for points in gestures {
            for point in points {
                minX = min(minX, point.x)
                minY = min(minY, point.y)
                maxX = max(maxX, point.x)
                maxY = max(maxY, point.y)
            }
        }

        let padding: CGFloat = 2.0
        return CGRect(
            x: minX - padding,
            y: minY - padding,
            width: maxX - minX + padding * 2,
            height: maxY - minY + padding * 2
        )
    }
}
