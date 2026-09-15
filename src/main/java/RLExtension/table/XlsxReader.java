package RLExtension.table;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Reads .xlsx with nothing but the JDK: the container is a zip, and every part inside it is XML
 * we walk with StAX. Avoids pulling Apache POI (and its ~30MB of transitive jars) into the fat JAR.
 */
public final class XlsxReader {

    private XlsxReader() {
    }

    public static Workbook parse(byte[] data) throws IOException, XMLStreamException {
        Map<String, byte[]> parts = readParts(data);
        byte[] workbookXml = parts.get("xl/workbook.xml");
        if (workbookXml == null) {
            throw new IOException("Not an xlsx container: xl/workbook.xml is missing");
        }

        List<String> warnings = new ArrayList<>();
        WorkbookPart workbook = readWorkbook(workbookXml);
        Map<String, String> rels = readRelationships(parts.get("xl/_rels/workbook.xml.rels"));
        String[] sharedStrings = readSharedStrings(parts.get("xl/sharedStrings.xml"));
        boolean[] styleIsDate = readStyles(parts.get("xl/styles.xml"));

        List<Sheet> sheets = new ArrayList<>();
        for (SheetRef ref : workbook.sheets) {
            String target = rels.get(ref.relationshipId);
            byte[] sheetXml = target == null ? null : parts.get(normalise(target));
            if (sheetXml == null) {
                warnings.add("Sheet \"" + ref.name + "\" could not be located inside the container");
                continue;
            }
            sheets.add(readSheet(sheetXml, ref.name, sharedStrings, styleIsDate, workbook.date1904));
        }

        if (sheets.isEmpty()) {
            throw new IOException("No readable worksheets in the container");
        }
        return new Workbook(sheets, warnings);
    }

    // ---------------------------------------------------------------- container

    private static Map<String, byte[]> readParts(byte[] data) throws IOException {
        Map<String, byte[]> parts = new HashMap<>();
        long totalBytes = 0;
        int entries = 0;

        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(data))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > Limits.MAX_ZIP_ENTRIES) {
                    break;
                }
                String name = entry.getName();
                if (entry.isDirectory() || !isWanted(name)) {
                    continue;
                }
                byte[] content = drain(zip, Limits.MAX_UNZIPPED_BYTES - totalBytes);
                totalBytes += content.length;
                parts.put(name, content);
                if (totalBytes >= Limits.MAX_UNZIPPED_BYTES) {
                    break; // Bomb guard: stop expanding rather than run the JVM out of heap.
                }
            }
        }
        return parts;
    }

    private static boolean isWanted(String name) {
        return name.equals("xl/workbook.xml")
                || name.equals("xl/_rels/workbook.xml.rels")
                || name.equals("xl/sharedStrings.xml")
                || name.equals("xl/styles.xml")
                || (name.startsWith("xl/worksheets/") && name.endsWith(".xml"));
    }

    private static byte[] drain(InputStream in, long limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        long total = 0;
        int read;
        while ((read = in.read(buffer)) > 0) {
            out.write(buffer, 0, read);
            total += read;
            if (total >= limit) {
                break;
            }
        }
        return out.toByteArray();
    }

    private static String normalise(String target) {
        String path = target.startsWith("/") ? target.substring(1) : target;
        if (path.startsWith("xl/")) {
            return path;
        }
        return "xl/" + path;
    }

    // ---------------------------------------------------------------- parts

    private record SheetRef(String name, String relationshipId) {
    }

    private static final class WorkbookPart {
        final List<SheetRef> sheets = new ArrayList<>();
        boolean date1904;
    }

    private static WorkbookPart readWorkbook(byte[] xml) throws XMLStreamException {
        WorkbookPart result = new WorkbookPart();
        XMLStreamReader reader = reader(xml);
        try {
            while (reader.hasNext()) {
                if (reader.next() != XMLStreamConstants.START_ELEMENT) {
                    continue;
                }
                switch (reader.getLocalName()) {
                    case "workbookPr" -> {
                        String flag = attribute(reader, "date1904");
                        if (flag == null) {
                            flag = attribute(reader, "date1904Compatibility");
                        }
                        result.date1904 = isTrue(flag);
                    }
                    case "sheet" -> {
                        String name = attribute(reader, "name");
                        String id = attribute(reader, "id"); // r:id, matched on local name
                        if (id != null) {
                            result.sheets.add(new SheetRef(name == null ? "Sheet" : name, id));
                        }
                    }
                    default -> {
                    }
                }
            }
        } finally {
            close(reader);
        }
        return result;
    }

    private static Map<String, String> readRelationships(byte[] xml) throws XMLStreamException {
        Map<String, String> rels = new LinkedHashMap<>();
        if (xml == null) {
            return rels;
        }
        XMLStreamReader reader = reader(xml);
        try {
            while (reader.hasNext()) {
                if (reader.next() == XMLStreamConstants.START_ELEMENT
                        && "Relationship".equals(reader.getLocalName())) {
                    String id = attribute(reader, "Id");
                    String target = attribute(reader, "Target");
                    if (id != null && target != null) {
                        rels.put(id, target);
                    }
                }
            }
        } finally {
            close(reader);
        }
        return rels;
    }

    private static String[] readSharedStrings(byte[] xml) throws XMLStreamException {
        if (xml == null) {
            return new String[0];
        }
        List<String> strings = new ArrayList<>();
        XMLStreamReader reader = reader(xml);
        try {
            while (reader.hasNext() && strings.size() < Limits.MAX_SHARED_STRINGS) {
                if (reader.next() == XMLStreamConstants.START_ELEMENT && "si".equals(reader.getLocalName())) {
                    strings.add(readTextRun(reader, "si"));
                }
            }
        } finally {
            close(reader);
        }
        return strings.toArray(new String[0]);
    }

    /**
     * Concatenates every {@code <t>} in the current element, which is how both shared strings and
     * inline strings represent rich text. Phonetic hints ({@code <rPh>}) are skipped: they are
     * pronunciation guides, not part of the value.
     */
    private static String readTextRun(XMLStreamReader reader, String endElement) throws XMLStreamException {
        StringBuilder text = new StringBuilder();
        int phoneticDepth = 0;
        boolean inText = false;

        while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                String local = reader.getLocalName();
                if (local.equals("rPh") || local.equals("phoneticPr")) {
                    phoneticDepth++;
                } else if (local.equals("t") && phoneticDepth == 0) {
                    inText = true;
                }
            } else if (event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA) {
                if (inText) {
                    text.append(reader.getText());
                }
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                String local = reader.getLocalName();
                if (local.equals("rPh") || local.equals("phoneticPr")) {
                    phoneticDepth = Math.max(0, phoneticDepth - 1);
                } else if (local.equals("t")) {
                    inText = false;
                } else if (local.equals(endElement)) {
                    break;
                }
            }
        }
        return Cells.clamp(text.toString());
    }

    /** Builds a style-index to is-a-date lookup from the number formats declared in styles.xml. */
    private static boolean[] readStyles(byte[] xml) throws XMLStreamException {
        if (xml == null) {
            return new boolean[0];
        }
        Map<Integer, String> customFormats = new HashMap<>();
        List<Integer> cellFormatIds = new ArrayList<>();

        XMLStreamReader reader = reader(xml);
        boolean inCellXfs = false;
        try {
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    switch (reader.getLocalName()) {
                        case "numFmt" -> {
                            int id = intAttribute(reader, "numFmtId", -1);
                            String code = attribute(reader, "formatCode");
                            if (id >= 0 && code != null) {
                                customFormats.put(id, code);
                            }
                        }
                        case "cellXfs" -> inCellXfs = true;
                        case "xf" -> {
                            if (inCellXfs) {
                                cellFormatIds.add(intAttribute(reader, "numFmtId", 0));
                            }
                        }
                        default -> {
                        }
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT && "cellXfs".equals(reader.getLocalName())) {
                    inCellXfs = false;
                }
            }
        } finally {
            close(reader);
        }

        boolean[] isDate = new boolean[cellFormatIds.size()];
        for (int i = 0; i < isDate.length; i++) {
            int formatId = cellFormatIds.get(i);
            String custom = customFormats.get(formatId);
            isDate[i] = custom != null ? Cells.isDateFormat(custom) : Cells.isBuiltinDateFormat(formatId);
        }
        return isDate;
    }

    // ---------------------------------------------------------------- worksheet

    private static Sheet readSheet(byte[] xml, String name, String[] sharedStrings,
                                   boolean[] styleIsDate, boolean date1904) throws XMLStreamException {
        List<String[]> rows = new ArrayList<>();
        List<String> current = new ArrayList<>();
        int columnCount = 0;
        boolean truncated = false;
        int nextColumn = 0;

        XMLStreamReader reader = reader(xml);
        try {
            while (reader.hasNext() && !truncated) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    switch (reader.getLocalName()) {
                        case "row" -> {
                            current.clear();
                            nextColumn = 0;
                            int declared = intAttribute(reader, "r", rows.size() + 1);
                            // Honour row gaps so values stay aligned with what Excel shows.
                            while (rows.size() < declared - 1) {
                                if (rows.size() >= Limits.MAX_ROWS) {
                                    break;
                                }
                                rows.add(new String[0]);
                            }
                        }
                        case "c" -> {
                            String ref = attribute(reader, "r");
                            int column = ref != null ? columnOf(ref) : nextColumn;
                            if (column < 0) {
                                column = nextColumn;
                            }
                            String value = readCell(reader, sharedStrings, styleIsDate, date1904);
                            if (column < Limits.MAX_COLUMNS) {
                                while (current.size() <= column) {
                                    current.add("");
                                }
                                current.set(column, value);
                                nextColumn = column + 1;
                            } else {
                                truncated = true;
                            }
                        }
                        default -> {
                        }
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT && "row".equals(reader.getLocalName())) {
                    trimTrailingBlanks(current);
                    columnCount = Math.max(columnCount, current.size());
                    rows.add(current.toArray(new String[0]));
                    if (rows.size() >= Limits.MAX_ROWS) {
                        truncated = true;
                    }
                }
            }
        } finally {
            close(reader);
        }

        trimTrailingBlankRows(rows);
        return new Sheet(name, rows, columnCount, truncated);
    }

    /** Reads one {@code <c>} element and returns its display text. */
    private static String readCell(XMLStreamReader reader, String[] sharedStrings,
                                   boolean[] styleIsDate, boolean date1904) throws XMLStreamException {
        String type = attribute(reader, "t");
        int styleIndex = intAttribute(reader, "s", -1);

        String rawValue = null;
        String inlineText = null;

        while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                switch (reader.getLocalName()) {
                    case "v" -> rawValue = reader.getElementText();
                    case "is" -> inlineText = readTextRun(reader, "is");
                    case "f" -> skipElement(reader, "f"); // formula source; we show the cached value
                    default -> {
                    }
                }
            } else if (event == XMLStreamConstants.END_ELEMENT && "c".equals(reader.getLocalName())) {
                break;
            }
        }

        if (inlineText != null) {
            return inlineText;
        }
        if (rawValue == null) {
            return "";
        }

        return switch (type == null ? "n" : type) {
            case "s" -> sharedString(sharedStrings, rawValue);
            case "b" -> "1".equals(rawValue.trim()) ? "TRUE" : "FALSE";
            case "str", "e", "d" -> Cells.clamp(rawValue);
            default -> numericCell(rawValue, styleIndex, styleIsDate, date1904);
        };
    }

    private static String sharedString(String[] sharedStrings, String rawValue) {
        try {
            int index = Integer.parseInt(rawValue.trim());
            return index >= 0 && index < sharedStrings.length ? sharedStrings[index] : "";
        } catch (NumberFormatException e) {
            return "";
        }
    }

    private static String numericCell(String rawValue, int styleIndex, boolean[] styleIsDate, boolean date1904) {
        double value;
        try {
            value = Double.parseDouble(rawValue.trim());
        } catch (NumberFormatException e) {
            return Cells.clamp(rawValue);
        }
        boolean isDate = styleIndex >= 0 && styleIndex < styleIsDate.length && styleIsDate[styleIndex];
        return isDate ? Cells.dateSerial(value, date1904) : Cells.number(value);
    }

    /** Converts a cell reference such as {@code BC12} to a zero-based column index. */
    static int columnOf(String reference) {
        int column = 0;
        for (int i = 0; i < reference.length(); i++) {
            char c = reference.charAt(i);
            if (c >= 'A' && c <= 'Z') {
                column = column * 26 + (c - 'A' + 1);
            } else if (c >= 'a' && c <= 'z') {
                column = column * 26 + (c - 'a' + 1);
            } else {
                break;
            }
        }
        return column - 1;
    }

    private static void trimTrailingBlanks(List<String> row) {
        while (!row.isEmpty() && row.get(row.size() - 1).isEmpty()) {
            row.remove(row.size() - 1);
        }
    }

    private static void trimTrailingBlankRows(List<String[]> rows) {
        while (!rows.isEmpty() && rows.get(rows.size() - 1).length == 0) {
            rows.remove(rows.size() - 1);
        }
    }

    // ---------------------------------------------------------------- StAX plumbing

    private static XMLStreamReader reader(byte[] xml) throws XMLStreamException {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        // The document is attacker-controlled: no DTDs, no external entities, no entity expansion.
        setProperty(factory, XMLInputFactory.SUPPORT_DTD, false);
        setProperty(factory, XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        setProperty(factory, XMLInputFactory.IS_COALESCING, true);
        return factory.createXMLStreamReader(new ByteArrayInputStream(xml));
    }

    private static void setProperty(XMLInputFactory factory, String name, boolean value) {
        try {
            factory.setProperty(name, value);
        } catch (IllegalArgumentException e) {
            // Property unsupported by this implementation; the remaining guards still apply.
        }
    }

    private static void close(XMLStreamReader reader) {
        try {
            reader.close();
        } catch (XMLStreamException e) {
            // Nothing useful to do while tearing down a reader over a byte array.
        }
    }

    private static void skipElement(XMLStreamReader reader, String localName) throws XMLStreamException {
        while (reader.hasNext()) {
            if (reader.next() == XMLStreamConstants.END_ELEMENT && localName.equals(reader.getLocalName())) {
                return;
            }
        }
    }

    /** Looks up an attribute by local name so namespace prefixes (r:id, xml:space) do not matter. */
    private static String attribute(XMLStreamReader reader, String localName) {
        for (int i = 0; i < reader.getAttributeCount(); i++) {
            if (localName.equals(reader.getAttributeLocalName(i))) {
                return reader.getAttributeValue(i);
            }
        }
        return null;
    }

    private static int intAttribute(XMLStreamReader reader, String localName, int fallback) {
        String value = attribute(reader, localName);
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static boolean isTrue(String value) {
        return value != null && (value.equals("1") || value.equalsIgnoreCase("true"));
    }
}
