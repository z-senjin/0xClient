package oxclient.api;

/**
 * Typing into the game the way a person does: key down, the character, key up.
 *
 * <p>Dialogues ("Click here to continue" = Space, options = 1..5), amount prompts (digits then Enter) and
 * the Grand Exchange search (letters then Enter) are all handled by the client's own scripts reacting to
 * these keys. Keys go through the game's own keyboard entry ({@link Natives#keyStroke}): POSTED keys are
 * dropped by the game unless its render view has keyboard focus (found 2026-10-10 -- nothing reached the GE
 * search while the launcher held focus). Posting is kept only as the fallback for a DLL without keyStroke.</p>
 */
public final class Keyboard {

    private Keyboard() {}

    public static final int VK_SPACE = 0x20;
    public static final int VK_ESCAPE = 0x1B;

    /** One keystroke through the game's own entry; null when this DLL has no keyStroke (then post instead). */
    private static Boolean stroke(int ch, int vk) {
        try { return oxclient.Natives.keyStroke(ch, vk); } catch (UnsatisfiedLinkError e) { return null; }
    }

    /** Press and release one key that produces a character (digits, letters, space). */
    public static boolean press(char c) {
        Boolean r = stroke(c, 0);
        if (r != null) return r;
        int vk = Character.toUpperCase(c);
        if (c == ' ') vk = VK_SPACE;
        boolean ok = Input.key(vk, true);
        ok &= Input.typeChar(c);
        ok &= Input.key(vk, false);
        return ok;
    }

    /** Type a whole string, one character at a time. */
    public static boolean type(String s) {
        boolean ok = true;
        for (char c : s.toCharArray()) ok &= press(c);
        return ok;
    }

    /** Enter (the DLL posts its character too -- see Natives.postKey). */
    public static boolean enter() {
        Boolean r = stroke('\r', Input.VK_RETURN);
        if (r != null) return r;
        boolean ok = Input.key(Input.VK_RETURN, true);
        ok &= Input.key(Input.VK_RETURN, false);
        return ok;
    }

    /** Escape: closes most interfaces. */
    public static boolean escape() {
        Boolean r = stroke(VK_ESCAPE, VK_ESCAPE);
        if (r != null) return r;
        boolean ok = Input.key(VK_ESCAPE, true);
        ok &= Input.key(VK_ESCAPE, false);
        return ok;
    }

    /** Type a number and press Enter -- answers an "Enter amount" prompt. */
    public static boolean amount(int n) {
        return type(Integer.toString(n)) && enter();
    }
}
