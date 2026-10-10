package oxclient.api;

import java.util.ArrayList;
import java.util.List;

import oxclient.Natives;

/**
 * NPC conversations and other "Click here to continue" boxes, and the "Select an Option" menu.
 *
 * <p>Read by text from the dialogue interfaces, and answered the way a click answers them: the client's own
 * "continue" sender (ACT_RESUME) with the component that says "Click here to continue", or with the options
 * menu's component and the chosen option's child. Posted keys (Space, 1..5) were the first way and did NOT
 * continue a level-up message live (2026-10-09), so they are now only the fallback for a build where
 * ACT_RESUME is not derived. The group ids below are the OSRS dialogue interfaces; they are interface ids,
 * not memory offsets, and do not change with client builds.</p>
 */
public final class Dialog {

    private Dialog() {}

    /** NPC chat, player chat, message box, item/sprite box, double sprite box, level-up style box. */
    static final int[] CONTINUE_GROUPS = {231, 217, 229, 193, 11, 233, 633};

    /** The "Select an Option" menu. */
    static final int OPTIONS_GROUP = 219;

    /** The chatbox, which also hosts "Enter amount" style prompts. */
    static final int CHATBOX_GROUP = 162;

    /** The "Click here to continue" line showing right now, or null. */
    static Interfaces.Line continueLine() {
        for (int g : CONTINUE_GROUPS) {
            if (!Interfaces.isOpen(g)) continue;
            Interfaces.Line l = Interfaces.findContaining(g, "to continue");
            if (l != null) return l;
        }
        return null;
    }

    // A dialogue group can stay LOADED after it closes, text and all ("open" means loaded: 2026-10-10), so a
    // stale "Click here to continue" could hold every task "continuing a message" forever. The same line still
    // there after 3 continues in 15 s is treated as stale and ignored for 30 s.
    private static String lastLine = "";
    private static int sameCount;
    private static long firstAt, ignoreUntil;

    /** True when a "Click here to continue" (or "Click to continue") is showing. */
    public static boolean canContinue() {
        Interfaces.Line l = continueLine();
        if (l == null) return false;
        long now = System.currentTimeMillis();
        String key = l.widgetId() + ":" + l.child() + ":" + l.plain();
        return !(key.equals(lastLine) && now < ignoreUntil);
    }

    /** Continue the conversation: the client's continue sender on that line, else Space. */
    public static boolean continueDialog() {
        Interfaces.Line l = continueLine();
        if (l != null) {
            long now = System.currentTimeMillis();
            String key = l.widgetId() + ":" + l.child() + ":" + l.plain();
            if (!key.equals(lastLine) || now - firstAt > 15000) { lastLine = key; sameCount = 0; firstAt = now; }
            if (++sameCount > 3) { ignoreUntil = now + 30000; return false; }   // stale: leave it alone a while
        }
        if (l != null && Natives.resumeDialog(l.widgetId(), l.child())) return true;
        return Keyboard.press(' ');
    }

    /** The option lines of an open "Select an Option" menu, in order; empty when none is open. */
    static List<Interfaces.Line> optionLines() {
        List<Interfaces.Line> out = new ArrayList<>();
        if (!Interfaces.isOpen(OPTIONS_GROUP)) return out;
        boolean title = true;
        for (Interfaces.Line l : Interfaces.text(OPTIONS_GROUP)) {
            if (l.hidden() || l.plain().isEmpty()) continue;
            if (l.child() < 0) continue;                       // the options are the children
            if (title) { title = false; if (l.plain().toLowerCase().startsWith("select an option")) continue; }
            out.add(l);
        }
        return out;
    }

    /** The options of an open "Select an Option" menu, in order; empty when none is open. */
    public static List<String> options() {
        List<String> out = new ArrayList<>();
        for (Interfaces.Line l : optionLines()) out.add(l.plain());
        return out;
    }

    /** Pick option {@code n} (1-based): the client's continue sender on that option, else its number key. */
    public static boolean choose(int n) {
        List<Interfaces.Line> ops = optionLines();
        if (n >= 1 && n <= ops.size()) {
            Interfaces.Line l = ops.get(n - 1);
            if (Natives.resumeDialog(l.widgetId(), l.child())) return true;
        }
        return n >= 1 && n <= 9 && Keyboard.press((char) ('0' + n));
    }

    /** Pick the first option containing {@code part} (ignoring case). False when there is none. */
    public static boolean choose(String part) {
        List<String> ops = options();
        for (int i = 0; i < ops.size(); i++) if (ops.get(i).toLowerCase().contains(part.toLowerCase())) return choose(i + 1);
        return false;
    }

    /** Is any dialogue showing (continue box or option menu)? */
    public static boolean isOpen() { return canContinue() || !options().isEmpty(); }

    /** The chatbox's "Enter amount" / "Enter name" style prompt text, or "" when none is up. */
    public static String prompt() {
        Interfaces.Line l = Interfaces.findContaining(CHATBOX_GROUP, "enter amount");
        if (l == null) l = Interfaces.findContaining(CHATBOX_GROUP, "what would you like to buy");
        if (l == null) l = Interfaces.findContaining(CHATBOX_GROUP, "how many");
        return l == null ? "" : l.plain();
    }
}
