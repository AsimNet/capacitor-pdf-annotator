import { WebPlugin } from '@capacitor/core';

import type {
  PdfAnnotatorPlugin,
  OpenPdfOptions,
  OpenPdfResult,
  InkSupportResult,
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
}
