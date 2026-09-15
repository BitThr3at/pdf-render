package RLExtension.table;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A parsed spreadsheet: one or more sheets plus anything the parser wants to warn about. */
public final class Workbook {

    private final List<Sheet> sheets;
    private final List<String> warnings;

    public Workbook(List<Sheet> sheets, List<String> warnings) {
        this.sheets = sheets;
        this.warnings = warnings == null ? new ArrayList<>() : warnings;
    }

    public static Workbook of(Sheet sheet) {
        return new Workbook(Collections.singletonList(sheet), null);
    }

    public List<Sheet> sheets() {
        return sheets;
    }

    public List<String> warnings() {
        return warnings;
    }

    public boolean isEmpty() {
        return sheets.isEmpty();
    }
}
