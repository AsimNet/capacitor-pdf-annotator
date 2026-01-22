package com.capacitor.pdfannotator;

import android.graphics.Color;

/**
 * Centralized color palette for the PDF annotator.
 * Ensures color picker UI and drawing colors are always in sync.
 *
 * Material Design color values are used by default.
 */
public class ColorPalette {

    /**
     * Default Material Design color palette.
     * Order: Black, Red, Blue, Green, Yellow, Pink/Magenta, Gray, Cyan, White
     */
    public static final int[] DEFAULT_COLORS = {
            0xFF000000, // Black
            0xFFF44336, // Red (Material Red 500)
            0xFF2196F3, // Blue (Material Blue 500)
            0xFF4CAF50, // Green (Material Green 500)
            0xFFFFEB3B, // Yellow (Material Yellow 500)
            0xFFE91E63, // Pink (Material Pink 500)
            0xFF9E9E9E, // Gray (Material Gray 500)
            0xFF00BCD4, // Cyan (Material Cyan 500)
            0xFFFFFFFF  // White
    };

    /**
     * Color names for logging/debugging
     */
    public static final String[] COLOR_NAMES = {
            "Black", "Red", "Blue", "Green", "Yellow", "Pink", "Gray", "Cyan", "White"
    };

    // Instance state
    private final int[] colors;

    /**
     * Create a ColorPalette with default colors
     */
    public ColorPalette() {
        this.colors = DEFAULT_COLORS.clone();
    }

    /**
     * Create a ColorPalette with custom colors
     *
     * @param customColors Array of color integers (ARGB format).
     *                     If null or empty, defaults are used.
     *                     If fewer than 9 colors, remaining slots use defaults.
     */
    public ColorPalette(int[] customColors) {
        this.colors = DEFAULT_COLORS.clone();

        if (customColors != null && customColors.length > 0) {
            int copyLength = Math.min(customColors.length, DEFAULT_COLORS.length);
            System.arraycopy(customColors, 0, this.colors, 0, copyLength);
        }
    }

    /**
     * Create a ColorPalette from hex color strings
     *
     * @param hexColors Array of hex color strings (e.g., "#FF0000", "#F44336")
     * @return ColorPalette instance
     */
    public static ColorPalette fromHexStrings(String[] hexColors) {
        if (hexColors == null || hexColors.length == 0) {
            return new ColorPalette();
        }

        int[] intColors = new int[hexColors.length];
        for (int i = 0; i < hexColors.length; i++) {
            try {
                intColors[i] = Color.parseColor(hexColors[i]);
            } catch (Exception e) {
                // Use default color for invalid strings
                intColors[i] = i < DEFAULT_COLORS.length ? DEFAULT_COLORS[i] : Color.BLACK;
            }
        }

        return new ColorPalette(intColors);
    }

    /**
     * Get color at specified index
     *
     * @param index Color index (0-8)
     * @return Color integer (ARGB format)
     */
    public int getColor(int index) {
        if (index < 0 || index >= colors.length) {
            return Color.BLACK;
        }
        return colors[index];
    }

    /**
     * Get all colors
     *
     * @return Array of color integers
     */
    public int[] getColors() {
        return colors.clone();
    }

    /**
     * Get the number of colors in the palette
     *
     * @return Number of colors (always 9)
     */
    public int size() {
        return colors.length;
    }

    // Color index constants for convenience
    public static final int INDEX_BLACK = 0;
    public static final int INDEX_RED = 1;
    public static final int INDEX_BLUE = 2;
    public static final int INDEX_GREEN = 3;
    public static final int INDEX_YELLOW = 4;
    public static final int INDEX_PINK = 5;
    public static final int INDEX_GRAY = 6;
    public static final int INDEX_CYAN = 7;
    public static final int INDEX_WHITE = 8;
}
