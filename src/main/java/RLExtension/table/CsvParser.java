package RLExtension.table;

import RLExtension.detect.DelimiterGuess;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** RFC 4180 reader with delimiter and charset detection. */
public final class CsvParser {

    private CsvParser() {
    }

    public static Workbook parse(byte[] data, String sheetName) {
        String text = decode(data);
        char delimiter = DelimiterGuess.guess(text.length() > 8192 ? text.substring(0, 8192) : text);
        if (delimiter == 0) {
            delimiter = ',';
        }
        return Workbook.of(parseText(text, delimiter, sheetName));
    }

    /** Parses with a known delimiter, skipping detection (used for declared TSV responses). */
    public static Workbook parse(byte[] data, char delimiter, String sheetName) {
        return Workbook.of(parseText(decode(data), delimiter, sheetName));
    }

    public static Sheet parseText(String text, char delimiter, String sheetName) {
        List<String[]> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        boolean fieldWasQuoted = false;
        int columnCount = 0;
        boolean truncated = false;

        int i = 0;
        int length = text.length();
        while (i < length) {
            char c = text.charAt(i++);

            if (inQuotes) {
                if (c == '"') {
                    if (i < length && text.charAt(i) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    field.append(c);
                }
                continue;
            }

            if (c == '"' && field.length() == 0) {
                inQuotes = true;
                fieldWasQuoted = true;
            } else if (c == delimiter) {
                row.add(finishField(field, fieldWasQuoted));
                fieldWasQuoted = false;
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i < length && text.charAt(i) == '\n') {
                    i++;
                }
                row.add(finishField(field, fieldWasQuoted));
                fieldWasQuoted = false;
                if (!isBlankRow(row)) {
                    columnCount = Math.max(columnCount, row.size());
                    rows.add(row.toArray(new String[0]));
                }
                row.clear();
                if (rows.size() >= Limits.MAX_ROWS) {
                    truncated = true;
                    break;
                }
            } else {
                field.append(c);
            }
        }

        if (!truncated) {
            row.add(finishField(field, fieldWasQuoted));
            if (!isBlankRow(row)) {
                columnCount = Math.max(columnCount, row.size());
                rows.add(row.toArray(new String[0]));
            }
        }

        if (columnCount > Limits.MAX_COLUMNS) {
            columnCount = Limits.MAX_COLUMNS;
            truncated = true;
        }
        return new Sheet(sheetName, rows, columnCount, truncated);
    }

    private static String finishField(StringBuilder field, boolean wasQuoted) {
        String value = field.toString();
        field.setLength(0);
        // Unquoted fields get trimmed; quoted ones keep their whitespace verbatim.
        return Cells.clamp(wasQuoted ? value : value.trim());
    }

    private static boolean isBlankRow(List<String> row) {
        for (String cell : row) {
            if (!cell.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Honours a BOM if present, otherwise tries strict UTF-8 and falls back to Latin-1 so that a
     * mis-declared export still renders instead of filling the grid with replacement characters.
     */
    static String decode(byte[] data) {
        if (data.length >= 3 && (data[0] & 0xFF) == 0xEF && (data[1] & 0xFF) == 0xBB && (data[2] & 0xFF) == 0xBF) {
            return new String(data, 3, data.length - 3, StandardCharsets.UTF_8);
        }
        if (data.length >= 2 && (data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xFE) {
            return new String(data, 2, data.length - 2, StandardCharsets.UTF_16LE);
        }
        if (data.length >= 2 && (data[0] & 0xFF) == 0xFE && (data[1] & 0xFF) == 0xFF) {
            return new String(data, 2, data.length - 2, StandardCharsets.UTF_16BE);
        }
        String strict = decodeStrict(data, StandardCharsets.UTF_8);
        return strict != null ? strict : new String(data, StandardCharsets.ISO_8859_1);
    }

    private static String decodeStrict(byte[] data, Charset charset) {
        CharsetDecoder decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            CharBuffer decoded = decoder.decode(ByteBuffer.wrap(data));
            return decoded.toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }
}
