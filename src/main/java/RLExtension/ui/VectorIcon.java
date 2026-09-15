package RLExtension.ui;

import javax.swing.Icon;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;

/**
 * Toolbar icons drawn with Java2D instead of shipped as bitmaps, so they take their colour from
 * the component's foreground and stay crisp on HiDPI. That is what keeps the toolbar in step with
 * whichever Burp theme is active.
 */
public final class VectorIcon implements Icon {

    public enum Glyph {
        FIRST_PAGE, PREVIOUS_PAGE, NEXT_PAGE, LAST_PAGE,
        ZOOM_IN, ZOOM_OUT, ROTATE_LEFT, ROTATE_RIGHT,
        FIT_WIDTH, FIT_PAGE, ACTUAL_SIZE,
        SINGLE_PAGE, CONTINUOUS, TWO_UP,
        SEARCH, PREVIOUS_MATCH, NEXT_MATCH, SAVE, CLEAR
    }

    private static final int DEFAULT_SIZE = 16;

    private final Glyph glyph;
    private final int size;

    public VectorIcon(Glyph glyph) {
        this(glyph, DEFAULT_SIZE);
    }

    public VectorIcon(Glyph glyph, int size) {
        this.glyph = glyph;
        this.size = size;
    }

    @Override
    public int getIconWidth() {
        return size;
    }

    @Override
    public int getIconHeight() {
        return size;
    }

    @Override
    public void paintIcon(Component c, Graphics g, int x, int y) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g2.setColor(colour(c));
            g2.setStroke(new BasicStroke(Math.max(1.25f, size / 11f),
                    BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.translate(x, y);
            g2.scale(size / 16d, size / 16d);
            draw(g2);
        } finally {
            g2.dispose();
        }
    }

    private static Color colour(Component c) {
        Color foreground = c != null && c.getForeground() != null ? c.getForeground() : Color.DARK_GRAY;
        if (c != null && !c.isEnabled()) {
            return new Color(foreground.getRed(), foreground.getGreen(), foreground.getBlue(), 90);
        }
        return foreground;
    }

    /** Every glyph is drawn inside a nominal 16x16 box; the caller has already scaled for us. */
    private void draw(Graphics2D g) {
        switch (glyph) {
            case PREVIOUS_PAGE -> chevron(g, 10, 8, -1);
            case NEXT_PAGE -> chevron(g, 6, 8, 1);
            case FIRST_PAGE -> {
                chevron(g, 11, 8, -1);
                g.draw(new java.awt.geom.Line2D.Double(5, 3.5, 5, 12.5));
            }
            case LAST_PAGE -> {
                chevron(g, 5, 8, 1);
                g.draw(new java.awt.geom.Line2D.Double(11, 3.5, 11, 12.5));
            }
            case ZOOM_IN -> {
                magnifier(g);
                g.draw(new java.awt.geom.Line2D.Double(4.2, 6.6, 9, 6.6));
                g.draw(new java.awt.geom.Line2D.Double(6.6, 4.2, 6.6, 9));
            }
            case ZOOM_OUT -> {
                magnifier(g);
                g.draw(new java.awt.geom.Line2D.Double(4.2, 6.6, 9, 6.6));
            }
            case SEARCH -> magnifier(g);
            case ROTATE_LEFT -> rotate(g, false);
            case ROTATE_RIGHT -> rotate(g, true);
            case FIT_WIDTH -> {
                page(g, 3, 2, 10, 12);
                arrow(g, 4.6, 8, 11.4, 8);
            }
            case FIT_PAGE -> {
                page(g, 3, 2, 10, 12);
                arrow(g, 8, 4, 8, 11.6);
            }
            case ACTUAL_SIZE -> {
                page(g, 2.5, 2, 11, 12);
                g.draw(new Rectangle2D.Double(5.5, 5.5, 5, 5));
            }
            case SINGLE_PAGE -> page(g, 4, 2, 8, 12);
            case CONTINUOUS -> {
                page(g, 4, 1, 8, 6);
                page(g, 4, 9, 8, 6);
            }
            case TWO_UP -> {
                page(g, 1.5, 2.5, 6, 11);
                page(g, 8.5, 2.5, 6, 11);
            }
            case PREVIOUS_MATCH -> chevronVertical(g, 5.5, -1);
            case NEXT_MATCH -> chevronVertical(g, 10.5, 1);
            case SAVE -> {
                g.draw(new java.awt.geom.Line2D.Double(8, 2.5, 8, 9.5));
                arrowHead(g, 8, 10.4, 0);
                g.draw(new java.awt.geom.Line2D.Double(3, 12.8, 13, 12.8));
            }
            case CLEAR -> {
                g.draw(new java.awt.geom.Line2D.Double(4.5, 4.5, 11.5, 11.5));
                g.draw(new java.awt.geom.Line2D.Double(11.5, 4.5, 4.5, 11.5));
            }
        }
    }

    private static void chevron(Graphics2D g, double tipX, double centreY, int direction) {
        Path2D path = new Path2D.Double();
        path.moveTo(tipX - direction * 3, centreY - 4);
        path.lineTo(tipX, centreY);
        path.lineTo(tipX - direction * 3, centreY + 4);
        g.draw(path);
    }

    private static void chevronVertical(Graphics2D g, double tipY, int direction) {
        Path2D path = new Path2D.Double();
        path.moveTo(4, tipY - direction * 3);
        path.lineTo(8, tipY);
        path.lineTo(12, tipY - direction * 3);
        g.draw(path);
    }

    private static void magnifier(Graphics2D g) {
        g.draw(new Ellipse2D.Double(2.4, 2.4, 8.4, 8.4));
        g.draw(new java.awt.geom.Line2D.Double(10.4, 10.4, 13.8, 13.8));
    }

    private static void page(Graphics2D g, double x, double y, double width, double height) {
        g.draw(new RoundRectangle2D.Double(x, y, width, height, 1.6, 1.6));
    }

    /**
     * Open circle with a solid arrowhead on one end, tangent to the arc so the direction of
     * rotation actually reads at 16px.
     */
    private static void rotate(Graphics2D g, boolean clockwise) {
        double centre = 8;
        double radius = 5;
        g.draw(new java.awt.geom.Arc2D.Double(centre - radius, centre - radius, radius * 2, radius * 2,
                120, 300, java.awt.geom.Arc2D.OPEN));

        // Screen y grows downward, so a point at angle a sits at (cx + r cos a, cy - r sin a).
        double angle = Math.toRadians(clockwise ? 120 : 60);
        double x = centre + radius * Math.cos(angle);
        double y = centre - radius * Math.sin(angle);
        // Tangent points the way travel continues: with decreasing angle when clockwise.
        double sign = clockwise ? 1 : -1;
        double tangentX = sign * Math.sin(angle);
        double tangentY = sign * Math.cos(angle);
        solidArrowHead(g, x, y, tangentX, tangentY);
    }

    /** Filled triangle at (x,y) pointing along the unit vector (dirX, dirY). */
    private static void solidArrowHead(Graphics2D g, double x, double y, double dirX, double dirY) {
        double length = 4.2;
        double halfWidth = 2.5;
        double normalX = -dirY;
        double normalY = dirX;

        Path2D head = new Path2D.Double();
        head.moveTo(x + dirX * length, y + dirY * length);
        head.lineTo(x + normalX * halfWidth, y + normalY * halfWidth);
        head.lineTo(x - normalX * halfWidth, y - normalY * halfWidth);
        head.closePath();
        g.fill(head);
    }

    private static void arrow(Graphics2D g, double x1, double y1, double x2, double y2) {
        g.draw(new java.awt.geom.Line2D.Double(x1, y1, x2, y2));
        boolean horizontal = y1 == y2;
        if (horizontal) {
            arrowHeadHorizontal(g, x1, y1, -1);
            arrowHeadHorizontal(g, x2, y2, 1);
        } else {
            arrowHead(g, x1, y1, 0, -1);
            arrowHead(g, x2, y2, 0, 1);
        }
    }

    private static void arrowHeadHorizontal(Graphics2D g, double x, double y, int direction) {
        Path2D path = new Path2D.Double();
        path.moveTo(x - direction * 2.2, y - 2.2);
        path.lineTo(x, y);
        path.lineTo(x - direction * 2.2, y + 2.2);
        g.draw(path);
    }

    private static void arrowHead(Graphics2D g, double x, double y, int horizontalBias) {
        arrowHead(g, x, y, horizontalBias, 1);
    }

    private static void arrowHead(Graphics2D g, double x, double y, int horizontalBias, int verticalDirection) {
        Path2D path = new Path2D.Double();
        double spread = 2.2;
        path.moveTo(x - spread + horizontalBias * spread, y - verticalDirection * spread);
        path.lineTo(x, y);
        path.lineTo(x + spread + horizontalBias * spread, y - verticalDirection * spread);
        g.draw(path);
    }
}
