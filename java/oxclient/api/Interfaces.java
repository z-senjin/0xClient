package oxclient.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import oxclient.Natives;

/**
 * Interfaces by their TEXT, and clicking their buttons.
 *
 * <p>Every open interface is a group of components, some with text. {@link #text} lists them, so a script
 * can find "Confirm" or "Click here to continue" by what it says rather than where it is drawn. A button
 * is clicked with the client's own interface-option sender (ACT_IF_OP, seen working for inventory Drop on
 * client-241-3): {@link #click} on a component, or on one of its dynamic children by index.</p>
 *
 * <p>Text comes with the client's colour tags ({@code <col=ff0000>}); {@link Line#plain} strips them.</p>
 */
public final class Interfaces {

    private Interfaces() {}

    /** One text-bearing component (child -1) or dynamic child of a group. */
    public record Line(int group, int component, int child, boolean hidden, String text) {
        /** The packed component id, (group << 16) | component. */
        public int widgetId() { return (group << 16) | component; }

        /** The text without colour or image tags. */
        public String plain() { return stripTags(text); }

        /** Take option {@code op} (1..10) on it -- a button press when op is 1. */
        public boolean click(int op) { return Interfaces.click(widgetId(), child, op); }
    }

    /** Is the group loaded right now? */
    public static boolean isOpen(int group) {
        for (int g : Natives.loadedGroups()) if (g == group) return true;
        return false;
    }

    /** Every text line of the group, components and their children; empty when it is not loaded. */
    public static List<Line> text(int group) {
        String raw = Natives.groupText(group);
        if (raw == null || raw.isEmpty()) return Collections.emptyList();
        List<Line> out = new ArrayList<>();
        for (String l : raw.split("\n")) {
            String[] p = l.split(" ", 4);
            if (p.length < 4) continue;
            try {
                out.add(new Line(group, Integer.parseInt(p[0]), Integer.parseInt(p[1]), "1".equals(p[2]), p[3]));
            } catch (NumberFormatException ignored) {
                // a torn line: skip it
            }
        }
        return out;
    }

    /** The first visible line whose plain text equals {@code text} (ignoring case), or null. */
    public static Line find(int group, String text) {
        for (Line l : text(group)) if (!l.hidden() && l.plain().equalsIgnoreCase(text)) return l;
        return null;
    }

    /** The first visible line whose plain text contains {@code part} (ignoring case), or null. */
    public static Line findContaining(int group, String part) {
        String p = part.toLowerCase();
        for (Line l : text(group)) if (!l.hidden() && l.plain().toLowerCase().contains(p)) return l;
        return null;
    }

    /**
     * Option {@code op} on component {@code widgetId}, on its dynamic child {@code child} (-1 for the
     * component itself). The client refuses an option the component does not offer, so a wrong guess does
     * nothing rather than something else.
     */
    public static boolean click(int widgetId, int child, int op) {
        return Natives.itemAction(widgetId, child, op, -1);
    }

    /** Click the visible line with this text in the group (option 1). False when there is none. */
    public static boolean clickText(int group, String text) {
        Line l = find(group, text);
        return l != null && l.click(1);
    }

    /** Remove {@code <...>} tags and collapse no-break spaces. */
    public static String stripTags(String s) {
        if (s == null) return "";
        return s.replaceAll("<[^>]*>", "").replace(' ', ' ').trim();
    }
}
