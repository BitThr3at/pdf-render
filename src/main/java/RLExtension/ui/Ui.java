package RLExtension.ui;

import javax.swing.AbstractButton;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSeparator;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.SwingConstants;
import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;

/**
 * Small Swing helpers that keep the toolbars flat and consistent. FlatLaf client properties are
 * set where they help; they are simply ignored under any other look and feel.
 */
public final class Ui {

    private Ui() {
    }

    public static JButton iconButton(VectorIcon.Glyph glyph, String tooltip) {
        JButton button = new JButton(new VectorIcon(glyph));
        flatten(button, tooltip);
        return button;
    }

    public static JToggleButton iconToggle(VectorIcon.Glyph glyph, String tooltip) {
        JToggleButton button = new JToggleButton(new VectorIcon(glyph));
        flatten(button, tooltip);
        return button;
    }

    private static void flatten(AbstractButton button, String tooltip) {
        button.setToolTipText(tooltip);
        button.setFocusable(false);
        button.setMargin(new Insets(3, 5, 3, 5));
        button.putClientProperty("JButton.buttonType", "toolBarButton");
        button.putClientProperty("JComponent.roundRect", true);
    }

    /** A horizontal strip that hosts controls without the raised look of a default toolbar. */
    public static JPanel strip() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEADING, 3, 3));
        panel.setOpaque(false);
        return panel;
    }

    public static Component gap(int width) {
        return Box.createHorizontalStrut(width);
    }

    public static Component separator() {
        JSeparator separator = new JSeparator(SwingConstants.VERTICAL);
        separator.setPreferredSize(new Dimension(1, 18));
        return separator;
    }

    public static JTextField textField(int columns, String placeholder) {
        JTextField field = new JTextField(columns);
        field.putClientProperty("JTextField.placeholderText", placeholder);
        field.putClientProperty("JComponent.roundRect", true);
        field.setToolTipText(placeholder);
        return field;
    }

    /** A muted label for secondary information such as byte counts. */
    public static JLabel subtle(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(subtleForeground());
        return label;
    }

    public static Color subtleForeground() {
        Color disabled = UIManager.getColor("Label.disabledForeground");
        if (disabled != null) {
            return disabled;
        }
        Color foreground = UIManager.getColor("Label.foreground");
        return foreground == null ? Color.GRAY : blend(foreground, background(), 0.45f);
    }

    public static Color background() {
        Color background = UIManager.getColor("Panel.background");
        return background == null ? Color.WHITE : background;
    }

    /** Alternating row colour that works on both light and dark themes. */
    public static Color stripe() {
        Color base = UIManager.getColor("Table.background");
        if (base == null) {
            base = Color.WHITE;
        }
        boolean dark = luminance(base) < 0.5;
        return dark ? lighten(base, 0.06f) : darken(base, 0.035f);
    }

    public static Color divider() {
        Color line = UIManager.getColor("Separator.foreground");
        return line != null ? line : blend(subtleForeground(), background(), 0.3f);
    }

    private static double luminance(Color c) {
        return (0.2126 * c.getRed() + 0.7152 * c.getGreen() + 0.0722 * c.getBlue()) / 255d;
    }

    private static Color lighten(Color c, float amount) {
        return blend(Color.WHITE, c, amount);
    }

    private static Color darken(Color c, float amount) {
        return blend(Color.BLACK, c, amount);
    }

    private static Color blend(Color top, Color bottom, float topWeight) {
        float weight = Math.max(0f, Math.min(1f, topWeight));
        return new Color(
                Math.round(top.getRed() * weight + bottom.getRed() * (1 - weight)),
                Math.round(top.getGreen() * weight + bottom.getGreen() * (1 - weight)),
                Math.round(top.getBlue() * weight + bottom.getBlue() * (1 - weight)));
    }

    /** Rounded chip used for the file type and for notices such as "gzip" or "truncated". */
    public static final class Badge extends JLabel {

        private final boolean emphasised;

        public Badge(String text, boolean emphasised) {
            super(text);
            this.emphasised = emphasised;
            setBorder(BorderFactory.createEmptyBorder(1, 7, 1, 7));
            setFont(getFont().deriveFont(Font.BOLD, Math.max(10f, getFont().getSize2D() - 1f)));
            setForeground(emphasised ? UIManager.getColor("Label.foreground") : subtleForeground());
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                Color fill = emphasised
                        ? blend(subtleForeground(), background(), 0.22f)
                        : blend(subtleForeground(), background(), 0.12f);
                g2.setColor(fill);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 8, 8);
            } finally {
                g2.dispose();
            }
            super.paintComponent(g);
        }
    }

    /** Adds a hairline under a header strip so it reads as a distinct band. */
    public static void underline(JComponent component) {
        component.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, divider()),
                BorderFactory.createEmptyBorder(3, 6, 3, 6)));
    }
}
