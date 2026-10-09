package oxclient.framework;

import oxclient.Natives;
import oxclient.api.Inventory;
import oxclient.api.Items;

/** What you carry and wear, by NAME (item names come from the client's definitions). */
public final class Gear {

    private Gear() {}

    /** The game's container id for worn equipment. */
    public static final int EQUIPMENT = 94;

    /** True when an item whose name contains {@code part} (ignoring case) is worn. */
    public static boolean wearing(String part) {
        int[] f = Natives.container(EQUIPMENT);
        String p = part.toLowerCase();
        for (int i = 0; i + 1 < f.length; i += 2) if (f[i] >= 0 && f[i + 1] > 0 && Items.name(f[i]).toLowerCase().contains(p)) return true;
        return false;
    }

    /** True when an item whose name contains {@code part} is carried. */
    public static boolean carrying(String part) {
        String p = part.toLowerCase();
        for (int id : Inventory.ids()) if (id >= 0 && Items.name(id).toLowerCase().contains(p)) return true;
        return false;
    }

    /** True when any of these name parts is worn or carried. An empty list is always true. */
    public static boolean has(java.util.List<String> anyOf) {
        if (anyOf.isEmpty()) return true;
        for (String s : anyOf) if (wearing(s) || carrying(s)) return true;
        return false;
    }

    /** Does an item's name match any of these parts (ignoring case)? */
    public static boolean matches(int itemId, java.util.List<String> parts) {
        String n = Items.name(itemId).toLowerCase();
        if (n.isEmpty()) return false;
        for (String s : parts) if (n.contains(s.toLowerCase())) return true;
        return false;
    }
}
