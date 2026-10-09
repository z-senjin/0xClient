package oxclient.api;

import oxclient.Natives;

/**
 * Varbits and varps: the game's own variables (quest progress, settings, toggles).
 *
 * <p>A varbit is read by the client's own reader on the game thread (client/actions.hpp), so the first
 * call for an id returns -1 and registers it; from the next frame on it holds the live value. Up to 64
 * varbits can be watched at once. Needs actions to be armed (they are, whenever the action senders are
 * measured for this build). Varps come straight from memory and are always current.</p>
 */
public final class Varbits {

    private Varbits() {}

    /** A varbit's value, or -1 until it has been read once. */
    public static int get(int id) { return Natives.varbit(id); }

    /** A varp's value (0 before the varp array exists). */
    public static int varp(int id) { return Natives.varp(id); }
}
