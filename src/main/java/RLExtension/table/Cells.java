package RLExtension.table;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** Turns raw spreadsheet values into the text shown in a cell. */
public final class Cells {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private Cells() {
    }

    /**
     * Formats a double the way a spreadsheet would rather than the way Java does: no exponent
     * for ordinary magnitudes, no trailing ".0" on whole numbers.
     */
    public static String number(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return String.valueOf(value);
        }
        if (value == Math.rint(value) && Math.abs(value) < 1e15) {
            return String.valueOf((long) value);
        }
        return BigDecimal.valueOf(value)
                .round(new MathContext(15))
                .stripTrailingZeros()
                .toPlainString();
    }

    /**
     * Converts an Excel date serial to text. Excel's 1900 system deliberately contains a
     * non-existent 29 Feb 1900, so serials past 59 are one day ahead of the real count.
     */
    public static String dateSerial(double serial, boolean date1904) {
        try {
            double days = serial;
            LocalDateTime epoch;
            if (date1904) {
                epoch = LocalDateTime.of(1904, 1, 1, 0, 0);
            } else {
                epoch = LocalDateTime.of(1899, 12, 31, 0, 0);
                if (days >= 60) {
                    days -= 1;
                }
            }
            long wholeDays = (long) Math.floor(days);
            double fraction = days - wholeDays;
            long millisInDay = Math.round(fraction * 86_400_000d);

            LocalDateTime moment = epoch.plusDays(wholeDays).plusNanos(millisInDay * 1_000_000L);
            boolean timeless = millisInDay == 0;
            return moment.format(timeless ? DATE : DATE_TIME);
        } catch (Exception e) {
            return number(serial);
        }
    }

    /** True for the built-in number format ids that Excel reserves for dates and times. */
    public static boolean isBuiltinDateFormat(int numberFormatId) {
        return (numberFormatId >= 14 && numberFormatId <= 22)
                || (numberFormatId >= 45 && numberFormatId <= 47)
                || (numberFormatId >= 27 && numberFormatId <= 36)
                || (numberFormatId >= 50 && numberFormatId <= 58);
    }

    /**
     * True when a custom format code renders a date or time. Colour and literal sections are
     * skipped so that e.g. {@code [Red]0.00;"m"} is not mistaken for a month pattern.
     */
    public static boolean isDateFormat(String formatCode) {
        if (formatCode == null || formatCode.isEmpty()) {
            return false;
        }
        boolean inLiteral = false;
        boolean inBracket = false;
        for (int i = 0; i < formatCode.length(); i++) {
            char c = formatCode.charAt(i);
            if (inLiteral) {
                inLiteral = c != '"';
                continue;
            }
            if (inBracket) {
                inBracket = c != ']';
                continue;
            }
            switch (c) {
                case '"' -> inLiteral = true;
                case '[' -> inBracket = true;
                case '\\' -> i++; // escaped literal character
                case 'y', 'Y', 'd', 'D', 'h', 'H', 's', 'S', 'm', 'M' -> {
                    return true;
                }
                default -> {
                }
            }
        }
        return false;
    }

    /** Clamps a cell string so one absurd cell cannot bloat the table. */
    public static String clamp(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= Limits.MAX_CELL_CHARS
                ? value
                : value.substring(0, Limits.MAX_CELL_CHARS) + "…";
    }
}
