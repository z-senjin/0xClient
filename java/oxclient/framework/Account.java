package oxclient.framework;

import java.nio.file.Path;
import java.nio.file.Paths;

import oxclient.Natives;
import oxclient.api.Game;

/**
 * Which character is logged in, for keeping saved state apart per character (the user's choice,
 * 2026-10-10): the bank memory and the builder's progress live under {@code ~/.0xclient/scripts/<name>/}.
 * The name is read off your own player (PLAYER_NAME_PTR, which passes the self-check).
 */
public final class Account {

    private Account() {}

    /** A file-safe key for the logged-in character, or "" before you are in the world. */
    public static String key() {
        if (!Game.ready()) return "";
        String n = Natives.entityName(Game.me().uid(), true);
        if (n == null) return "";
        n = n.replace(' ', ' ').trim().toLowerCase().replaceAll("[^a-z0-9]+", "_");
        return n.replaceAll("^_+|_+$", "");
    }

    /** {@code ~/.0xclient/scripts/<character>/<base>.json}, or null before the character is known. */
    public static Path file(String base) {
        String k = key();
        return k.isEmpty() ? null : Paths.get(System.getProperty("user.home"), ".0xclient", "scripts", k, base + ".json");
    }
}
