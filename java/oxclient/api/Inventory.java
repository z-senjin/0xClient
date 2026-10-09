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

    /** The first slot holding an item with this name (ignoring case), or -1. */
    public static int firstSlot(String name) {
        int[] ids = ids();
        for (int i = 0; i < ids.length; i++) if (ids[i] >= 0 && Items.named(ids[i], name)) return i;
        return -1;
    }

    /** How many slots hold an item with this name (ignoring case). */
    public static int count(String name) {
        int n = 0;
        for (int id : ids()) if (id >= 0 && Items.named(id, name)) n++;
        return n;
    }

    /** The total amount of an item, summing stacks (coins, runes) and single slots alike. */
    public static int amount(int itemId) {
        int[] flat = Natives.container(CONTAINER);
        int n = 0;
        for (int i = 0; i + 1 < flat.length; i += 2) if (flat[i] == itemId) n += Math.max(0, flat[i + 1]);
        return n;
    }

    /** True when an item with this name is in the inventory. */
    public static boolean contains(String name) { return firstSlot(name) >= 0; }

    /** How many slots are empty. */
    public static int free() { int u = used(); return u < 0 ? 0 : SIZE - u; }

    // "Use" the item in a slot on something -- the client's own use-item senders (offsets.hpp, USE ITEM ON).
    // Derived from client-241-3, not yet seen working.

    /** Use the item in {@code slot} on the item in {@code targetSlot} (e.g. tinderbox on logs). */
    public static boolean useOn(int slot, int targetSlot) {
        int[] ids = ids();
        if (slot < 0 || targetSlot < 0 || slot >= ids.length || targetSlot >= ids.length || ids[slot] < 0 || ids[targetSlot] < 0) return false;
        return Natives.useItemOn(6, WIDGET, slot, ids[slot], WIDGET, targetSlot, ids[targetSlot]);
    }

    /** Use the item in {@code slot} on a scenery object (e.g. raw fish on a range). */
    public static boolean useOn(int slot, Scenery.SceneObject o) {
        int[] ids = ids();
        if (o == null || slot < 0 || slot >= ids.length || ids[slot] < 0) return false;
        return Natives.useItemOn(7, WIDGET, slot, ids[slot], o.sceneX(), o.sceneY(), o.id());
    }

    /** Use the item in {@code slot} on an NPC. */
    public static boolean useOn(int slot, Entity npc) {
        int[] ids = ids();
        if (npc == null || slot < 0 || slot >= ids.length || ids[slot] < 0) return false;
        return Natives.useItemOn(8, WIDGET, slot, ids[slot], 0, 0, npc.uid());
    }

    /** Use the item in {@code slot} on a ground item. */
    public static boolean useOn(int slot, GroundItems.GroundItem g) {
        int[] ids = ids();
        if (g == null || slot < 0 || slot >= ids.length || ids[slot] < 0) return false;
        return Natives.useItemOn(9, WIDGET, slot, ids[slot], g.sceneX(), g.sceneY(), g.id());
    }

    private static boolean contains(int[] set, int v) {
        for (int s : set) if (s == v) return true;
        return false;
    }
}
