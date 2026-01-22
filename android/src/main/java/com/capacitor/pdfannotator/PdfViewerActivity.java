package com.capacitor.pdfannotator;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.Window;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;

public class PdfViewerActivity extends AppCompatActivity implements InkCanvasView.OnInkChangeListener {

    private static final String TAG = "PdfViewerActivity";

    // Intent extras
    public static final String EXTRA_PDF_URI = "pdf_uri";
    public static final String EXTRA_ENABLE_ANNOTATIONS = "enable_annotations";
    public static final String EXTRA_ENABLE_INK = "enable_ink";
    public static final String EXTRA_INK_COLOR = "ink_color";
    public static final String EXTRA_INK_WIDTH = "ink_width";
    public static final String EXTRA_INITIAL_PAGE = "initial_page";
    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_ENABLE_TEXT_SELECTION = "enable_text_selection";
    public static final String EXTRA_ENABLE_SEARCH = "enable_search";

    // Theme extras
    public static final String EXTRA_PRIMARY_COLOR = "primary_color";
    public static final String EXTRA_TOOLBAR_COLOR = "toolbar_color";
    public static final String EXTRA_STATUS_BAR_COLOR = "status_bar_color";
    public static final String EXTRA_COLOR_PALETTE = "color_palette";

    // Result extras
    public static final String RESULT_SAVED = "saved";
    public static final String RESULT_SAVED_PATH = "saved_path";

    // Views
    private ViewPager2 viewPager;
    private PdfPagerAdapter pagerAdapter;
    private Toolbar toolbar;
    private FloatingActionButton fabDraw;
    private View colorPicker;
    private Menu optionsMenu;

    // Floating toolbox views
    private View floatingToolbox;
    private ImageButton btnBrush;
    private ImageButton btnColor;
    private ImageButton btnSize;
    private ImageButton btnUndo;
    private ImageButton btnRedo;
    private ImageButton btnEraser;
    private ImageButton btnClear;
    private ImageButton btnClose;

    // PDF
    private File pdfFile;
    private Uri pdfUri;
    private String pdfPath;

    // Annotation storage
    private AnnotationStorage annotationStorage;

    // Options
    private boolean enableAnnotations = true;
    private boolean enableInk = true;
    private int currentInkColor = Color.BLACK;
    private float currentInkWidth = 10f; // Medium size default
    private int currentBrushType = AndroidXInkView.BRUSH_PRESSURE_PEN;
    private int initialPage = 0;
    private boolean isDrawingMode = false;
    private boolean isEraserMode = false;

    // Theme colors
    private int primaryColor = 0;
    private int toolbarColor = 0;
    private int statusBarColor = 0;

    // Color palette for ink picker
    private ColorPalette colorPalette;

    // State
    private boolean hasChanges = false;

    // Size options in pixels
    private static final float SIZE_SMALL = 5f;
    private static final float SIZE_MEDIUM = 10f;
    private static final float SIZE_LARGE = 15f;
    private static final float SIZE_XLARGE = 20f;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_pdf_viewer);

        // Initialize annotation storage
        annotationStorage = new AnnotationStorage(this);

        // Get intent extras
        parseIntentExtras();

        // Apply theme colors
        applyThemeColors();

        // Setup views
        setupViews();

        // Load PDF
        loadPdf();
    }

    private void parseIntentExtras() {
        Intent intent = getIntent();
        String uriString = intent.getStringExtra(EXTRA_PDF_URI);
        if (uriString != null) {
            pdfUri = Uri.parse(uriString);
            pdfPath = pdfUri.getPath();
        }

        enableAnnotations = intent.getBooleanExtra(EXTRA_ENABLE_ANNOTATIONS, true);
        enableInk = intent.getBooleanExtra(EXTRA_ENABLE_INK, true);

        String colorString = intent.getStringExtra(EXTRA_INK_COLOR);
        if (colorString != null) {
            try {
                currentInkColor = Color.parseColor(colorString);
            } catch (Exception e) {
                currentInkColor = Color.BLACK;
            }
        }

        currentInkWidth = intent.getFloatExtra(EXTRA_INK_WIDTH, SIZE_MEDIUM);
        initialPage = intent.getIntExtra(EXTRA_INITIAL_PAGE, 0);

        String title = intent.getStringExtra(EXTRA_TITLE);
        if (title != null && !title.isEmpty()) {
            setTitle(title);
        }

        // Parse theme colors
        String primaryColorStr = intent.getStringExtra(EXTRA_PRIMARY_COLOR);
        if (primaryColorStr != null) {
            try {
                primaryColor = Color.parseColor(primaryColorStr);
            } catch (Exception e) {
                primaryColor = 0;
            }
        }

        String toolbarColorStr = intent.getStringExtra(EXTRA_TOOLBAR_COLOR);
        if (toolbarColorStr != null) {
            try {
                toolbarColor = Color.parseColor(toolbarColorStr);
            } catch (Exception e) {
                toolbarColor = 0;
            }
        }

        String statusBarColorStr = intent.getStringExtra(EXTRA_STATUS_BAR_COLOR);
        if (statusBarColorStr != null) {
            try {
                statusBarColor = Color.parseColor(statusBarColorStr);
            } catch (Exception e) {
                statusBarColor = 0;
            }
        }

        // Parse color palette
        String[] paletteStrings = intent.getStringArrayExtra(EXTRA_COLOR_PALETTE);
        if (paletteStrings != null && paletteStrings.length > 0) {
            colorPalette = ColorPalette.fromHexStrings(paletteStrings);
        } else {
            colorPalette = new ColorPalette();
        }

        // Set default ink color from palette
        currentInkColor = colorPalette.getColor(ColorPalette.INDEX_BLACK);
    }

    private void applyThemeColors() {
        // Apply toolbar color
        if (toolbarColor != 0) {
            toolbar = findViewById(R.id.toolbar);
            if (toolbar != null) {
                toolbar.setBackgroundColor(toolbarColor);
            }
        } else if (primaryColor != 0) {
            toolbar = findViewById(R.id.toolbar);
            if (toolbar != null) {
                toolbar.setBackgroundColor(primaryColor);
            }
        }

        // Apply status bar color
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            Window window = getWindow();
            if (statusBarColor != 0) {
                window.setStatusBarColor(statusBarColor);
            } else if (primaryColor != 0) {
                // Darken primary color for status bar
                window.setStatusBarColor(darkenColor(primaryColor, 0.8f));
            }
        }
    }

    private int darkenColor(int color, float factor) {
        int a = Color.alpha(color);
        int r = Math.round(Color.red(color) * factor);
        int g = Math.round(Color.green(color) * factor);
        int b = Math.round(Color.blue(color) * factor);
        return Color.argb(a, Math.min(r, 255), Math.min(g, 255), Math.min(b, 255));
    }

    private void setupViews() {
        toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setDisplayShowHomeEnabled(true);
        }

        viewPager = findViewById(R.id.viewPager);
        fabDraw = findViewById(R.id.fabDraw);
        colorPicker = findViewById(R.id.colorPicker);

        // Setup floating toolbox
        setupFloatingToolbox();

        // Apply primary color to FAB if set
        if (primaryColor != 0) {
            fabDraw.setBackgroundTintList(ColorStateList.valueOf(primaryColor));
        }

        // Setup FAB
        if (enableAnnotations && enableInk) {
            fabDraw.setOnClickListener(v -> toggleDrawingMode());
        } else {
            fabDraw.setVisibility(View.GONE);
        }

        // Setup color picker
        setupColorPicker();
    }

    private void setupFloatingToolbox() {
        floatingToolbox = findViewById(R.id.floatingToolbox);
        btnBrush = findViewById(R.id.btnBrush);
        btnColor = findViewById(R.id.btnColor);
        btnSize = findViewById(R.id.btnSize);
        btnUndo = findViewById(R.id.btnUndo);
        btnRedo = findViewById(R.id.btnRedo);
        btnEraser = findViewById(R.id.btnEraser);
        btnClear = findViewById(R.id.btnClear);
        btnClose = findViewById(R.id.btnClose);

        // Brush type selector
        btnBrush.setOnClickListener(v -> showBrushSelector());

        // Color picker
        btnColor.setOnClickListener(v -> showColorPicker());

        // Size selector
        btnSize.setOnClickListener(v -> showSizeSelector());

        // Undo
        btnUndo.setOnClickListener(v -> undoCurrentPage());

        // Redo
        btnRedo.setOnClickListener(v -> redoCurrentPage());

        // Eraser toggle
        btnEraser.setOnClickListener(v -> toggleEraserMode());

        // Clear all
        btnClear.setOnClickListener(v -> showClearConfirmation());

        // Close drawing mode
        btnClose.setOnClickListener(v -> toggleDrawingMode());
    }

    private void setupColorPicker() {
        View colorBlack = findViewById(R.id.colorBlack);
        View colorRed = findViewById(R.id.colorRed);
        View colorBlue = findViewById(R.id.colorBlue);
        View colorGreen = findViewById(R.id.colorGreen);
        View colorYellow = findViewById(R.id.colorYellow);
        View colorMagenta = findViewById(R.id.colorMagenta);
        View colorGray = findViewById(R.id.colorGray);
        View colorCyan = findViewById(R.id.colorCyan);
        View colorWhite = findViewById(R.id.colorWhite);

        // Use colorPalette for consistent colors between UI and drawing
        View.OnClickListener colorClickListener = v -> {
            int id = v.getId();
            if (id == R.id.colorBlack) currentInkColor = colorPalette.getColor(ColorPalette.INDEX_BLACK);
            else if (id == R.id.colorRed) currentInkColor = colorPalette.getColor(ColorPalette.INDEX_RED);
            else if (id == R.id.colorBlue) currentInkColor = colorPalette.getColor(ColorPalette.INDEX_BLUE);
            else if (id == R.id.colorGreen) currentInkColor = colorPalette.getColor(ColorPalette.INDEX_GREEN);
            else if (id == R.id.colorYellow) currentInkColor = colorPalette.getColor(ColorPalette.INDEX_YELLOW);
            else if (id == R.id.colorMagenta) currentInkColor = colorPalette.getColor(ColorPalette.INDEX_PINK);
            else if (id == R.id.colorGray) currentInkColor = colorPalette.getColor(ColorPalette.INDEX_GRAY);
            else if (id == R.id.colorCyan) currentInkColor = colorPalette.getColor(ColorPalette.INDEX_CYAN);
            else if (id == R.id.colorWhite) currentInkColor = colorPalette.getColor(ColorPalette.INDEX_WHITE);

            // Exit eraser mode when selecting color
            if (isEraserMode) {
                isEraserMode = false;
                btnEraser.setAlpha(1.0f);
            }

            updateInkColor();
            colorPicker.setVisibility(View.GONE);
        };

        if (colorBlack != null) colorBlack.setOnClickListener(colorClickListener);
        if (colorRed != null) colorRed.setOnClickListener(colorClickListener);
        if (colorBlue != null) colorBlue.setOnClickListener(colorClickListener);
        if (colorGreen != null) colorGreen.setOnClickListener(colorClickListener);
        if (colorYellow != null) colorYellow.setOnClickListener(colorClickListener);
        if (colorMagenta != null) colorMagenta.setOnClickListener(colorClickListener);
        if (colorGray != null) colorGray.setOnClickListener(colorClickListener);
        if (colorCyan != null) colorCyan.setOnClickListener(colorClickListener);
        if (colorWhite != null) colorWhite.setOnClickListener(colorClickListener);
    }

    private void showBrushSelector() {
        // Hide color picker if visible
        colorPicker.setVisibility(View.GONE);

        View popupView = getLayoutInflater().inflate(R.layout.dialog_brush_selector, null);

        // Measure the view to get its dimensions
        popupView.measure(
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        );

        PopupWindow popup = new PopupWindow(
                popupView,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                true
        );

        popup.setElevation(16f);
        popup.setOutsideTouchable(true);

        popupView.findViewById(R.id.brushPressurePen).setOnClickListener(v -> {
            selectBrushType(AndroidXInkView.BRUSH_PRESSURE_PEN);
            popup.dismiss();
        });

        popupView.findViewById(R.id.brushMarker).setOnClickListener(v -> {
            selectBrushType(AndroidXInkView.BRUSH_MARKER);
            popup.dismiss();
        });

        popupView.findViewById(R.id.brushHighlighter).setOnClickListener(v -> {
            selectBrushType(AndroidXInkView.BRUSH_HIGHLIGHTER);
            popup.dismiss();
        });

        popupView.findViewById(R.id.brushDashedLine).setOnClickListener(v -> {
            selectBrushType(AndroidXInkView.BRUSH_DASHED_LINE);
            popup.dismiss();
        });

        // Show popup to the left of the toolbox
        int[] location = new int[2];
        btnBrush.getLocationOnScreen(location);
        int popupWidth = popupView.getMeasuredWidth();

        popup.showAsDropDown(btnBrush, -(popupWidth + 16), -btnBrush.getHeight() / 2, Gravity.START | Gravity.TOP);
    }

    private void selectBrushType(int brushType) {
        currentBrushType = brushType;
        if (pagerAdapter != null) {
            pagerAdapter.setBrushType(brushType);
        }

        // Auto-set size to Large for highlighter
        if (brushType == AndroidXInkView.BRUSH_HIGHLIGHTER) {
            currentInkWidth = SIZE_LARGE; // 15f
            if (pagerAdapter != null) {
                pagerAdapter.setInkWidth(SIZE_LARGE);
            }
        }

        // Exit eraser mode when selecting brush
        if (isEraserMode) {
            isEraserMode = false;
            btnEraser.setBackground(null);
            if (pagerAdapter != null) {
                pagerAdapter.setEraserMode(false);
            }
        }

        // Show brush type feedback
        String brushName;
        switch (brushType) {
            case AndroidXInkView.BRUSH_MARKER:
                brushName = getString(R.string.marker);
                break;
            case AndroidXInkView.BRUSH_HIGHLIGHTER:
                brushName = getString(R.string.highlighter);
                break;
            case AndroidXInkView.BRUSH_DASHED_LINE:
                brushName = getString(R.string.dashed_line);
                break;
            default:
                brushName = getString(R.string.pressure_pen);
                break;
        }
        Toast.makeText(this, brushName, Toast.LENGTH_SHORT).show();
    }

    private void showSizeSelector() {
        // Hide color picker if visible
        colorPicker.setVisibility(View.GONE);

        View popupView = getLayoutInflater().inflate(R.layout.dialog_size_selector, null);

        // Measure the view to get its dimensions
        popupView.measure(
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        );

        PopupWindow popup = new PopupWindow(
                popupView,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                true
        );

        popup.setElevation(16f);
        popup.setOutsideTouchable(true);

        popupView.findViewById(R.id.sizeSmall).setOnClickListener(v -> {
            selectSize(SIZE_SMALL);
            popup.dismiss();
        });

        popupView.findViewById(R.id.sizeMedium).setOnClickListener(v -> {
            selectSize(SIZE_MEDIUM);
            popup.dismiss();
        });

        popupView.findViewById(R.id.sizeLarge).setOnClickListener(v -> {
            selectSize(SIZE_LARGE);
            popup.dismiss();
        });

        popupView.findViewById(R.id.sizeXLarge).setOnClickListener(v -> {
            selectSize(SIZE_XLARGE);
            popup.dismiss();
        });

        // Show popup to the left of the toolbox
        int popupWidth = popupView.getMeasuredWidth();

        popup.showAsDropDown(btnSize, -(popupWidth + 16), -btnSize.getHeight() / 2, Gravity.START | Gravity.TOP);
    }

    private void selectSize(float size) {
        currentInkWidth = size;
        if (pagerAdapter != null) {
            pagerAdapter.setInkWidth(size);
        }
    }

    private void toggleEraserMode() {
        isEraserMode = !isEraserMode;

        // Update eraser button visual state
        if (isEraserMode) {
            btnEraser.setBackgroundResource(R.drawable.bg_tool_active);
        } else {
            btnEraser.setBackground(null);
        }

        // Propagate eraser mode to the adapter
        if (pagerAdapter != null) {
            pagerAdapter.setEraserMode(isEraserMode);
        }

        if (isEraserMode) {
            Toast.makeText(this, R.string.eraser, Toast.LENGTH_SHORT).show();
        }
    }

    private void showClearConfirmation() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.clear)
                .setMessage(R.string.clear_all_annotations)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> clearAllAnnotations())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void loadPdf() {
        if (pdfPath == null) {
            Toast.makeText(this, "Invalid PDF path", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        new Thread(() -> {
            try {
                pdfFile = new File(pdfPath);

                // Load saved annotations if any
                Map<Integer, List<InkCanvasView.InkStroke>> savedAnnotations =
                        annotationStorage.loadAnnotations(pdfPath);

                runOnUiThread(() -> {
                    try {
                        // Use native PdfRenderer via PdfPagerAdapter for display
                        pagerAdapter = new PdfPagerAdapter(this, pdfFile, enableInk);
                        pagerAdapter.setInkColor(currentInkColor);
                        pagerAdapter.setInkWidth(currentInkWidth);
                        pagerAdapter.setBrushType(currentBrushType);
                        pagerAdapter.setDrawingEnabled(isDrawingMode);
                        pagerAdapter.setOnInkChangeListener(this);

                        // Load saved annotations
                        if (!savedAnnotations.isEmpty()) {
                            pagerAdapter.loadStrokes(savedAnnotations);
                            Log.d(TAG, "Loaded " + savedAnnotations.size() + " pages of annotations");
                        }

                        viewPager.setAdapter(pagerAdapter);
                        viewPager.setCurrentItem(initialPage, false);

                        // Update title with page info
                        updatePageTitle();

                        // Update menu state
                        updateMenuState();

                        viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
                            @Override
                            public void onPageSelected(int position) {
                                updatePageTitle();
                                // Update undo/redo state for new page
                                updateMenuState();
                                updateToolboxState();
                            }
                        });
                    } catch (IOException e) {
                        Log.e(TAG, "Error creating PDF adapter", e);
                        Toast.makeText(this, "Error loading PDF: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                        finish();
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Error loading PDF", e);
                runOnUiThread(() -> {
                    Toast.makeText(this, "Error loading PDF: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    finish();
                });
            }
        }).start();
    }

    private void updatePageTitle() {
        if (pagerAdapter != null) {
            int currentPage = viewPager.getCurrentItem() + 1;
            int totalPages = pagerAdapter.getPageCount();
            String title = getIntent().getStringExtra(EXTRA_TITLE);
            if (title == null) title = "PDF";
            setTitle(title + " - " + currentPage + "/" + totalPages);
        }
    }

    private void toggleDrawingMode() {
        isDrawingMode = !isDrawingMode;
        pagerAdapter.setDrawingEnabled(isDrawingMode);

        // Disable/enable page swiping based on drawing mode
        viewPager.setUserInputEnabled(!isDrawingMode);

        if (isDrawingMode) {
            fabDraw.setVisibility(View.GONE);
            floatingToolbox.setVisibility(View.VISIBLE);
            Toast.makeText(this, R.string.drawing_mode_enabled, Toast.LENGTH_SHORT).show();
        } else {
            fabDraw.setVisibility(View.VISIBLE);
            floatingToolbox.setVisibility(View.GONE);
            colorPicker.setVisibility(View.GONE);
            Toast.makeText(this, R.string.drawing_mode_disabled, Toast.LENGTH_SHORT).show();

            // Auto-save when exiting drawing mode if there are changes
            if (hasChanges) {
                saveAnnotationsAsync(false);
            }
        }

        // Update menu visibility
        updateMenuState();
        updateToolboxState();
    }

    private void showColorPicker() {
        colorPicker.setVisibility(
                colorPicker.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE
        );
    }

    private void updateInkColor() {
        if (pagerAdapter != null) {
            pagerAdapter.setInkColor(currentInkColor);
        }
    }

    private void saveAnnotations() {
        saveAnnotationsAsync(true);
    }

    private void saveAnnotationsAsync(boolean showToast) {
        if (pagerAdapter == null) return;

        // Check if there are any annotations to save
        Map<Integer, List<InkCanvasView.InkStroke>> strokesByPage = pagerAdapter.getStrokesByPage();

        if (strokesByPage.isEmpty() && !annotationStorage.hasAnnotations(pdfPath)) {
            if (showToast) {
                Toast.makeText(this, R.string.no_changes_to_save, Toast.LENGTH_SHORT).show();
            }
            return;
        }

        new Thread(() -> {
            boolean success;
            if (strokesByPage.isEmpty()) {
                // No strokes, delete the annotation file
                success = annotationStorage.deleteAnnotations(pdfPath);
            } else {
                // Save strokes to JSON file
                success = annotationStorage.saveAnnotations(pdfPath, strokesByPage);
            }

            runOnUiThread(() -> {
                if (success) {
                    if (showToast) {
                        Toast.makeText(this, R.string.annotations_saved, Toast.LENGTH_SHORT).show();
                    }
                    hasChanges = false;

                    // Update result
                    Intent resultIntent = new Intent();
                    resultIntent.putExtra(RESULT_SAVED, true);
                    resultIntent.putExtra(RESULT_SAVED_PATH, pdfPath);
                    setResult(RESULT_OK, resultIntent);
                } else {
                    if (showToast) {
                        Toast.makeText(this, R.string.error_saving, Toast.LENGTH_SHORT).show();
                    }
                }
            });
        }).start();
    }

    @Override
    public void onInkChanged() {
        hasChanges = true;
        updateMenuState();
        updateToolboxState();
    }

    private void updateToolboxState() {
        if (btnUndo == null || btnRedo == null || pagerAdapter == null) return;

        int currentPage = viewPager != null ? viewPager.getCurrentItem() : 0;

        // Update undo button state
        boolean canUndo = pagerAdapter.canUndoPage(currentPage);
        btnUndo.setAlpha(canUndo ? 1.0f : 0.3f);
        btnUndo.setEnabled(canUndo);

        // Update redo button state
        boolean canRedo = pagerAdapter.canRedoPage(currentPage);
        btnRedo.setAlpha(canRedo ? 1.0f : 0.3f);
        btnRedo.setEnabled(canRedo);

        // Update clear button state
        boolean hasStrokes = pagerAdapter.hasAnyStrokes();
        btnClear.setAlpha(hasStrokes ? 1.0f : 0.3f);
        btnClear.setEnabled(hasStrokes);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_pdf_viewer, menu);
        optionsMenu = menu;
        updateMenuState();
        return true;
    }

    private void updateMenuState() {
        if (optionsMenu == null) return;

        MenuItem undoItem = optionsMenu.findItem(R.id.action_undo);
        MenuItem redoItem = optionsMenu.findItem(R.id.action_redo);
        MenuItem clearItem = optionsMenu.findItem(R.id.action_clear);

        int currentPage = viewPager != null ? viewPager.getCurrentItem() : 0;

        // Hide menu items when using floating toolbox in drawing mode
        if (undoItem != null) {
            undoItem.setVisible(false); // Using floating toolbox instead
        }
        if (redoItem != null) {
            redoItem.setVisible(false); // Using floating toolbox instead
        }

        // Clear only enabled when there are annotations and not in drawing mode
        if (clearItem != null) {
            boolean hasAnnotations = pagerAdapter != null && pagerAdapter.hasAnyStrokes();
            clearItem.setVisible(enableAnnotations && !isDrawingMode);
            clearItem.setEnabled(hasAnnotations);
        }
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == android.R.id.home) {
            onBackPressed();
            return true;
        } else if (id == R.id.action_undo) {
            undoCurrentPage();
            return true;
        } else if (id == R.id.action_redo) {
            redoCurrentPage();
            return true;
        } else if (id == R.id.action_clear) {
            showClearConfirmation();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void undoCurrentPage() {
        if (pagerAdapter != null) {
            int currentPage = viewPager.getCurrentItem();
            pagerAdapter.undoPage(currentPage);
            hasChanges = true;
            updateMenuState();
            updateToolboxState();
        }
    }

    private void redoCurrentPage() {
        if (pagerAdapter != null) {
            int currentPage = viewPager.getCurrentItem();
            pagerAdapter.redoPage(currentPage);
            hasChanges = true;
            updateMenuState();
            updateToolboxState();
        }
    }

    private void clearAllAnnotations() {
        if (pagerAdapter != null) {
            pagerAdapter.clearAllPages();
            hasChanges = true;
            updateMenuState();
            updateToolboxState();
            Toast.makeText(this, R.string.annotations_cleared, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onBackPressed() {
        if (isDrawingMode) {
            // Exit drawing mode first
            toggleDrawingMode();
            return;
        }

        if (hasChanges) {
            // Auto-save annotations silently
            saveAnnotationsAsync(false);
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        // Clean up PDF adapter (closes native PdfRenderer)
        if (pagerAdapter != null) {
            pagerAdapter.cleanup();
        }
    }
}
