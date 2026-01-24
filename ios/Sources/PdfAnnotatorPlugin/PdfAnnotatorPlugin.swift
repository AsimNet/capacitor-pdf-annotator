import Foundation
import Capacitor
import PDFKit

@objc(PdfAnnotatorPlugin)
public class PdfAnnotatorPlugin: CAPPlugin, CAPBridgedPlugin {
    public let identifier = "PdfAnnotatorPlugin"
    public let jsName = "PdfAnnotator"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "openPdf", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "isInkSupported", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "exportAnnotations", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "importAnnotations", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "hasAnnotations", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "deleteAnnotations", returnType: CAPPluginReturnPromise)
    ]

    private let implementation = PdfAnnotator()
    private var currentCall: CAPPluginCall?

    @objc func openPdf(_ call: CAPPluginCall) {
        guard let urlString = call.getString("url") else {
            call.reject("URL parameter is required")
            return
        }


        // Get options with defaults
        let enableAnnotations = call.getBool("enableAnnotations") ?? true
        let enableInk = call.getBool("enableInk") ?? true
        let inkColor = call.getString("inkColor") ?? "#000000"
        let inkWidth = call.getFloat("inkWidth") ?? 2.0
        let initialPage = call.getInt("initialPage") ?? 0
        let title = call.getString("title")
        let enableTextSelection = call.getBool("enableTextSelection") ?? true
        let enableSearch = call.getBool("enableSearch") ?? true
        let primaryColor = call.getString("primaryColor")
        let toolbarColor = call.getString("toolbarColor")

        // Get view controller
        guard let viewController = self.bridge?.viewController else {
            call.reject("Unable to access view controller")
            return
        }

        self.currentCall = call

        let options = PdfAnnotatorOptions(
            enableAnnotations: enableAnnotations,
            enableInk: enableInk,
            inkColor: inkColor,
            inkWidth: inkWidth,
            initialPage: initialPage,
            title: title,
            enableTextSelection: enableTextSelection,
            enableSearch: enableSearch,
            primaryColor: primaryColor,
            toolbarColor: toolbarColor
        )

        do {
            try implementation.openPdf(
                url: urlString,
                options: options,
                from: viewController,
                onDismiss: { [weak self] saved, savedPath in
                    var result: [String: Any] = ["dismissed": true]
                    if let saved = saved {
                        result["saved"] = saved
                    }
                    if let savedPath = savedPath {
                        result["savedPath"] = savedPath
                    }
                    self?.currentCall?.resolve(result)
                    self?.currentCall = nil
                },
                onSaved: { [weak self] path, type in
                    self?.notifyListeners("pdfSaved", data: [
                        "path": path,
                        "type": type
                    ])
                },
                onAnnotationAdded: { [weak self] type, page in
                    self?.notifyListeners("annotationAdded", data: [
                        "type": type,
                        "page": page
                    ])
                }
            )
        } catch {
            call.reject(error.localizedDescription)
        }
    }

    @objc func isInkSupported(_ call: CAPPluginCall) {
        // iOS always supports ink via Apple Pencil / finger
        call.resolve([
            "supported": true,
            "stylusConnected": false, // Would need to check UIPencilInteraction
            "lowLatencyInk": true // iOS has low-latency ink support
        ])
    }

    /// Export annotations for a PDF file as XFDF string.
    /// This can be used to upload annotations to a server for cloud sync.
    @objc func exportAnnotations(_ call: CAPPluginCall) {
        guard let urlString = call.getString("url") else {
            call.reject("URL parameter is required")
            return
        }

        // Convert URL to path
        let pdfPath = urlToPath(urlString)

        // Export annotations from PDF
        guard let xfdf = XfdfStorage.exportAnnotations(pdfPath: pdfPath) else {
            call.reject("Failed to export annotations")
            return
        }

        call.resolve([
            "xfdf": xfdf
        ])
    }

    /// Import annotations from XFDF string and apply to PDF.
    /// This can be used to download annotations from a server for cloud sync.
    @objc func importAnnotations(_ call: CAPPluginCall) {
        guard let urlString = call.getString("url") else {
            call.reject("URL parameter is required")
            return
        }

        guard let xfdf = call.getString("xfdf") else {
            call.reject("XFDF parameter is required")
            return
        }

        // Convert URL to path
        let pdfPath = urlToPath(urlString)

        // Import annotations to PDF
        let success = XfdfStorage.importAnnotations(pdfPath: pdfPath, xfdfContent: xfdf)

        if !success {
            call.reject("Failed to import annotations")
            return
        }

        call.resolve([
            "success": true
        ])
    }

    /// Check if annotations exist for a PDF file.
    @objc func hasAnnotations(_ call: CAPPluginCall) {
        guard let urlString = call.getString("url") else {
            call.reject("URL parameter is required")
            return
        }

        // Convert URL to path
        let pdfPath = urlToPath(urlString)

        // Check for stored XFDF annotations
        let hasStoredAnnotations = XfdfStorage.hasAnnotations(pdfPath: pdfPath)

        // Also check if PDF has embedded ink annotations
        var hasEmbeddedAnnotations = false
        if let pdfURL = URL(string: pdfPath.hasPrefix("file://") ? pdfPath : "file://\(pdfPath)"),
           let pdfDocument = PDFKit.PDFDocument(url: pdfURL) {
            for i in 0..<pdfDocument.pageCount {
                if let page = pdfDocument.page(at: i) {
                    let inkAnnotations = page.annotations.filter { $0.type == "Ink" }
                    if !inkAnnotations.isEmpty {
                        hasEmbeddedAnnotations = true
                        break
                    }
                }
            }
        }

        call.resolve([
            "hasAnnotations": hasStoredAnnotations || hasEmbeddedAnnotations
        ])
    }

    /// Delete annotations for a PDF file.
    @objc func deleteAnnotations(_ call: CAPPluginCall) {
        guard let urlString = call.getString("url") else {
            call.reject("URL parameter is required")
            return
        }

        // Convert URL to path
        let pdfPath = urlToPath(urlString)

        // Delete stored XFDF annotations
        let xfdfDeleted = XfdfStorage.deleteAnnotations(pdfPath: pdfPath)

        // Also remove ink annotations from PDF
        var pdfCleaned = true
        if let pdfURL = URL(string: pdfPath.hasPrefix("file://") ? pdfPath : "file://\(pdfPath)"),
           let pdfDocument = PDFKit.PDFDocument(url: pdfURL) {
            var modified = false
            for i in 0..<pdfDocument.pageCount {
                if let page = pdfDocument.page(at: i) {
                    let inkAnnotations = page.annotations.filter { $0.type == "Ink" }
                    for annotation in inkAnnotations {
                        page.removeAnnotation(annotation)
                        modified = true
                    }
                }
            }
            if modified {
                pdfCleaned = pdfDocument.write(to: pdfURL)
            }
        }

        call.resolve([
            "success": xfdfDeleted && pdfCleaned
        ])
    }

    /// Convert URL string to file path.
    private func urlToPath(_ url: String) -> String {
        if url.hasPrefix("file://") {
            return url
        } else if url.hasPrefix("/") {
            return "file://\(url)"
        }
        return url
    }
}
