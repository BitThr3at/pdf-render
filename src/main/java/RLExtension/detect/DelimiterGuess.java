package RLExtension.detect;

/**
 * Picks the delimiter of a character-separated file by looking for the candidate that yields a
 * consistent field count across the first handful of lines.
 */
public final class DelimiterGuess {

    private static final char[] CANDIDATES = {',', ';', '\t', '|'};
    private static final int SAMPLE_LINES = 20;

    private DelimiterGuess() {
    }

    /** Returns the delimiter, or 0 when the text does not look delimited at all. */
    public static char guess(String head) {
        if (head == null) {
            return 0;
        }
        String text = stripBom(head).stripLeading();
        if (text.isEmpty() || text.charAt(0) == '<' || text.charAt(0) == '{' || text.charAt(0) == '[') {
            return 0; // XML/HTML/JSON, not a spreadsheet.
        }

        char best = 0;
        int bestFields = 1;
        for (char candidate : CANDIDATES) {
            int fields = consistentFieldCount(text, candidate);
            if (fields > bestFields) {
                bestFields = fields;
                best = candidate;
            }
        }
        return best;
    }

    /**
     * Returns the shared field count when every sampled line agrees, otherwise 1.
     * The last sampled line is ignored because the sample may cut it in half.
     */
    private static int consistentFieldCount(String text, char delimiter) {
        int expected = -1;
        int lines = 0;
        int index = 0;
        boolean inQuotes = false;
        int fields = 1;

        while (index < text.length() && lines < SAMPLE_LINES) {
            char c = text.charAt(index++);
            if (c == '"') {
                boolean escaped = inQuotes && index < text.length() && text.charAt(index) == '"';
                if (escaped) {
                    index++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (inQuotes) {
                continue;
            } else if (c == delimiter) {
                fields++;
            } else if (c == '\n') {
                if (fields > 1 || expected > 1) {
                    if (expected == -1) {
                        expected = fields;
                    } else if (expected != fields) {
                        return 1;
                    }
                    lines++;
                }
                fields = 1;
            }
        }
        return expected > 1 && lines >= 1 ? expected : 1;
    }

    private static String stripBom(String text) {
        return !text.isEmpty() && text.charAt(0) == '﻿' ? text.substring(1) : text;
    }
}
