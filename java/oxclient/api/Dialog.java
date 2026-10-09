package oxclient.api;

import java.util.ArrayList;
import java.util.List;

/**
 * NPC conversations and other "Click here to continue" boxes, and the "Select an Option" menu.
 *
 * <p>Read by text from the dialogue interfaces; answered with keys the way a player does -- Space to
 * continue, 1..5 to pick an option -- so the client's own scripts send the reply (no offset involved).
 * The group ids below are the OSRS dialogue interfaces; they are interface ids, not memory offsets, and
 * do not change with client builds.</p>
 */
public final class Dialog {

    private Dialog() {}

    /** NPC chat, player chat, message box, item/sprite box, double sprite box, level-up style box. */
    static final int[] CONTINUE_GROUPS = {231, 217, 229, 193, 11, 233, 633};

    /** The "Select an Option" menu. */
    static final int OPTIONS_GROUP = 219;

    /** The chatbox, which also hosts "Enter amount" style prompts. */
    static final int CHATBOX_GROUP = 162;

    /** True when a "Click here to continue" (or "Click to continue") is showing. */
    public static boolean canContinue() {
        for (int g : CONTINUE_GROUPS) {
            if (!Interfaces.isOpen(g)) continue;
            if (Interfaces.findContaining(g, "to continue") != null) return true;
        }
        return false;
    }

    /** Continue the conversation (Space). */
    public static boolean continueDialog() { return Keyboard.press(' '); }

    /** The options of an open "Select an Option" menu, in order; empty when none is open. */
    public static List<String> options() {
        List<String> out = new ArrayList<>();
        if (!Interfaces.isOpen(OPTIONS_GROUP)) return out;
        boolean title = true;
        for (Interfaces.Line l : Interfaces.text(OPTIONS_GROUP)) {
            if (l.hidden() || l.plain().isEmpty()) continue;
            if (l.child() < 0) continue;                       // the options are the children
            if (title) { title = false; if (l.plain().toLowerCase().startsWith("select an option")) continue; }
            out.add(l.plain());
        }
        return out;
    }

    /** Pick option {@code n} (1-based) by pressing its number. */
    public static boolean choose(int n) { return n >= 1 && n <= 9 && Keyboard.press((char) ('0' + n)); }

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
