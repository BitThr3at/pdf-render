package RLExtension.table;

import java.util.List;

/** One grid of already-formatted cell text. */
public final class Sheet {

    private final String name;
    private final List<String[]> rows;
    private final int columnCount;
    private final boolean truncated;

    public Sheet(String name, List<String[]> rows, int columnCount, boolean truncated) {
        this.name = name;
        this.rows = rows;
        this.columnCount = columnCount;
        this.truncated = truncated;
    }

    public String name() {
        return name;
    }

    public List<String[]> rows() {
        return rows;
    }

    public int columnCount() {
        return columnCount;
    }

    /** True when row/column caps stopped us short of the end of the sheet. */
    public boolean truncated() {
        return truncated;
    }

    public String cell(int row, int column) {
        String[] cells = rows.get(row);
        return column < cells.length && cells[column] != null ? cells[column] : "";
    }
}
