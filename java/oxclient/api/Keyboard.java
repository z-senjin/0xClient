package oxclient.api;

/**
 * Typing into the game the way a person does: key down, the character, key up.
 *
 * <p>Dialogues ("Click here to continue" = Space, options = 1..5), amount prompts (digits then Enter) and
 * the Grand Exchange search (letters then Enter) are all handled by the client's own scripts reacting to
 * these keys -- so nothing here needs an offset. Posted keys reach the game window whether or not it has
 * focus (seen live with the login screen, 2026-09-05).</p>
 */
public final class Keyboard {

    private Keyboard() {}

    public static final int VK_SPACE = 0x20;
    public static final int VK_ESCAPE = 0x1B;

    /** Press and release one key that produces a character (digits, letters, space). */
    public static boolean press(char c) {
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
        boolean ok = Input.key(Input.VK_RETURN, true);
        ok &= Input.key(Input.VK_RETURN, false);
        return ok;
    }

    /** Escape: closes most interfaces. */
    public static boolean escape() {
        boolean ok = Input.key(VK_ESCAPE, true);
        ok &= Input.key(VK_ESCAPE, false);
        return ok;
    }

    /** Type a number and press Enter -- answers an "Enter amount" prompt. */
    public static boolean amount(int n) {
        return type(Integer.toString(n)) && enter();
    }
}
