package RLExtension.detect;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.zip.GZIPInputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

/**
 * Works out whether a response body is something we can render, using magic bytes first and
 * the Content-Type / filename only as a tie-breaker.
 * <p>
 * Everything here has to be cheap and exception-free: {@code isEnabledFor} runs this for every
 * message Burp shows, on bodies we do not control.
 */
public final class TypeDetector {

    /** Bodies larger than this are refused outright rather than parsed. */
    public static final int MAX_BODY_BYTES = 96 * 1024 * 1024;

    /** How far into the body we look for the %PDF marker. */
    private static final int PDF_SCAN_WINDOW = 1024;

    /** Cap on bytes produced while expanding a compressed body. */
    private static final int MAX_INFLATED_BYTES = MAX_BODY_BYTES;

    private static final byte[] ZIP_MAGIC = {0x50, 0x4B, 0x03, 0x04};
    private static final byte[] CFB_MAGIC = {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0,
            (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1};

    private TypeDetector() {
    }

    /**
     * Full detection: expands a compressed body and returns the bytes a renderer should read.
     */
    public static Detected detect(byte[] rawBody, String contentType, String contentDisposition, String requestPath) {
        if (rawBody == null || rawBody.length == 0 || rawBody.length > MAX_BODY_BYTES) {
            return Detected.NONE;
        }

        int originalLength = rawBody.length;
        byte[] body = rawBody;
        boolean decompressed = false;

        byte[] expanded = expand(rawBody, contentType, MAX_INFLATED_BYTES);
        if (expanded != null) {
            body = expanded;
            decompressed = true;
        }

        String fileName = fileName(contentDisposition, requestPath);
        FileType type = classify(body, contentType, fileName);
        if (type == FileType.UNKNOWN) {
            return Detected.NONE;
        }
        return new Detected(type, body, fileName, decompressed, originalLength);
    }

    /**
     * Cheap variant for {@code isEnabledFor}: only ever expands a small prefix of the body.
     */
    public static FileType sniff(byte[] rawBody, String contentType, String contentDisposition, String requestPath) {
        if (rawBody == null || rawBody.length == 0 || rawBody.length > MAX_BODY_BYTES) {
            return FileType.UNKNOWN;
        }
        byte[] body = rawBody;
        if (isGzip(rawBody) || mentionsCompression(contentType)) {
            byte[] prefix = expand(rawBody, contentType, 128 * 1024);
            if (prefix != null) {
                body = prefix;
            }
        }
        return classify(body, contentType, fileName(contentDisposition, requestPath));
    }

    // ---------------------------------------------------------------- classification

    private static FileType classify(byte[] body, String contentType, String fileName) {
        if (looksLikePdf(body)) {
            return FileType.PDF;
        }
        if (startsWith(body, ZIP_MAGIC)) {
            // OOXML keeps entry names in the clear inside the zip, so a byte search beats inflating.
            return containsAscii(body, "xl/workbook.xml") ? FileType.XLSX : FileType.UNKNOWN;
        }
        if (startsWith(body, CFB_MAGIC)) {
            return looksLikeLegacyExcel(body, contentType, fileName) ? FileType.XLS : FileType.UNKNOWN;
        }
        return classifyText(body, contentType, fileName);
    }

    private static boolean looksLikePdf(byte[] body) {
        int limit = Math.min(body.length - 4, PDF_SCAN_WINDOW);
        for (int i = 0; i <= limit; i++) {
            if (body[i] == '%' && body[i + 1] == 'P' && body[i + 2] == 'D' && body[i + 3] == 'F') {
                return true;
            }
        }
        return false;
    }

    private static boolean looksLikeLegacyExcel(byte[] body, String contentType, String fileName) {
        // A CFB container may hold .doc/.ppt/.msi too, so require an Excel-specific stream name
        // (stored UTF-16LE in the directory) or an explicit content-type / extension hint.
        if (containsUtf16(body, "Workbook") || containsUtf16(body, "Book")) {
            return true;
        }
        String ct = lower(contentType);
        return ct.contains("excel") || ct.contains("ms-excel") || hasExtension(fileName, ".xls");
    }

    private static FileType classifyText(byte[] body, String contentType, String fileName) {
        String ct = lower(contentType);
        boolean csvHint = ct.contains("text/csv") || ct.contains("application/csv")
                || hasExtension(fileName, ".csv");
        boolean tsvHint = ct.contains("tab-separated") || hasExtension(fileName, ".tsv")
                || hasExtension(fileName, ".tab");

        if (csvHint) {
            return FileType.CSV;
        }
        if (tsvHint) {
            return FileType.TSV;
        }

        // No declared type: only claim the body when it is plain text served as a download
        // *and* actually looks delimited. Otherwise we would hijack every JSON/HTML response.
        boolean plainText = ct.isEmpty() || ct.contains("text/plain") || ct.contains("octet-stream");
        if (!plainText) {
            return FileType.UNKNOWN;
        }
        String head = headAsText(body, 8192);
        if (!isMostlyPrintable(head)) {
            return FileType.UNKNOWN; // Binary payload that happens to contain commas.
        }
        char delimiter = DelimiterGuess.guess(head);
        if (delimiter == '\t') {
            return FileType.TSV;
        }
        return delimiter == 0 ? FileType.UNKNOWN : FileType.CSV;
    }

    // ---------------------------------------------------------------- decompression

    /** Returns expanded bytes, or null when the body is not compressed (or will not expand). */
    private static byte[] expand(byte[] body, String contentType, int limit) {
        if (isGzip(body)) {
            byte[] out = drain(gzipStream(body), limit);
            if (out != null && out.length > 0) {
                return out;
            }
        }
        if (mentionsCompression(contentType) && !isGzip(body)) {
            byte[] out = drain(deflateStream(body, false), limit);
            if (out == null || out.length == 0) {
                out = drain(deflateStream(body, true), limit);
            }
            if (out != null && out.length > 0) {
                return out;
            }
        }
        return null;
    }

    private static java.io.InputStream gzipStream(byte[] body) {
        try {
            return new GZIPInputStream(new ByteArrayInputStream(body));
        } catch (Exception e) {
            return null;
        }
    }

    private static java.io.InputStream deflateStream(byte[] body, boolean raw) {
        return new InflaterInputStream(new ByteArrayInputStream(body), new Inflater(raw));
    }

    private static byte[] drain(java.io.InputStream in, int limit) {
        if (in == null) {
            return null;
        }
        try (java.io.InputStream stream = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(limit, 64 * 1024));
            byte[] buffer = new byte[16 * 1024];
            int total = 0;
            int read;
            while ((read = stream.read(buffer)) > 0) {
                out.write(buffer, 0, read);
                total += read;
                if (total >= limit) {
                    break; // Truncated on purpose: guards against a decompression bomb.
                }
            }
            return out.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isGzip(byte[] body) {
        return body.length > 2 && (body[0] & 0xFF) == 0x1F && (body[1] & 0xFF) == 0x8B;
    }

    private static boolean mentionsCompression(String contentType) {
        String ct = lower(contentType);
        return ct.contains("gzip") || ct.contains("deflate");
    }

    // ---------------------------------------------------------------- filename

    /** Pulls a filename out of Content-Disposition, falling back to the request path. */
    public static String fileName(String contentDisposition, String requestPath) {
        String fromHeader = fromContentDisposition(contentDisposition);
        if (fromHeader != null) {
            return fromHeader;
        }
        if (requestPath == null || requestPath.isEmpty()) {
            return null;
        }
        String path = requestPath;
        int query = path.indexOf('?');
        if (query >= 0) {
            path = path.substring(0, query);
        }
        int slash = path.lastIndexOf('/');
        String last = slash >= 0 ? path.substring(slash + 1) : path;
        return last.isEmpty() ? null : last;
    }

    private static String fromContentDisposition(String header) {
        if (header == null || header.isEmpty()) {
            return null;
        }
        String extended = matchAfter(header, "filename*=");
        if (extended != null) {
            // RFC 5987: charset'lang'percent-encoded-value
            int lastQuote = extended.lastIndexOf('\'');
            String value = lastQuote >= 0 ? extended.substring(lastQuote + 1) : extended;
            return percentDecode(value);
        }
        String plain = matchAfter(header, "filename=");
        return plain == null ? null : plain;
    }

    private static String matchAfter(String header, String key) {
        int at = lower(header).indexOf(key);
        if (at < 0) {
            return null;
        }
        String rest = header.substring(at + key.length()).trim();
        if (rest.startsWith("\"")) {
            int end = rest.indexOf('"', 1);
            return end > 0 ? rest.substring(1, end) : rest.substring(1);
        }
        int end = rest.indexOf(';');
        String value = end >= 0 ? rest.substring(0, end) : rest;
        value = value.trim();
        return value.isEmpty() ? null : value;
    }

    private static String percentDecode(String value) {
        try {
            return java.net.URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return value;
        }
    }

    private static boolean hasExtension(String fileName, String extension) {
        return fileName != null && lower(fileName).endsWith(extension);
    }

    // ---------------------------------------------------------------- byte helpers

    private static boolean startsWith(byte[] body, byte[] magic) {
        if (body.length < magic.length) {
            return false;
        }
        for (int i = 0; i < magic.length; i++) {
            if (body[i] != magic[i]) {
                return false;
            }
        }
        return true;
    }

    private static boolean containsAscii(byte[] body, String needle) {
        byte[] target = needle.getBytes(StandardCharsets.US_ASCII);
        // Entry names live near the start (local headers) and the end (central directory).
        return indexOf(body, target, 0, Math.min(body.length, 16 * 1024))
                || indexOf(body, target, Math.max(0, body.length - 256 * 1024), body.length);
    }

    private static boolean containsUtf16(byte[] body, String needle) {
        byte[] target = needle.getBytes(StandardCharsets.UTF_16LE);
        return indexOf(body, target, 0, Math.min(body.length, 64 * 1024));
    }

    private static boolean indexOf(byte[] body, byte[] target, int from, int to) {
        int end = Math.min(to, body.length) - target.length;
        outer:
        for (int i = Math.max(0, from); i <= end; i++) {
            for (int j = 0; j < target.length; j++) {
                if (body[i + j] != target[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    /**
     * Rejects binary payloads before the delimiter guess sees them: control bytes other than the
     * usual whitespace are the giveaway. An undeclared body has to look like text to be claimed.
     */
    private static boolean isMostlyPrintable(String head) {
        if (head.isEmpty()) {
            return false;
        }
        int control = 0;
        for (int i = 0; i < head.length(); i++) {
            char c = head.charAt(i);
            if (c == '\t' || c == '\n' || c == '\r') {
                continue;
            }
            if (c < 0x20 || c == 0x7F) {
                control++;
            }
        }
        return control * 100 < head.length(); // under 1% control characters
    }

    private static String headAsText(byte[] body, int limit) {
        int length = Math.min(body.length, limit);
        return new String(body, 0, length, StandardCharsets.ISO_8859_1);
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
}
