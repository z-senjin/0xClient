package oxclient.api;

import oxclient.Natives;

/**
 * Your backpack: what is in it, and using an item's options.
 *
 * <p>Read through the game's item container 93 (the inventory, 28 slots). Options go through the same
 * function the game calls when you click an item's option -- see {@code ACT_IF_OP} in
 * {@code client/offsets.hpp} -- on the inventory interface, component 149:0.</p>
 *
 * <p><b>Options are not seen working yet</b> (client-241-3, 2026-10-09). Which option number is "Drop"
 * is a setting in the plugins that use it for exactly that reason: if 7 is wrong, the client refuses
 * an option the item does not have rather than doing something else.</p>
 */
public final class Inventory {

    private Inventory() {}

    /** The game's container id for the inventory. */
    public static final int CONTAINER = 93;

    /** The inventory interface component, (group 149 << 16) | component 0. */
    public static final int WIDGET = (149 << 16);

    /** How many slots the inventory has. */
    public static final int SIZE = 28;

    /** The item id in each slot, -1 for empty. Length 0 when the inventory is not readable. */
    public static int[] ids() {
        int[] flat = Natives.container(CONTAINER);
        int n = flat.length / 2;
        int[] out = new int[n];
        for (int i = 0; i < n; i++) out[i] = flat[2 * i + 1] > 0 ? flat[2 * i] : -1;
        return out;
    }

    /** How many slots hold something. -1 when the inventory is not readable right now. */
    public static int used() {
        int[] ids = ids();
        if (ids.length == 0) return -1;
        int n = 0;
        for (int id : ids) if (id >= 0) n++;
        return n;
    }

    /** True when every slot holds something. False when unreadable, so nothing acts on a bad read. */
    public static boolean isFull() { return used() >= SIZE; }

    /** How many slots hold one of these items. */
    public static int count(int... itemIds) {
        int n = 0;
        for (int id : ids()) if (id >= 0 && contains(itemIds, id)) n++;
        return n;
    }

    /** The first slot holding one of these items, or -1. */
    public static int firstSlot(int... itemIds) {
        int[] ids = ids();
        for (int i = 0; i < ids.length; i++) if (ids[i] >= 0 && contains(itemIds, ids[i])) return i;
        return -1;
    }

    /**
     * Take option {@code op} (1..10, as the game numbers them) on the item in {@code slot}.
     *
     * @return true when the option was queued -- it is sent on the game thread within a frame; false
     *     when the slot is empty or the action was dropped (see the DLL log's {@code [actions]} lines)
     */
    public static boolean interact(int slot, int op) {
        int[] ids = ids();
        if (slot < 0 || slot >= ids.length || ids[slot] < 0) return false;
        return Natives.itemAction(WIDGET, slot, op, ids[slot]);
    }

    private static boolean contains(int[] set, int v) {
        for (int s : set) if (s == v) return true;
        return false;
    }
}
