package oxclient.framework;

import oxclient.Natives;
import oxclient.api.Inventory;
import oxclient.api.Items;

/**
 * What you carry and wear, by NAME (item names come from the client's definitions). Names match EXACTLY,
 * ignoring case: a contains-match let "Salmon" (food) match "Raw salmon" and "Logs" match "Oak logs", which
 * sent tasks round in circles (audit 2026-10-10). Every list of acceptable items names each one in full.
 */
public final class Gear {

    private Gear() {}

    /** The game's container id for worn equipment. */
    public static final int EQUIPMENT = 94;

    /** True when an item named {@code name} (ignoring case) is worn. */
    public static boolean wearing(String name) {
        int[] f = Natives.container(EQUIPMENT);
        for (int i = 0; i + 1 < f.length; i += 2) if (f[i] >= 0 && f[i + 1] > 0 && Items.name(f[i]).equalsIgnoreCase(name)) return true;
        return false;
    }

    /** True when an item named {@code name} (ignoring case) is carried. */
    public static boolean carrying(String name) {
        for (int id : Inventory.ids()) if (id >= 0 && Items.name(id).equalsIgnoreCase(name)) return true;
        return false;
    }

    /** True when any of these name parts is worn or carried. An empty list is always true. */
    public static boolean has(java.util.List<String> anyOf) {
        if (anyOf.isEmpty()) return true;
        for (String s : anyOf) if (wearing(s) || carrying(s)) return true;
        return false;
    }

    /** Is the item named one of these (ignoring case)? */
    public static boolean matches(int itemId, java.util.List<String> names) {
        String n = Items.name(itemId);
        if (n.isEmpty()) return false;
        for (String s : names) if (n.equalsIgnoreCase(s)) return true;
        return false;
    }
}
