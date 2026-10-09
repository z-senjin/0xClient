package oxclient.api;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Game messages: "You get some logs.", "Your inventory is too full to hold any more logs." -- read from
 * the chatbox's own text, so nothing here needs an offset.
 *
 * <p>The chatbox shows the last messages as text lines (interface group 162). {@link #lines()} is what it
 * shows right now, top to bottom; {@link #poll()} returns the lines that were not there on the previous
 * poll, which is how a script reacts to "You can't reach that." once rather than every frame.</p>
 */
public final class Chat {

    private Chat() {}

    private static final int GROUP = 162;
    private static Set<String> seen = new LinkedHashSet<>();

    /** The chatbox's visible text lines, tags stripped. */
    public static List<String> lines() {
        List<String> out = new ArrayList<>();
        for (Interfaces.Line l : Interfaces.text(GROUP)) {
            if (l.hidden() || l.child() < 0) continue;     // the messages are dynamic children
            String t = l.plain();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    /** Lines that appeared since the last call. The first call returns nothing (it only remembers). */
    public static synchronized List<String> poll() {
        List<String> now = lines();
        Set<String> next = new LinkedHashSet<>(now);
        List<String> fresh = new ArrayList<>();
        if (!seen.isEmpty()) for (String l : now) if (!seen.contains(l)) fresh.add(l);
        seen = next;
        return fresh;
    }

    /** Is a line containing {@code part} (ignoring case) on screen right now? */
    public static boolean contains(String part) {
        String p = part.toLowerCase();
        for (String l : lines()) if (l.toLowerCase().contains(p)) return true;
        return false;
    }
}
