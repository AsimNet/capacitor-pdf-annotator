# Capacitor PDF Annotator

[![npm version](https://img.shields.io/npm/v/capacitor-pdf-annotator.svg)](https://www.npmjs.com/package/capacitor-pdf-annotator)
[![npm downloads](https://img.shields.io/npm/dm/capacitor-pdf-annotator.svg)](https://www.npmjs.com/package/capacitor-pdf-annotator)
[![License](https://img.shields.io/npm/l/capacitor-pdf-annotator.svg)](https://github.com/AsimNet/capacitor-pdf-annotator/blob/main/LICENSE)
[![Ko-Fi](https://img.shields.io/badge/Ko--fi-Support%20Me-ff5e5b?logo=ko-fi&logoColor=white)](https://ko-fi.com/asimnet)

A Capacitor plugin for viewing and annotating PDF documents with stylus/pen support on iOS and Android.

## Screenshots

### Android
<p align="center">
  <img src="screenshots/android/choose_color.png" width="250" alt="Color Picker" />
  <img src="screenshots/android/choose_brush.png" width="250" alt="Brush Selection" />
</p>

### iOS
<p align="center">
  <img src="screenshots/ios/pencil_kit.png" width="250" alt="PencilKit Integration" />
</p>

## Features

- **PDF Viewing**: Native PDF rendering on both platforms
- **Ink Annotations**: Draw with stylus or finger with pressure sensitivity
- **Multiple Brush Types**: Pressure Pen, Marker, Highlighter, and Dashed Line
- **Multiple Colors**: Customizable color palette with 9 color options
- **Adjustable Stroke Width**: Customize pen thickness
- **Dark Mode Support**: Automatic dark mode for toolbar and dialogs
- **Zoom & Pan**: Smooth zoom and pan gestures with improved UX
- **Auto-Save**: Annotations are automatically saved to the PDF
- **Undo/Redo**: Full undo/redo support for annotations
- **Theming**: Customize primary color, toolbar color, and status bar color
- **Bilingual**: Supports English and Arabic (RTL)

## Platforms

| Platform | Implementation |
|----------|---------------|
| iOS | QLPreviewController + PencilKit |
| Android | AndroidX PDF Viewer + Ink API + PdfBox |
| Web | Fallback (opens in new tab) |

## Requirements

- **iOS**: 14.0+
- **Android**: API 30+ (Android 11+)
- **Capacitor**: 6.0+

## Installation

```bash
npm install capacitor-pdf-annotator
npx cap sync
```

## Usage

### Basic Usage

```typescript
import { PdfAnnotator } from 'capacitor-pdf-annotator';

// Open a PDF file
await PdfAnnotator.openPdf({
  url: 'file:///path/to/document.pdf'
});
```

### With Annotations and Theming

```typescript
import { PdfAnnotator } from 'capacitor-pdf-annotator';

await PdfAnnotator.openPdf({
  url: 'file:///path/to/document.pdf',
  title: 'My Document',
  enableAnnotations: true,
  enableInk: true,
  inkColor: '#2196F3',  // Blue
  inkWidth: 3.0,
  primaryColor: '#1C8354',  // Green theme
  toolbarColor: '#1C8354',
  initialPage: 0
});
```

### With Custom Color Palette

```typescript
import { PdfAnnotator } from 'capacitor-pdf-annotator';

await PdfAnnotator.openPdf({
  url: 'file:///path/to/document.pdf',
  enableInk: true,
  // Custom 9-color palette (Android only)
  colorPalette: [
    '#000000', // Black
    '#F44336', // Red
    '#2196F3', // Blue
    '#4CAF50', // Green
    '#FFEB3B', // Yellow
    '#E91E63', // Pink
    '#9E9E9E', // Gray
    '#00BCD4', // Cyan
    '#FFFFFF'  // White
  ]
});
```

### Check Ink Support

```typescript
import { PdfAnnotator } from 'capacitor-pdf-annotator';

const result = await PdfAnnotator.isInkSupported();
console.log('Ink supported:', result.supported);
console.log('Stylus connected:', result.stylusConnected);
console.log('Low-latency ink:', result.lowLatencyInk); // Android only
```

### Listen for Events

```typescript
import { PdfAnnotator } from 'capacitor-pdf-annotator';

// Listen for save events
PdfAnnotator.addListener('pdfSaved', (event) => {
  console.log('PDF saved:', event.path);
  console.log('Save type:', event.type); // 'updated' or 'copy'
});

// Listen for annotation events
PdfAnnotator.addListener('annotationAdded', (event) => {
  console.log('Annotation type:', event.type); // 'ink', 'text', or 'highlight'
  console.log('Page:', event.page);
});

// Clean up listeners when done
await PdfAnnotator.removeAllListeners();
```

## API

### openPdf(options)

Opens a PDF document for viewing and annotation.

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `url` | `string` | required | URL or file path to the PDF |
| `title` | `string` | `undefined` | Title shown in the toolbar |
| `enableAnnotations` | `boolean` | `true` | Enable annotation tools |
| `enableInk` | `boolean` | `true` | Enable ink/drawing tools |
| `inkColor` | `string` | `'#000000'` | Default ink color (hex) |
| `inkWidth` | `number` | `2.0` | Default ink stroke width |
| `initialPage` | `number` | `0` | Initial page to display (0-indexed) |
| `enableTextSelection` | `boolean` | `true` | Enable text selection |
| `enableSearch` | `boolean` | `true` | Enable search functionality |
| `primaryColor` | `string` | `undefined` | Primary theme color (hex) |
| `toolbarColor` | `string` | `undefined` | Toolbar background color (hex) |
| `statusBarColor` | `string` | `undefined` | Status bar color - Android only (hex) |
| `colorPalette` | `string[]` | Material colors | Custom color palette (max 9) - Android only |

**Returns:** `Promise<OpenPdfResult>`

```typescript
interface OpenPdfResult {
  dismissed: boolean;    // Whether the viewer was dismissed
  saved?: boolean;       // Whether annotations were saved
  savedPath?: string;    // Path to saved PDF (if different from original)
}
```

### isInkSupported()

Checks if ink annotations are supported on the current device.

**Returns:** `Promise<InkSupportResult>`

```typescript
interface InkSupportResult {
  supported: boolean;        // Whether ink input is supported
  stylusConnected?: boolean; // Whether a stylus is connected
  lowLatencyInk?: boolean;   // Whether low-latency ink is available (Android)
}
```

## Events

### pdfSaved

Fired when the PDF is saved.

```typescript
interface PdfSavedEvent {
  path: string;              // Path to the saved PDF file
  type: 'updated' | 'copy';  // Type of save operation
}
```

### annotationAdded

Fired when an annotation is added.

```typescript
interface AnnotationEvent {
  type: 'ink' | 'text' | 'highlight';  // Type of annotation
  page: number;                         // Page number
}
```

## Android Configuration

The plugin uses AndroidX PDF Viewer and Ink API. Add the following to your `android/app/build.gradle` if needed:

```groovy
android {
    defaultConfig {
        minSdkVersion 30
    }
}
```

## iOS Configuration

The plugin uses QLPreviewController with PencilKit. No additional configuration required.

Add to `Info.plist` if loading files from specific locations:

```xml
<key>UISupportsDocumentBrowser</key>
<true/>
```

## Localization

The plugin supports both English and Arabic. Language is automatically detected from device settings.

## Support

If you find this plugin helpful, consider supporting the development:

[![Ko-Fi](https://img.shields.io/badge/Ko--fi-Support%20Me-ff5e5b?logo=ko-fi&logoColor=white)](https://ko-fi.com/asimnet)

## License

Apache 2.0

## Contributors

- [Nasser](https://github.com/newer97) - iOS implementation (QLPreviewController + PencilKit)

## Credits

- [AndroidX PDF Viewer](https://developer.android.com/jetpack/androidx/releases/pdf)
- [AndroidX Ink API](https://developer.android.com/develop/ui/views/touch-and-input/stylus-input/about-ink-api)
- [PdfBox-Android](https://github.com/TomRoush/PdfBox-Android)
