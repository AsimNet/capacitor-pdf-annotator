import { WebPlugin } from '@capacitor/core';

import type {
  PdfAnnotatorPlugin,
  OpenPdfOptions,
  OpenPdfResult,
  InkSupportResult,
  ExportAnnotationsOptions,
  ExportAnnotationsResult,
  ImportAnnotationsOptions,
  ImportAnnotationsResult,
  HasAnnotationsOptions,
  HasAnnotationsResult,
  DeleteAnnotationsOptions,
  DeleteAnnotationsResult,
} from './definitions';

export class PdfAnnotatorWeb extends WebPlugin implements PdfAnnotatorPlugin {
  async openPdf(options: OpenPdfOptions): Promise<OpenPdfResult> {
    console.log('PdfAnnotator.openPdf called with:', options);

    // On web, we can open the PDF in a new tab or use pdf.js
    // For now, just open in new tab
    if (options.url) {
      window.open(options.url, '_blank');
    }

    return {
      dismissed: true,
      saved: false,
    };
  }

  async isInkSupported(): Promise<InkSupportResult> {
    // Check for pointer events support (basic touch/stylus support)
    const hasPointerEvents = 'PointerEvent' in window;

    return {
      supported: hasPointerEvents,
      stylusConnected: false,
      lowLatencyInk: false,
    };
  }

  async exportAnnotations(options: ExportAnnotationsOptions): Promise<ExportAnnotationsResult> {
    console.log('PdfAnnotator.exportAnnotations called with:', options);

    // Web platform doesn't persist annotations locally
    // Return empty XFDF document
    const emptyXfdf = `<?xml version="1.0" encoding="UTF-8"?>
<xfdf xmlns="http://ns.adobe.com/xfdf/" xml:space="preserve">
  <annots/>
</xfdf>`;

    return {
      xfdf: emptyXfdf,
    };
  }

  async importAnnotations(options: ImportAnnotationsOptions): Promise<ImportAnnotationsResult> {
    console.log('PdfAnnotator.importAnnotations called with:', options);

    // Web platform doesn't persist annotations locally
    // Just acknowledge the import
    return {
      success: true,
    };
  }

  async hasAnnotations(options: HasAnnotationsOptions): Promise<HasAnnotationsResult> {
    console.log('PdfAnnotator.hasAnnotations called with:', options);

    // Web platform doesn't persist annotations locally
    return {
      hasAnnotations: false,
    };
  }

  async deleteAnnotations(options: DeleteAnnotationsOptions): Promise<DeleteAnnotationsResult> {
    console.log('PdfAnnotator.deleteAnnotations called with:', options);

    // Web platform doesn't persist annotations locally
    return {
      success: true,
    };
  }
}
