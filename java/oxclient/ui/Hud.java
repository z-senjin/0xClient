package oxclient.ui;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Polygon;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.List;

/**
 * Drawing helpers, so an overlay is a few lines rather than a page of Java2D.
 *
 * <p>None of this is magic -- it is all {@link Graphics2D} underneath and you can ignore the whole class
 * and draw whatever you like. It exists because the same three things get drawn by every plugin: a
 * labelled box over something, a tile highlight on the ground, and a little panel of statistics.</p>
 */
public final class Hud {

    private Hud() {}

    /**
     * Text with a dark outline behind it, which is the difference between readable and not when the
     * background is a snowy field one moment and a dark cave the next.
     */
    public static void text(Graphics2D g, String s, int x, int y, Color colour) {
        if (s == null || s.isEmpty()) return;
        g.setColor(Color.BLACK);
        g.drawString(s, x - 1, y);
        g.drawString(s, x + 1, y);
        g.drawString(s, x, y - 1);
        g.drawString(s, x, y + 1);
        g.setColor(colour);
        g.drawString(s, x, y);
    }

    /** Text centred on a point rather than starting at it. */
    public static void textCentred(Graphics2D g, String s, int cx, int y, Color colour) {
        if (s == null || s.isEmpty()) return;
        int w = g.getFontMetrics().stringWidth(s);
        text(g, s, cx - w / 2, y, colour);
    }

    /**
     * A box standing on a point, the way an entity marker does: {@code w} wide, {@code h} tall, with the
     * point at the bottom centre.
     */
    public static void entityBox(Graphics2D g, Point at, int w, int h, Color colour) {
        if (at == null) return;
        int left = at.x - w / 2, top = at.y - h;
        g.setColor(Theme.alpha(colour, 40));
        g.fillRect(left, top, w, h);
        g.setColor(colour);
        g.drawRect(left, top, w, h);
    }

    /** A tile outline lying flat on the ground, filled faintly and outlined solid. */
    public static void tile(Graphics2D g, Polygon outline, Color colour) {
        if (outline == null) return;
        g.setColor(Theme.alpha(colour, 45));
        g.fillPolygon(outline);
        g.setColor(colour);
        g.drawPolygon(outline);
    }

    /** Somewhere to collect lines for {@link #panel}. */
    public static final class Lines {

        private final List<String> keys = new ArrayList<>();
        private final List<String> values = new ArrayList<>();
        private final List<Color> colours = new ArrayList<>();

        /** A label and a value, shown as two columns. */
        public Lines add(String key, Object value) { return add(key, value, Theme.TEXT); }

        /** A label and a value, in a colour of your choosing. */
        public Lines add(String key, Object value, Color colour) {
            keys.add(key == null ? "" : key);
            values.add(value == null ? "" : String.valueOf(value));
            colours.add(colour);
            return this;
        }

        /** A single line with no value column. */
        public Lines add(String text) { return add(text, "", Theme.TEXT_DIM); }

        public boolean isEmpty() { return keys.isEmpty(); }

        public int size() { return keys.size(); }
    }

    /** How wide {@link #panel} will draw these lines, so a caller can right-align it. */
    public static int panelWidth(Graphics2D g, String title, Lines lines) {
        g.setFont(Theme.UI_BOLD);
        int width = g.getFontMetrics().stringWidth(title);
        g.setFont(Theme.UI);
        for (int i = 0; i < lines.size(); i++) {
            int w = g.getFontMetrics().stringWidth(lines.keys.get(i) + "   " + lines.values.get(i));
            width = Math.max(width, w);
        }
        return width + 16;
    }

    /**
     * A small statistics panel: a rounded translucent box with a title and two columns.
     *
     * <p>Sizes itself to its contents, so you do not have to guess a width and then discover it clips as
     * soon as a number reaches five digits.</p>
     *
     * @return the height it drew, so you can stack another one under it
     */
    public static int panel(Graphics2D g, int x, int y, String title, Lines lines) {
        final int padding = 8, lineHeight = 15;
        int width = panelWidth(g, title, lines);
        int height = padding * 2 + lineHeight * (lines.size() + 1);

        g.setColor(Theme.HUD_BACK);
        g.fillRoundRect(x, y, width, height, 8, 8);
        g.setColor(Theme.HUD_BORDER);
        g.drawRoundRect(x, y, width, height, 8, 8);

        int textY = y + padding + 11;
        g.setFont(Theme.UI_BOLD);
        text(g, title, x + padding, textY, Theme.ACCENT);
        textY += lineHeight;

        g.setFont(Theme.UI);
        for (int i = 0; i < lines.size(); i++) {
            String key = lines.keys.get(i), value = lines.values.get(i);
            text(g, key, x + padding, textY, Theme.TEXT_DIM);
            if (!value.isEmpty()) {
                Rectangle2D r = g.getFontMetrics().getStringBounds(value, g);
                text(g, value, x + width - padding - (int) r.getWidth(), textY, lines.colours.get(i));
            }
            textY += lineHeight;
        }
        return height;
    }
}
