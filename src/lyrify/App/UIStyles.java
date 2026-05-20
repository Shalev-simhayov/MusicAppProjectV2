package lyrify.App;

import javafx.scene.text.Font;

// Centralised style constants for the whole UI.
// All colours, font sizes and CSS strings live here so changing
// the look of the app only requires edits in one place.
public final class UIStyles {

    // -- Prevent instantiation - this is a constants-only class
    private UIStyles() {}

    // -- Brand colours --
    public static final String ACCENT       = "#2E4A7A"; // dark blue used for headers
    public static final String ACCENT_LIGHT = "#E8EEF7"; // pale blue for table headers / backgrounds
    public static final String SUCCESS      = "#1D9E75"; // green for high-confidence matches
    public static final String WARNING      = "#BA7517"; // amber for review-zone matches
    public static final String DANGER       = "#E24B4A"; // red for failures / no-match
    public static final String TEXT_PRIMARY = "#1A1A1A"; // near-black body text
    public static final String TEXT_MUTED   = "#666666"; // secondary / hint text
    public static final String BG_MAIN      = "#F5F7FA"; // window background
    public static final String BG_CARD      = "#FFFFFF"; // card / panel background
    public static final String BORDER       = "#D8DCE6"; // subtle border colour

    // -- Font sizes (pts) --
    public static final int FONT_SMALL  = 11;
    public static final int FONT_BODY   = 13;
    public static final int FONT_LARGE  = 15;
    public static final int FONT_TITLE  = 20;

    // -- Spacing (px) --
    public static final int PAD_SM = 8;
    public static final int PAD_MD = 16;
    public static final int PAD_LG = 24;

    // -- Inline CSS snippets used across views --

    // Base window background
    public static final String CSS_ROOT =
        "-fx-background-color: " + BG_MAIN + ";";

    // Card panel (white box with rounded corners and subtle shadow feel)
    public static final String CSS_CARD =
        "-fx-background-color: " + BG_CARD + ";" +
        "-fx-border-color: " + BORDER + ";" +
        "-fx-border-radius: 8;" +
        "-fx-background-radius: 8;" +
        "-fx-padding: 16;";

    // Primary action button (filled blue)
    public static final String CSS_BTN_PRIMARY =
        "-fx-background-color: " + ACCENT + ";" +
        "-fx-text-fill: white;" +
        "-fx-font-size: " + FONT_BODY + "px;" +
        "-fx-background-radius: 6;" +
        "-fx-padding: 8 18 8 18;" +
        "-fx-cursor: hand;";

    // Secondary button (outlined, no fill)
    public static final String CSS_BTN_SECONDARY =
        "-fx-background-color: transparent;" +
        "-fx-border-color: " + ACCENT + ";" +
        "-fx-border-radius: 6;" +
        "-fx-text-fill: " + ACCENT + ";" +
        "-fx-font-size: " + FONT_BODY + "px;" +
        "-fx-padding: 7 17 7 17;" +
        "-fx-cursor: hand;";

    // Danger button (red, for reject / skip)
    public static final String CSS_BTN_DANGER =
        "-fx-background-color: " + DANGER + ";" +
        "-fx-text-fill: white;" +
        "-fx-font-size: " + FONT_BODY + "px;" +
        "-fx-background-radius: 6;" +
        "-fx-padding: 8 18 8 18;" +
        "-fx-cursor: hand;";

    // Label used as a screen heading
    public static final String CSS_HEADING =
        "-fx-font-size: " + FONT_TITLE + "px;" +
        "-fx-font-weight: bold;" +
        "-fx-text-fill: " + ACCENT + ";";

    // Muted helper text
    public static final String CSS_MUTED =
        "-fx-font-size: " + FONT_SMALL + "px;" +
        "-fx-text-fill: " + TEXT_MUTED + ";";

    // TextField / PasswordField base style
    public static final String CSS_FIELD =
        "-fx-background-color: " + BG_CARD + ";" +
        "-fx-border-color: " + BORDER + ";" +
        "-fx-border-radius: 5;" +
        "-fx-background-radius: 5;" +
        "-fx-font-size: " + FONT_BODY + "px;" +
        "-fx-padding: 6 10 6 10;";

    // -- Score badge helpers --

    // Returns the colour string for a confidence score so badges are
    // always colour-coded the same way across the whole app.
    public static String scoreColor(double score) {
        if (score >= 0.85) return SUCCESS;
        if (score >= 0.60) return WARNING;
        return DANGER;
    }

    // Returns a short text label for a score (e.g. "High", "Review", "Low")
    public static String scoreLabel(double score) {
        if (score >= 0.85) return "High";
        if (score >= 0.60) return "Review";
        return "Low";
    }

    // Builds an inline CSS string that colours a label like a badge
    public static String scoreBadgeStyle(double score) {
        return "-fx-background-color: " + scoreColor(score) + "22;" + // 22 = ~13% alpha
               "-fx-text-fill: " + scoreColor(score) + ";" +
               "-fx-background-radius: 4;" +
               "-fx-padding: 2 8 2 8;" +
               "-fx-font-size: " + FONT_SMALL + "px;" +
               "-fx-font-weight: bold;";
    }
}
