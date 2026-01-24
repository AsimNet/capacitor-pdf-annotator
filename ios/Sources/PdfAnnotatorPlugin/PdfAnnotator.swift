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
    let primaryColor: String?
    let toolbarColor: String?
}

// MARK: - UIColor Hex Extension
extension UIColor {
    convenience init?(hex: String) {
        var hexSanitized = hex.trimmingCharacters(in: .whitespacesAndNewlines)
        hexSanitized = hexSanitized.replacingOccurrences(of: "#", with: "")

        var rgb: UInt64 = 0
        guard Scanner(string: hexSanitized).scanHexInt64(&rgb) else { return nil }

        let length = hexSanitized.count
        if length == 6 {
            self.init(
                red: CGFloat((rgb & 0xFF0000) >> 16) / 255.0,
                green: CGFloat((rgb & 0x00FF00) >> 8) / 255.0,
                blue: CGFloat(rgb & 0x0000FF) / 255.0,
                alpha: 1.0
            )
        } else if length == 8 {
            self.init(
                red: CGFloat((rgb & 0xFF000000) >> 24) / 255.0,
                green: CGFloat((rgb & 0x00FF0000) >> 16) / 255.0,
                blue: CGFloat((rgb & 0x0000FF00) >> 8) / 255.0,
                alpha: CGFloat(rgb & 0x000000FF) / 255.0
            )
        } else {
            return nil
        }
    }
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

    public func openPdf(
        url: String,
        options: PdfAnnotatorOptions,
        from viewController: UIViewController,
        onDismiss: @escaping (Bool?, String?) -> Void,
        onSaved: @escaping (String, String) -> Void,
        onAnnotationAdded: @escaping (String, Int) -> Void
    ) throws {
        print("[PdfAnnotator] openPdf implementation called")
        print("[PdfAnnotator] URL string: \(url)")

        // Convert string to URL
        let fileURL: URL
        if url.hasPrefix("file://") {
            guard let parsedURL = URL(string: url) else {
                print("[PdfAnnotator] Failed to parse URL string")
                throw NSError(domain: "PdfAnnotator", code: 1, userInfo: [NSLocalizedDescriptionKey: "Invalid file URL"])
            }
            fileURL = parsedURL
            print("[PdfAnnotator] Parsed file URL: \(fileURL)")
        } else {
            fileURL = URL(fileURLWithPath: url)
            print("[PdfAnnotator] Created file URL from path: \(fileURL)")
        }

        // Check if file exists
        let filePath = fileURL.path
        print("[PdfAnnotator] Checking file existence at path: \(filePath)")
        guard FileManager.default.fileExists(atPath: filePath) else {
            print("[PdfAnnotator] File does NOT exist!")
            throw NSError(domain: "PdfAnnotator", code: 2, userInfo: [NSLocalizedDescriptionKey: "File does not exist at path: \(filePath)"])
        }
        print("[PdfAnnotator] File exists, proceeding to open")

        self.fileURL = fileURL
        self.options = options
        self.viewController = viewController
        self.onDismiss = onDismiss
        self.onSaved = onSaved
        self.onAnnotationAdded = onAnnotationAdded
        self.hasSaved = false
        self.savedPath = nil

        DispatchQueue.main.async {
            let tintColor = options.primaryColor.flatMap { UIColor(hex: $0) }

            // Apply tint color via UIAppearance before presenting
            if let tintColor = tintColor {
                UINavigationBar.appearance(whenContainedInInstancesOf: [QLPreviewController.self]).tintColor = tintColor
                UIToolbar.appearance(whenContainedInInstancesOf: [QLPreviewController.self]).tintColor = tintColor
            }

            let previewController = QLPreviewController()
            previewController.dataSource = self
            previewController.delegate = self

            if let title = options.title {
                previewController.title = title
            }

            // Set view tint color (affects all subviews including navigation items)
            if let tintColor = tintColor {
                previewController.view.tintColor = tintColor
            }

            viewController.present(previewController, animated: true) {
                // Also set on navigation controller after presentation
                if let tintColor = tintColor {
                    previewController.navigationController?.navigationBar.tintColor = tintColor
                    previewController.navigationItem.rightBarButtonItem?.tintColor = tintColor
                }

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
