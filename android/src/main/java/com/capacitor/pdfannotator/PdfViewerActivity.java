package com.capacitor.pdfannotator;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDPage;
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream;
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle;
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationMarkup;
import com.tom_roush.pdfbox.rendering.PDFRenderer;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

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

    // Result extras
    public static final String RESULT_SAVED = "saved";
    public static final String RESULT_SAVED_PATH = "saved_path";

    // Views
    private ViewPager2 viewPager;
    private PdfPagerAdapter pagerAdapter;
    private Toolbar toolbar;
    private FloatingActionButton fabDraw;
    private FloatingActionButton fabColor;
    private FloatingActionButton fabSave;
    private View colorPicker;

    // PDF
    private PDDocument document;
    private PDFRenderer pdfRenderer;
    private Uri pdfUri;
    private String pdfPath;

    // Options
    private boolean enableAnnotations = true;
    private boolean enableInk = true;
    private int currentInkColor = Color.BLACK;
    private float currentInkWidth = 5f;
    private int initialPage = 0;
    private boolean isDrawingMode = false;

    // State
    private boolean hasChanges = false;
    private List<InkCanvasView> inkCanvasViews = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_pdf_viewer);

        // Initialize PDFBox
        PDFBoxResourceLoader.init(getApplicationContext());

        // Get intent extras
        parseIntentExtras();

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

        currentInkWidth = intent.getFloatExtra(EXTRA_INK_WIDTH, 5f);
        initialPage = intent.getIntExtra(EXTRA_INITIAL_PAGE, 0);

        String title = intent.getStringExtra(EXTRA_TITLE);
        if (title != null && !title.isEmpty()) {
            setTitle(title);
        }
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
        fabColor = findViewById(R.id.fabColor);
        fabSave = findViewById(R.id.fabSave);
        colorPicker = findViewById(R.id.colorPicker);

        // Setup FABs
        if (enableAnnotations && enableInk) {
            fabDraw.setOnClickListener(v -> toggleDrawingMode());
            fabColor.setOnClickListener(v -> showColorPicker());
            fabSave.setOnClickListener(v -> saveAnnotations());
        } else {
            fabDraw.setVisibility(View.GONE);
            fabColor.setVisibility(View.GONE);
            fabSave.setVisibility(View.GONE);
        }

        // Setup color picker
        setupColorPicker();
    }

    private void setupColorPicker() {
        int[] colors = {
                Color.BLACK,
                Color.RED,
                Color.BLUE,
                Color.GREEN,
                Color.YELLOW,
                Color.MAGENTA
        };

        View colorBlack = findViewById(R.id.colorBlack);
        View colorRed = findViewById(R.id.colorRed);
        View colorBlue = findViewById(R.id.colorBlue);
        View colorGreen = findViewById(R.id.colorGreen);
        View colorYellow = findViewById(R.id.colorYellow);
        View colorMagenta = findViewById(R.id.colorMagenta);

        View.OnClickListener colorClickListener = v -> {
            int id = v.getId();
            if (id == R.id.colorBlack) currentInkColor = Color.BLACK;
            else if (id == R.id.colorRed) currentInkColor = Color.RED;
            else if (id == R.id.colorBlue) currentInkColor = Color.BLUE;
            else if (id == R.id.colorGreen) currentInkColor = Color.GREEN;
            else if (id == R.id.colorYellow) currentInkColor = Color.YELLOW;
            else if (id == R.id.colorMagenta) currentInkColor = Color.MAGENTA;

            updateInkColor();
            colorPicker.setVisibility(View.GONE);
        };

        if (colorBlack != null) colorBlack.setOnClickListener(colorClickListener);
        if (colorRed != null) colorRed.setOnClickListener(colorClickListener);
        if (colorBlue != null) colorBlue.setOnClickListener(colorClickListener);
        if (colorGreen != null) colorGreen.setOnClickListener(colorClickListener);
        if (colorYellow != null) colorYellow.setOnClickListener(colorClickListener);
        if (colorMagenta != null) colorMagenta.setOnClickListener(colorClickListener);
    }

    private void loadPdf() {
        if (pdfPath == null) {
            Toast.makeText(this, "Invalid PDF path", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        new Thread(() -> {
            try {
                File file = new File(pdfPath);
                document = PDDocument.load(file);
                pdfRenderer = new PDFRenderer(document);

                int pageCount = document.getNumberOfPages();

                runOnUiThread(() -> {
                    pagerAdapter = new PdfPagerAdapter(this, pdfRenderer, pageCount, enableInk);
                    pagerAdapter.setInkColor(currentInkColor);
                    pagerAdapter.setInkWidth(currentInkWidth);
                    pagerAdapter.setDrawingEnabled(isDrawingMode);
                    pagerAdapter.setOnInkChangeListener(this);

                    viewPager.setAdapter(pagerAdapter);
                    viewPager.setCurrentItem(initialPage, false);

                    // Update title with page info
                    updatePageTitle();

                    viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
                        @Override
                        public void onPageSelected(int position) {
                            updatePageTitle();
                        }
                    });
                });
            } catch (IOException e) {
                Log.e(TAG, "Error loading PDF", e);
                runOnUiThread(() -> {
                    Toast.makeText(this, "Error loading PDF: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    finish();
                });
            }
        }).start();
    }

    private void updatePageTitle() {
        if (document != null) {
            int currentPage = viewPager.getCurrentItem() + 1;
            int totalPages = document.getNumberOfPages();
            String title = getIntent().getStringExtra(EXTRA_TITLE);
            if (title == null) title = "PDF";
            setTitle(title + " - " + currentPage + "/" + totalPages);
        }
    }

    private void toggleDrawingMode() {
        isDrawingMode = !isDrawingMode;
        pagerAdapter.setDrawingEnabled(isDrawingMode);

        if (isDrawingMode) {
            fabDraw.setImageResource(R.drawable.ic_edit_off);
            fabColor.setVisibility(View.VISIBLE);
            Toast.makeText(this, "Drawing mode enabled", Toast.LENGTH_SHORT).show();
        } else {
            fabDraw.setImageResource(R.drawable.ic_edit);
            fabColor.setVisibility(View.GONE);
            colorPicker.setVisibility(View.GONE);
            Toast.makeText(this, "Drawing mode disabled", Toast.LENGTH_SHORT).show();
        }
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
        if (!hasChanges) {
            Toast.makeText(this, "No changes to save", Toast.LENGTH_SHORT).show();
            return;
        }

        new Thread(() -> {
            try {
                // Save ink strokes to PDF using PdfBox
                if (pagerAdapter != null) {
                    List<InkCanvasView.InkStroke> allStrokes = pagerAdapter.getAllStrokes();
                    // TODO: Convert strokes to PDF annotations
                    // This requires converting canvas coordinates to PDF coordinates
                }

                document.save(pdfPath);

                runOnUiThread(() -> {
                    Toast.makeText(this, "Annotations saved", Toast.LENGTH_SHORT).show();
                    hasChanges = false;

                    // Update result
                    Intent resultIntent = new Intent();
                    resultIntent.putExtra(RESULT_SAVED, true);
                    resultIntent.putExtra(RESULT_SAVED_PATH, pdfPath);
                    setResult(RESULT_OK, resultIntent);
                });
            } catch (IOException e) {
                Log.e(TAG, "Error saving PDF", e);
                runOnUiThread(() -> {
                    Toast.makeText(this, "Error saving: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        }).start();
    }

    @Override
    public void onInkChanged() {
        hasChanges = true;
        fabSave.setVisibility(View.VISIBLE);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_pdf_viewer, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == android.R.id.home) {
            onBackPressed();
            return true;
        } else if (id == R.id.action_clear) {
            clearCurrentPage();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void clearCurrentPage() {
        if (pagerAdapter != null) {
            pagerAdapter.clearPage(viewPager.getCurrentItem());
            hasChanges = true;
        }
    }

    @Override
    public void onBackPressed() {
        if (hasChanges) {
            // Show save prompt
            new androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Unsaved Changes")
                    .setMessage("Do you want to save your annotations?")
                    .setPositiveButton("Save", (dialog, which) -> {
                        saveAnnotations();
                        finish();
                    })
                    .setNegativeButton("Discard", (dialog, which) -> {
                        finish();
                    })
                    .setNeutralButton("Cancel", null)
                    .show();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (document != null) {
            try {
                document.close();
            } catch (IOException e) {
                Log.e(TAG, "Error closing document", e);
            }
        }
    }
}
