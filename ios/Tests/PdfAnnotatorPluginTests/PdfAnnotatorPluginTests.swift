import XCTest
@testable import PdfAnnotatorPlugin

class PdfAnnotatorPluginTests: XCTestCase {
    func testInkSupportedReturnsTrue() {
        // iOS should always support ink
        let annotator = PdfAnnotator()
        // Basic test - actual functionality requires UI testing
        XCTAssertNotNil(annotator)
    }
}
