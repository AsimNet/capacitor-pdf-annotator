export interface PdfAnnotatorPlugin {
  /**
   * Open a PDF file for viewing and annotation
   * @param options - Options for opening the PDF
   * @returns Promise that resolves when the PDF viewer is dismissed
   */
  openPdf(options: OpenPdfOptions): Promise<OpenPdfResult>;

  /**
   * Check if the device supports ink/stylus annotations
   * @returns Promise with support information
   */
  isInkSupported(): Promise<InkSupportResult>;

  /**
   * Add a listener for PDF events
   * @param eventName - Name of the event to listen for
   * @param listenerFunc - Callback function
   */
  addListener(
    eventName: 'pdfSaved',
    listenerFunc: (event: PdfSavedEvent) => void,
  ): Promise<PluginListenerHandle>;

  /**
   * Add a listener for annotation events
   * @param eventName - Name of the event to listen for
   * @param listenerFunc - Callback function
   */
  addListener(
    eventName: 'annotationAdded',
    listenerFunc: (event: AnnotationEvent) => void,
  ): Promise<PluginListenerHandle>;

  /**
   * Remove all listeners for this plugin
   */
  removeAllListeners(): Promise<void>;
}

export interface OpenPdfOptions {
  /**
   * The file URL or path to the PDF document
   * Can be a file:// URL or absolute path
   */
  url: string;

  /**
   * Enable saving annotations to the PDF
   * @default true
   */
  enableAnnotations?: boolean;

  /**
   * Enable ink/drawing annotations
   * @default true
   */
  enableInk?: boolean;

  /**
   * Default ink color in hex format
   * @default '#000000'
   */
  inkColor?: string;

  /**
   * Default ink stroke width in points
   * @default 2.0
   */
  inkWidth?: number;

  /**
   * Page number to open (0-indexed)
   * @default 0
   */
  initialPage?: number;

  /**
   * Title to display in the viewer toolbar
   */
  title?: string;

  /**
   * Enable text selection
   * @default true
   */
  enableTextSelection?: boolean;

  /**
   * Enable search functionality
   * @default true
   */
  enableSearch?: boolean;
}

export interface OpenPdfResult {
  /**
   * Whether the PDF viewer was dismissed
   */
  dismissed: boolean;

  /**
   * Whether annotations were saved
   */
  saved?: boolean;

  /**
   * Path to the saved PDF (if different from original)
   */
  savedPath?: string;
}

export interface InkSupportResult {
  /**
   * Whether ink/stylus input is supported
   */
  supported: boolean;

  /**
   * Whether a stylus is currently connected
   */
  stylusConnected?: boolean;

  /**
   * Whether low-latency ink is available (Android only)
   */
  lowLatencyInk?: boolean;
}

export interface PdfSavedEvent {
  /**
   * Path to the saved PDF file
   */
  path: string;

  /**
   * Type of save operation
   */
  type: 'updated' | 'copy';
}

export interface AnnotationEvent {
  /**
   * Type of annotation
   */
  type: 'ink' | 'text' | 'highlight';

  /**
   * Page number where annotation was added
   */
  page: number;
}

export interface PluginListenerHandle {
  remove: () => Promise<void>;
}
