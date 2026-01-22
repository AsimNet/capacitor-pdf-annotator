import Foundation
import QuickLook
import UIKit

public struct PdfAnnotatorOptions {
    let enableAnnotations: Bool
    let enableInk: Bool
    let inkColor: String
    let inkWidth: Float
    let initialPage: Int
    let title: String?
    let enableTextSelection: Bool
    let enableSearch: Bool
}

@objc public class PdfAnnotator: NSObject {
    private var fileURL: URL?
    private var options: PdfAnnotatorOptions?
    private weak var viewController: UIViewController?
    private var onDismiss: ((Bool?, String?) -> Void)?
    private var onSaved: ((String, String) -> Void)?
    private var onAnnotationAdded: ((String, Int) -> Void)?
    private var hasSaved: Bool = false
    private var savedPath: String?

    @objc public func openPdf(
        url: String,
        options: PdfAnnotatorOptions,
        from viewController: UIViewController,
        onDismiss: @escaping (Bool?, String?) -> Void,
        onSaved: @escaping (String, String) -> Void,
        onAnnotationAdded: @escaping (String, Int) -> Void
    ) throws {
        // Convert string to URL
        let fileURL: URL
        if url.hasPrefix("file://") {
            guard let parsedURL = URL(string: url) else {
                throw NSError(domain: "PdfAnnotator", code: 1, userInfo: [NSLocalizedDescriptionKey: "Invalid file URL"])
            }
            fileURL = parsedURL
        } else {
            fileURL = URL(fileURLWithPath: url)
        }

        // Check if file exists
        guard FileManager.default.fileExists(atPath: fileURL.path) else {
            throw NSError(domain: "PdfAnnotator", code: 2, userInfo: [NSLocalizedDescriptionKey: "File does not exist at path: \(fileURL.path)"])
        }

        self.fileURL = fileURL
        self.options = options
        self.viewController = viewController
        self.onDismiss = onDismiss
        self.onSaved = onSaved
        self.onAnnotationAdded = onAnnotationAdded
        self.hasSaved = false
        self.savedPath = nil

        DispatchQueue.main.async {
            let previewController = QLPreviewController()
            previewController.dataSource = self
            previewController.delegate = self

            if let title = options.title {
                previewController.title = title
            }

            viewController.present(previewController, animated: true) {
                // Set initial page if specified
                if options.initialPage > 0 {
                    previewController.currentPreviewItemIndex = options.initialPage
                }
            }
        }
    }
}

extension PdfAnnotator: QLPreviewControllerDataSource {
    public func numberOfPreviewItems(in controller: QLPreviewController) -> Int {
        return 1
    }

    public func previewController(_ controller: QLPreviewController,
                                  previewItemAt index: Int) -> QLPreviewItem {
        return fileURL! as NSURL
    }
}

extension PdfAnnotator: QLPreviewControllerDelegate {
    // This method controls whether annotations/editing is enabled
    public func previewController(_ controller: QLPreviewController,
                                  editingModeFor previewItem: QLPreviewItem) -> QLPreviewItemEditingMode {
        guard let options = options else {
            return .disabled
        }
        return options.enableAnnotations ? .updateContents : .disabled
    }

    public func previewController(_ controller: QLPreviewController,
                                  didUpdateContentsOf previewItem: QLPreviewItem) {
        hasSaved = true
        savedPath = previewItem.previewItemURL?.absoluteString
        onSaved?(previewItem.previewItemURL?.absoluteString ?? "", "updated")
        onAnnotationAdded?("ink", controller.currentPreviewItemIndex)
    }

    public func previewController(_ controller: QLPreviewController,
                                  didSaveEditedCopyOf previewItem: QLPreviewItem,
                                  at modifiedContentsURL: URL) {
        hasSaved = true
        savedPath = modifiedContentsURL.absoluteString
        onSaved?(modifiedContentsURL.absoluteString, "copy")
    }

    public func previewControllerDidDismiss(_ controller: QLPreviewController) {
        onDismiss?(hasSaved, savedPath)
    }
}
