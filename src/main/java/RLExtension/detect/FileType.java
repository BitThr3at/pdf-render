package RLExtension.detect;

/**
 * Response body types this extension knows how to render.
 */
public enum FileType {
    PDF("PDF", false),
    XLSX("Excel", true),
    XLS("Excel", true),
    CSV("CSV", true),
    TSV("TSV", true),
    UNKNOWN("File", false);

    private final String caption;
    private final boolean tabular;

    FileType(String caption, boolean tabular) {
        this.caption = caption;
        this.tabular = tabular;
    }

    /** Short label used for the editor tab caption and the header badge. */
    public String caption() {
        return caption;
    }

    /** True when the type is rendered as a spreadsheet rather than a document. */
    public boolean isTabular() {
        return tabular;
    }
}
