package oxclient.api;

import oxclient.Natives;

/**
 * The bank: is it open, and depositing items from the inventory side of it.
 *
 * <p>Deposits go through the same item-option sender as {@link Inventory#interact} (seen working on
 * client-241-3 for Drop), on the bank's own copy of your inventory, component 15:3. Which option number
 * is "Deposit-All" there is a setting in the plugins that use it, because it has not been seen yet: a
 * wrong number is refused by the client rather than doing something else.</p>
 *
 * <p>"Open" is read as "the bank's interface group, 12, is loaded". That is derived from how the client
 * loads interfaces, not yet seen across an open-and-close; callers should also check that a deposit
 * actually emptied the slot rather than trusting it alone.</p>
 */
public final class Bank {

    private Bank() {}

    /** The bank's main interface group. */
    public static final int GROUP = 12;

    /** The bank's copy of your inventory, (group 15 << 16) | component 3. */
    public static final int INVENTORY_WIDGET = (15 << 16) | 3;

    /** True while the bank interface is loaded. */
    public static boolean isOpen() {
        for (int g : Natives.loadedGroups()) if (g == GROUP) return true;
        return false;
    }

    /**
     * Take deposit option {@code op} on the item in inventory {@code slot}.
     *
     * @return true when queued; false when the slot is empty or the action was dropped
     */
    public static boolean deposit(int slot, int op) {
        int[] ids = Inventory.ids();
        if (slot < 0 || slot >= ids.length || ids[slot] < 0) return false;
        return Natives.itemAction(INVENTORY_WIDGET, slot, op, ids[slot]);
    }

    /**
     * Is this a bank you can open by clicking? A booth or chest -- its name has "bank" in it, not a
     * deposit box -- with a "Bank" or "Use" option. Returns that option number, or -1.
     */
    public static int bankOption(Scenery.SceneObject o) {
        String n = o.name().toLowerCase();
        if (!n.contains("bank") || n.contains("deposit")) return -1;
        int op = o.option("Bank");
        return op > 0 ? op : o.option("Use");
    }
}
