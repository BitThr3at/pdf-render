package RLExtension.table;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Best-effort reader for legacy .xls (BIFF8) workbooks, written against the JDK only.
 * <p>
 * Scope: cell values, sheet names, shared strings, and date formatting. Formulas are <em>not</em>
 * evaluated — the value Excel cached with the formula is shown instead, which is what a viewer
 * wants anyway. Encrypted workbooks are rejected rather than half-parsed, and Excel 5/95 files
 * are read with a warning because their string records use a different encoding.
 */
public final class XlsReader {

    private static final int BOF = 0x0809;
    private static final int EOF_RECORD = 0x000A;
    private static final int BOUNDSHEET = 0x0085;
    private static final int SST = 0x00FC;
    private static final int CONTINUE = 0x003C;
    private static final int FILEPASS = 0x002F;
    private static final int DATEMODE = 0x0022;
    private static final int XF = 0x00E0;
    private static final int FORMAT = 0x041E;
    private static final int LABELSST = 0x00FD;
    private static final int LABEL = 0x0204;
    private static final int RSTRING = 0x00D6;
    private static final int NUMBER = 0x0203;
    private static final int RK = 0x027E;
    private static final int MULRK = 0x00BD;
    private static final int BOOLERR = 0x0205;
    private static final int FORMULA = 0x0006;
    private static final int STRING = 0x0207;

    private final byte[] stream;
    private final List<String> warnings = new ArrayList<>();
    private final List<Integer> xfNumberFormats = new ArrayList<>();
    private final Map<Integer, String> customFormats = new HashMap<>();
    private final List<BoundSheet> boundSheets = new ArrayList<>();
    private String[] sharedStrings = new String[0];
    private boolean date1904;
    private int biffVersion = 0x0600;

    private record BoundSheet(String name, int offset) {
    }

    private XlsReader(byte[] stream) {
        this.stream = stream;
    }

    public static Workbook parse(byte[] data) throws IOException {
        Cfb container = new Cfb(data);
        byte[] workbookStream = container.stream("Workbook");
        if (workbookStream == null) {
            workbookStream = container.stream("Book"); // Excel 5/95 name
        }
        if (workbookStream == null) {
            throw new IOException("Not an .xls workbook: no Workbook stream in the container");
        }

        XlsReader reader = new XlsReader(workbookStream);
        reader.readGlobals();
        return reader.readSheets();
    }

    // ---------------------------------------------------------------- globals substream

    private void readGlobals() throws IOException {
        int position = 0;
        while (position + 4 <= stream.length) {
            int id = u16(stream, position);
            int length = u16(stream, position + 2);
            int body = position + 4;
            if (body + length > stream.length) {
                break;
            }

            int next = body + length;
            switch (id) {
                case FILEPASS -> throw new IOException("Workbook is password protected");
                case BOF -> biffVersion = length >= 2 ? u16(stream, body) : biffVersion;
                case DATEMODE -> date1904 = length >= 2 && u16(stream, body) == 1;
                case XF -> xfNumberFormats.add(length >= 4 ? u16(stream, body + 2) : 0);
                case FORMAT -> readFormat(body, length);
                case BOUNDSHEET -> readBoundSheet(body, length);
                // The SST swallows its own CONTINUE records, so it decides where the next one starts.
                case SST -> next = readSharedStrings(body, length);
                case EOF_RECORD -> {
                    return;
                }
                default -> {
                }
            }
            position = next;
        }
    }

    private void readFormat(int body, int length) throws IOException {
        if (length < 3) {
            return;
        }
        int formatId = u16(stream, body);
        StringValue code = readUnicodeString(stream, body + 2, body + length);
        customFormats.put(formatId, code.text);
    }

    private void readBoundSheet(int body, int length) throws IOException {
        if (length < 8) {
            return;
        }
        int offset = i32(stream, body);
        int nameLength = stream[body + 6] & 0xFF;
        int flags = stream[body + 7] & 0xFF;
        boolean wide = (flags & 0x01) != 0;
        int charBytes = nameLength * (wide ? 2 : 1);
        int end = Math.min(body + 8 + charBytes, body + length);
        String name = new String(stream, body + 8, Math.max(0, end - (body + 8)),
                wide ? StandardCharsets.UTF_16LE : StandardCharsets.ISO_8859_1);
        boundSheets.add(new BoundSheet(name.isEmpty() ? "Sheet" + (boundSheets.size() + 1) : name, offset));
    }

    /**
     * Reads the shared string table, which routinely spills across CONTINUE records — including
     * mid-string, where the continuation restates whether the remaining characters are wide.
     * Returns the stream position just past the last consumed record.
     */
    private int readSharedStrings(int body, int length) throws IOException {
        if (length < 8) {
            return body + length;
        }
        int uniqueCount = i32(stream, body + 4);
        if (uniqueCount < 0 || uniqueCount > Limits.MAX_SHARED_STRINGS) {
            warnings.add("Shared string table declares an implausible size; strings may be missing");
            return body + length;
        }

        List<byte[]> blocks = new ArrayList<>();
        blocks.add(slice(body + 8, body + length));

        int position = body + length;
        while (position + 4 <= stream.length && u16(stream, position) == CONTINUE) {
            int continueLength = u16(stream, position + 2);
            if (position + 4 + continueLength > stream.length) {
                break;
            }
            blocks.add(slice(position + 4, position + 4 + continueLength));
            position += 4 + continueLength;
        }

        SstStream sst = new SstStream(blocks);
        String[] strings = new String[uniqueCount];
        for (int i = 0; i < uniqueCount; i++) {
            try {
                strings[i] = sst.readString();
            } catch (IOException e) {
                warnings.add("Shared string table ended early after " + i + " of " + uniqueCount + " strings");
                for (int j = i; j < uniqueCount; j++) {
                    strings[j] = "";
                }
                break;
            }
        }
        sharedStrings = strings;
        return position;
    }

    // ---------------------------------------------------------------- worksheet substreams

    private Workbook readSheets() {
        if (biffVersion != 0 && biffVersion < 0x0600) {
            warnings.add("Excel 5/95 workbook: text cells may render incorrectly");
        }

        List<Sheet> sheets = new ArrayList<>();
        for (BoundSheet boundSheet : boundSheets) {
            try {
                sheets.add(readSheet(boundSheet));
            } catch (Exception e) {
                warnings.add("Sheet \"" + boundSheet.name + "\" could not be read: " + message(e));
            }
        }
        if (sheets.isEmpty()) {
            sheets.add(new Sheet("(empty)", new ArrayList<>(), 0, false));
        }
        return new Workbook(sheets, warnings);
    }

    private Sheet readSheet(BoundSheet boundSheet) throws IOException {
        TreeMap<Integer, TreeMap<Integer, String>> grid = new TreeMap<>();
        int position = boundSheet.offset;
        int depth = 0;
        boolean truncated = false;
        int[] pendingStringCell = null;

        while (position + 4 <= stream.length) {
            int id = u16(stream, position);
            int length = u16(stream, position + 2);
            int body = position + 4;
            if (body + length > stream.length) {
                break;
            }

            if (id == BOF) {
                depth++;
            } else if (id == EOF_RECORD) {
                if (--depth <= 0) {
                    break;
                }
            } else if (depth == 1) {
                // Nested BOF/EOF pairs wrap charts and macro sheets; only read the top level.
                switch (id) {
                    case LABELSST -> {
                        int index = i32(stream, body + 6);
                        put(grid, body, index >= 0 && index < sharedStrings.length ? sharedStrings[index] : "");
                    }
                    case LABEL, RSTRING -> {
                        StringValue value = readUnicodeString(stream, body + 6, body + length);
                        put(grid, body, value.text);
                    }
                    case NUMBER -> put(grid, body, formatNumeric(f64(stream, body + 6), u16(stream, body + 4)));
                    case RK -> put(grid, body, formatNumeric(decodeRk(i32(stream, body + 6)), u16(stream, body + 4)));
                    case MULRK -> readMulRk(grid, body, length);
                    case BOOLERR -> put(grid, body, decodeBoolErr(body));
                    case FORMULA -> pendingStringCell = readFormula(grid, body, length);
                    case STRING -> {
                        if (pendingStringCell != null) {
                            StringValue value = readUnicodeString(stream, body, body + length);
                            cell(grid, pendingStringCell[0], pendingStringCell[1], value.text);
                            pendingStringCell = null;
                        }
                    }
                    default -> {
                    }
                }
                if (grid.size() > Limits.MAX_ROWS) {
                    truncated = true;
                    break;
                }
            }
            position = body + length;
        }

        return materialise(boundSheet.name, grid, truncated);
    }

    private void readMulRk(TreeMap<Integer, TreeMap<Integer, String>> grid, int body, int length) throws IOException {
        int row = u16(stream, body);
        int firstColumn = u16(stream, body + 2);
        int cells = (length - 6) / 6;
        for (int i = 0; i < cells; i++) {
            int offset = body + 4 + i * 6;
            int formatIndex = u16(stream, offset);
            double value = decodeRk(i32(stream, offset + 2));
            cell(grid, row, firstColumn + i, formatNumeric(value, formatIndex));
        }
    }

    /**
     * Reads a formula's cached result. Returns the cell coordinates when the result is a string,
     * because that string arrives in the STRING record that follows.
     */
    private int[] readFormula(TreeMap<Integer, TreeMap<Integer, String>> grid, int body, int length)
            throws IOException {
        if (length < 14) {
            return null;
        }
        int row = u16(stream, body);
        int column = u16(stream, body + 2);
        int formatIndex = u16(stream, body + 4);
        int marker = stream[body + 6] & 0xFF;
        boolean isSpecial = (u16(stream, body + 12) & 0xFFFF) == 0xFFFF;

        if (!isSpecial) {
            cell(grid, row, column, formatNumeric(f64(stream, body + 6), formatIndex));
            return null;
        }
        switch (marker) {
            case 0 -> {
                return new int[]{row, column}; // string result follows in a STRING record
            }
            case 1 -> cell(grid, row, column, (stream[body + 8] & 0xFF) != 0 ? "TRUE" : "FALSE");
            case 2 -> cell(grid, row, column, errorText(stream[body + 8] & 0xFF));
            default -> cell(grid, row, column, ""); // blank result
        }
        return null;
    }

    private String decodeBoolErr(int body) throws IOException {
        int value = stream[body + 6] & 0xFF;
        boolean isError = (stream[body + 7] & 0xFF) != 0;
        return isError ? errorText(value) : (value != 0 ? "TRUE" : "FALSE");
    }

    private static String errorText(int code) {
        return switch (code) {
            case 0x00 -> "#NULL!";
            case 0x07 -> "#DIV/0!";
            case 0x0F -> "#VALUE!";
            case 0x17 -> "#REF!";
            case 0x1D -> "#NAME?";
            case 0x24 -> "#NUM!";
            case 0x2A -> "#N/A";
            default -> "#ERR!";
        };
    }

    /**
     * RK packs a double into 32 bits: bit 0 means "divide by 100", bit 1 means the remaining
     * 30 bits are a signed integer rather than the top half of an IEEE double.
     */
    private static double decodeRk(int rk) {
        double value;
        if ((rk & 0x02) != 0) {
            value = rk >> 2;
        } else {
            value = Double.longBitsToDouble(((long) (rk & 0xFFFFFFFC)) << 32);
        }
        return (rk & 0x01) != 0 ? value / 100d : value;
    }

    private String formatNumeric(double value, int formatIndex) {
        return isDateFormat(formatIndex) ? Cells.dateSerial(value, date1904) : Cells.number(value);
    }

    private boolean isDateFormat(int xfIndex) {
        if (xfIndex < 0 || xfIndex >= xfNumberFormats.size()) {
            return false;
        }
        int formatId = xfNumberFormats.get(xfIndex);
        String custom = customFormats.get(formatId);
        return custom != null ? Cells.isDateFormat(custom) : Cells.isBuiltinDateFormat(formatId);
    }

    // ---------------------------------------------------------------- grid plumbing

    private void put(TreeMap<Integer, TreeMap<Integer, String>> grid, int body, String value) throws IOException {
        cell(grid, u16(stream, body), u16(stream, body + 2), value);
    }

    private static void cell(TreeMap<Integer, TreeMap<Integer, String>> grid, int row, int column, String value) {
        if (row < 0 || column < 0 || row >= Limits.MAX_ROWS || column >= Limits.MAX_COLUMNS) {
            return;
        }
        grid.computeIfAbsent(row, key -> new TreeMap<>()).put(column, Cells.clamp(value));
    }

    private static Sheet materialise(String name, TreeMap<Integer, TreeMap<Integer, String>> grid, boolean truncated) {
        List<String[]> rows = new ArrayList<>();
        if (grid.isEmpty()) {
            return new Sheet(name, rows, 0, truncated);
        }
        int lastRow = Math.min(grid.lastKey(), Limits.MAX_ROWS - 1);
        int columnCount = 0;
        for (int row = 0; row <= lastRow; row++) {
            TreeMap<Integer, String> cells = grid.get(row);
            if (cells == null || cells.isEmpty()) {
                rows.add(new String[0]);
                continue;
            }
            int width = cells.lastKey() + 1;
            String[] line = new String[width];
            for (int column = 0; column < width; column++) {
                line[column] = cells.getOrDefault(column, "");
            }
            columnCount = Math.max(columnCount, width);
            rows.add(line);
        }
        return new Sheet(name, rows, columnCount, truncated);
    }

    // ---------------------------------------------------------------- BIFF strings

    private record StringValue(String text, int end) {
    }

    /** Reads an XLUnicodeString: character count, a flag byte, then 8- or 16-bit characters. */
    private static StringValue readUnicodeString(byte[] data, int offset, int limit) throws IOException {
        if (offset + 3 > limit) {
            return new StringValue("", limit);
        }
        int charCount = u16(data, offset);
        int flags = data[offset + 2] & 0xFF;
        boolean wide = (flags & 0x01) != 0;
        int start = offset + 3;
        int bytes = Math.min(charCount * (wide ? 2 : 1), Math.max(0, limit - start));
        String text = new String(data, start, bytes, wide ? StandardCharsets.UTF_16LE : StandardCharsets.ISO_8859_1);
        return new StringValue(text, start + bytes);
    }

    /**
     * Cursor over the SST record and its CONTINUE records. A CONTINUE that resumes in the middle
     * of a string's character array begins with a fresh wide/narrow flag byte, so block
     * transitions have to be handled explicitly rather than by concatenating the payloads.
     */
    private static final class SstStream {

        private final List<byte[]> blocks;
        private int blockIndex;
        private int position;
        private boolean wide;
        private boolean insideCharacters;

        SstStream(List<byte[]> blocks) {
            this.blocks = blocks;
        }

        String readString() throws IOException {
            int charCount = u16();
            int flags = u8();
            wide = (flags & 0x01) != 0;
            boolean hasFarEast = (flags & 0x04) != 0;
            boolean hasRichText = (flags & 0x08) != 0;

            int runCount = hasRichText ? u16() : 0;
            long farEastBytes = hasFarEast ? (u16() | ((long) u16() << 16)) : 0;

            if (charCount < 0 || charCount > Limits.MAX_CELL_CHARS * 4) {
                throw new IOException("implausible shared string length");
            }

            StringBuilder text = new StringBuilder(Math.min(charCount, 1024));
            insideCharacters = true;
            for (int i = 0; i < charCount; i++) {
                text.append((char) (wide ? u16() : u8()));
            }
            insideCharacters = false;

            skip((long) runCount * 4);   // formatting runs
            skip(farEastBytes);          // phonetic data
            return Cells.clamp(text.toString());
        }

        private void skip(long count) throws IOException {
            for (long i = 0; i < count; i++) {
                u8();
            }
        }

        private int u16() throws IOException {
            return u8() | (u8() << 8);
        }

        private int u8() throws IOException {
            while (blockIndex < blocks.size() && position >= blocks.get(blockIndex).length) {
                blockIndex++;
                position = 0;
                if (blockIndex < blocks.size() && insideCharacters) {
                    byte[] block = blocks.get(blockIndex);
                    if (block.length == 0) {
                        continue;
                    }
                    wide = (block[position++] & 0x01) != 0;
                }
            }
            if (blockIndex >= blocks.size()) {
                throw new IOException("shared string table truncated");
            }
            return blocks.get(blockIndex)[position++] & 0xFF;
        }
    }

    // ---------------------------------------------------------------- primitives

    private byte[] slice(int from, int to) {
        int start = Math.max(0, Math.min(from, stream.length));
        int end = Math.max(start, Math.min(to, stream.length));
        byte[] out = new byte[end - start];
        System.arraycopy(stream, start, out, 0, out.length);
        return out;
    }

    private static int u16(byte[] data, int offset) throws IOException {
        if (offset + 1 >= data.length) {
            throw new IOException("record truncated");
        }
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
    }

    private static int i32(byte[] data, int offset) throws IOException {
        if (offset + 3 >= data.length) {
            throw new IOException("record truncated");
        }
        return (data[offset] & 0xFF)
                | ((data[offset + 1] & 0xFF) << 8)
                | ((data[offset + 2] & 0xFF) << 16)
                | ((data[offset + 3] & 0xFF) << 24);
    }

    private static double f64(byte[] data, int offset) throws IOException {
        if (offset + 7 >= data.length) {
            throw new IOException("record truncated");
        }
        long bits = 0;
        for (int i = 7; i >= 0; i--) {
            bits = (bits << 8) | (data[offset + i] & 0xFF);
        }
        return Double.longBitsToDouble(bits);
    }

    private static String message(Exception e) {
        String message = e.getMessage();
        return message == null || message.isEmpty() ? e.getClass().getSimpleName() : message;
    }
}
