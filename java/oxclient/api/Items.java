package oxclient.api;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import oxclient.Natives;

/**
 * Item definitions: name, stackable, shop value and ground options, read from the client's own cache.
 *
 * <p>The client caches every item it draws, so anything in your inventory, bank or on screen has a
 * definition here. An item it has not drawn yet answers "" / -1 -- ask again once it is on screen.</p>
 *
 * <p>Derived from client-241-3 (2026-10-09); the self-check names your inventory on login, which is
 * what marks these offsets verified.</p>
 */
public final class Items {

    private Items() {}

    private static final Map<Integer, String> NAMES = new ConcurrentHashMap<>();

    /** The item's name ("Logs", "Bronze axe"), or "" when not cached right now. */
    public static String name(int id) {
        if (id < 0) return "";
        String n = NAMES.get(id);
        if (n != null) return n;
        String raw = Natives.itemName(id);
        if (raw == null || raw.isEmpty()) return "";
        n = raw.replace(' ', ' ').trim();
        if (!n.isEmpty()) NAMES.put(id, n);
        return n;
    }

    /** True when the name matches, ignoring case. */
    public static boolean named(int id, String name) { return name != null && name.equalsIgnoreCase(name(id)); }

    /** 1 stackable, 0 not, -1 unknown right now. */
    public static int stackable(int id) { return Natives.itemInfo(id)[0]; }

    /** The item's shop value in coins, or -1 when unknown right now. */
    public static int value(int id) { return Natives.itemInfo(id)[1]; }

    /** The five ground options ("Take" is usually one); option n is {@code [n-1]}, "" where none. */
    public static String[] groundOptions(int id) {
        String raw = Natives.itemGroundOptions(id);
        String[] out = {"", "", "", "", ""};
        if (raw == null || raw.isEmpty()) return out;
        String[] parts = raw.split("\n", -1);
        for (int i = 0; i < 5 && i < parts.length; i++) out[i] = parts[i].replace(' ', ' ').trim();
        return out;
    }
}
