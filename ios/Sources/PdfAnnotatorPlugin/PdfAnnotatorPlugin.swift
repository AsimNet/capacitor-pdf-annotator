import Foundation
import Capacitor

@objc(PdfAnnotatorPlugin)
public class PdfAnnotatorPlugin: CAPPlugin, CAPBridgedPlugin {
    public let identifier = "PdfAnnotatorPlugin"
    public let jsName = "PdfAnnotator"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "openPdf", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "isInkSupported", returnType: CAPPluginReturnPromise)
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
            enableSearch: enableSearch
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
}
