package RLExtension.table;

/**
 * Caps applied while parsing attacker-controlled spreadsheets. Without these a crafted file can
 * exhaust heap (zip bomb, sparse sheet claiming row 1,048,576, huge shared-string table).
 */
public final class Limits {

    /**
     * Largest body we will parse into a grid. Parsing multiplies memory use several times over
     * (decoded text, then a String per cell), so this stays well below the detection cap.
     */
    public static final int MAX_TABULAR_BYTES = 32 * 1024 * 1024;

    public static final int MAX_ROWS = 200_000;
    public static final int MAX_COLUMNS = 4_096;
    public static final int MAX_CELL_CHARS = 32_768;

    /** Total uncompressed bytes we will pull out of one zip container. */
    public static final long MAX_UNZIPPED_BYTES = 256L * 1024 * 1024;

    /** Entries we will look at inside one zip container. */
    public static final int MAX_ZIP_ENTRIES = 4_096;

    /** Entries in a shared-string table. */
    public static final int MAX_SHARED_STRINGS = 1_000_000;

    private Limits() {
    }
}
