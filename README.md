# Capacitor PDF Annotator

A Capacitor plugin for viewing and annotating PDF documents with stylus/pen support on iOS and Android.

## Features

- **PDF Viewing**: Native PDF rendering on both platforms
- **Ink Annotations**: Draw with stylus or finger
- **Multiple Colors**: Red, Blue, Green, Yellow, Magenta, and Black ink colors
- **Adjustable Stroke Width**: Customize pen thickness
- **Auto-Save**: Annotations are automatically saved to the PDF
- **Undo/Redo**: Full undo/redo support for annotations
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

### With Annotations Enabled

```typescript
import { PdfAnnotator } from 'capacitor-pdf-annotator';

await PdfAnnotator.openPdf({
  url: 'file:///path/to/document.pdf',
  enableAnnotations: true,
  enableInk: true,
  inkColor: '#2196F3',  // Blue
  inkWidth: 3.0
});
```

### Check Ink Support

```typescript
import { PdfAnnotator } from 'capacitor-pdf-annotator';

const result = await PdfAnnotator.isInkSupported();
if (result.supported) {
  console.log('Ink annotations are supported!');
}
```

### Listen for Events

```typescript
import { PdfAnnotator } from 'capacitor-pdf-annotator';

// Listen for save events
PdfAnnotator.addListener('pdfSaved', (event) => {
  console.log('PDF saved:', event.url);
  console.log('Has annotations:', event.hasAnnotations);
});

// Listen for annotation events
PdfAnnotator.addListener('annotationAdded', (event) => {
  console.log('Annotation added on page:', event.pageIndex);
  console.log('Annotation type:', event.type);
});
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
| `autoSave` | `boolean` | `true` | Auto-save annotations |

### isInkSupported()

Checks if ink annotations are supported on the current device.

Returns: `Promise<{ supported: boolean }>`

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

## License

Apache 2.0

## Contributors

- [Nasser](https://github.com/newer97) - iOS implementation (QLPreviewController + PencilKit)

## Credits

- [AndroidX PDF Viewer](https://developer.android.com/jetpack/androidx/releases/pdf)
- [AndroidX Ink API](https://developer.android.com/develop/ui/views/touch-and-input/stylus-input/about-ink-api)
- [PdfBox-Android](https://github.com/TomRoush/PdfBox-Android)
