package oxclient.api;

import oxclient.Natives;

/**
 * The bank: is it open, what is in it, and depositing and withdrawing.
 *
 * <p>Every deposit and withdrawal goes through the client's item-option sender (ACT_IF_OP, seen working
 * on client-241-3 for inventory Drop): deposits on the bank's copy of your inventory (15:3), withdrawals on
 * the bank's item grid (12:12).</p>
 *
 * <p>All option numbers below were SEEN in a hook of real clicks (client-241-3, 2026-10-09): the item grid
 * is 12:12; on it a left click is option 1 (Withdraw-1 while the bank's quantity setting is "1"),
 * Withdraw-5 is 3 and Withdraw-All is 7. On the bank's copy of the inventory (15:3) Deposit-1 is 2 and
 * Deposit-All is 8. The close button is child 11 of 12:2.</p>
 *
 * <p>"Open" means the bank's interface group, 12, is loaded. Callers should also check that a deposit or
 * withdrawal changed the inventory rather than trusting it alone.</p>
 */
public final class Bank {

    private Bank() {}

    /** The bank's main interface group. */
    public static final int GROUP = 12;

    /** The bank's copy of your inventory, (group 15 << 16) | component 3. */
    public static final int INVENTORY_WIDGET = (15 << 16) | 3;

    /** The bank's item grid, (group 12 << 16) | component 12 -- seen in a hook of a real withdrawal. */
    public static final int ITEMS_WIDGET = (12 << 16) | 12;

    /** The game's container id for the bank. */
    public static final int CONTAINER = 95;

    /** A plain left click: Withdraw-1 while the bank's quantity setting is "1" (seen live). */
    public static volatile int opWithdraw1 = 1;
    public static volatile int opWithdraw5 = 3;
    public static volatile int opWithdrawAll = 7;
    public static volatile int opDeposit1 = 2;
    public static volatile int opDepositAll = 8;

    /** The close button: child 11 of component 12:2 (seen live). */
    static final int CLOSE_WIDGET = (12 << 16) | 2, CLOSE_CHILD = 11;

    // Buttons along the bottom of the bank, from a hook of the user's own clicks (client-241-3, 2026-10-10):
    // Deposit inventory, the Note toggle, and the quantity buttons 1 / 5 / 10 / X / All (each then makes a
    // left click -- op 1 on the grid -- withdraw that many; X asks for a typed amount, which 0xClient cannot
    // send: the reply is built inside the script interpreter, see GrandExchange). Seen working when clicked by
    // hand; NOT YET SEEN clicked by 0xClient.
    public static final int DEPOSIT_INVENTORY = (12 << 16) | 47;
    public static final int NOTE = (12 << 16) | 25;
    public static final int QTY_1 = (12 << 16) | 29, QTY_5 = (12 << 16) | 31, QTY_10 = (12 << 16) | 33,
            QTY_X = (12 << 16) | 35, QTY_ALL = (12 << 16) | 37;

    /** Set the quantity a left click withdraws: 1, 5, 10, or anything else for All. */
    public static boolean quantity(int n) {
        int w = switch (n) { case 1 -> QTY_1; case 5 -> QTY_5; case 10 -> QTY_10; default -> QTY_ALL; };
        return Interfaces.click(w, -1, 1);
    }

    /** Deposit everything carried in one click. */
    public static boolean depositInventory() { return Interfaces.click(DEPOSIT_INVENTORY, -1, 1); }

    /** Withdraw as notes from now on (the bank remembers the setting). */
    public static boolean withdrawAsNotes() { return Interfaces.click(NOTE, -1, 1); }

    /**
     * The "Item" button beside Note: withdraw items again. NOT MAPPED FROM A CLICK -- 12:23 is the component just
     * before Note (12:25), the way the quantity buttons step by two; the user's test list checks it.
     */
    public static final int ITEM = (12 << 16) | 23;

    /** Withdraw as items (not notes) from now on. */
    public static boolean withdrawAsItems() { return Interfaces.click(ITEM, -1, 1); }

    /** True while the bank interface is loaded. */
    public static boolean isOpen() { return Interfaces.isOpen(GROUP); }

    /** The bank's contents, slot by slot ({@code {id, quantity}} pairs flattened); empty when closed. */
    public static int[] contents() { return Natives.container(CONTAINER); }

    /** The bank slot holding this item, or -1. */
    public static int slotOf(int itemId) {
        int[] f = contents();
        for (int i = 0; i + 1 < f.length; i += 2) if (f[i] == itemId && f[i + 1] > 0) return i / 2;
        return -1;
    }

    /** The bank slot holding an item with this name (ignoring case), or -1. */
    public static int slotOf(String name) {
        int[] f = contents();
        for (int i = 0; i + 1 < f.length; i += 2) if (f[i + 1] > 0 && Items.named(f[i], name)) return i / 2;
        return -1;
    }

    /** How many of this item the bank holds (0 when none or closed). */
    public static int count(int itemId) {
        int[] f = contents();
        int n = 0;
        for (int i = 0; i + 1 < f.length; i += 2) if (f[i] == itemId) n += Math.max(0, f[i + 1]);
        return n;
    }

    /** Take deposit option {@code op} on the item in inventory {@code slot}. */
    public static boolean deposit(int slot, int op) {
        int[] ids = Inventory.ids();
        if (slot < 0 || slot >= ids.length || ids[slot] < 0) return false;
        return Natives.itemAction(INVENTORY_WIDGET, slot, op, ids[slot]);
    }

    /** Deposit-All of whatever is in inventory {@code slot}. */
    public static boolean depositAll(int slot) { return deposit(slot, opDepositAll); }

    /** Withdraw with option {@code op} from bank {@code slot}. */
    public static boolean withdraw(int slot, int op) {
        int[] f = contents();
        if (slot < 0 || 2 * slot + 1 >= f.length || f[2 * slot] < 0) return false;
        return Natives.itemAction(ITEMS_WIDGET, slot, op, f[2 * slot]);
    }

    /** Withdraw one of this item. False when the bank does not have it. */
    public static boolean withdrawOne(int itemId) { return withdraw(slotOf(itemId), opWithdraw1); }

    /** Withdraw all of this item. False when the bank does not have it. */
    public static boolean withdrawAll(int itemId) { return withdraw(slotOf(itemId), opWithdrawAll); }

    /** Withdraw one of the item named {@code name} (ignoring case). False when the bank does not have it. */
    public static boolean withdrawOne(String name) { return withdraw(slotOf(name), opWithdraw1); }

    /** Withdraw five of the item named {@code name}. False when the bank does not have it. */
    public static boolean withdrawFive(String name) { return withdraw(slotOf(name), opWithdraw5); }

    /** Withdraw all of the item named {@code name}. False when the bank does not have it. */
    public static boolean withdrawAll(String name) { return withdraw(slotOf(name), opWithdrawAll); }

    /** Deposit every one of the item named {@code name} you carry. False when you carry none. */
    public static boolean depositAll(String name) { return depositAll(Inventory.firstSlot(name)); }

    /** How many of the item named {@code name} the bank holds (0 when none, or the bank is closed). */
    public static int count(String name) {
        int[] f = contents();
        int n = 0;
        for (int i = 0; i + 1 < f.length; i += 2) if (f[i + 1] > 0 && Items.named(f[i], name)) n += f[i + 1];
        return n;
    }

    /** True when the bank holds at least one of the item named {@code name}. */
    public static boolean contains(String name) { return slotOf(name) >= 0; }

    /** Close the bank with its close button (Escape only closes interfaces when that game setting is on). */
    public static boolean close() { return Interfaces.click(CLOSE_WIDGET, CLOSE_CHILD, 1); }

    /**
     * Is this a bank you can open by clicking? Anything with a "Bank" option that is not a deposit box (the
     * Grand Exchange's booths are named "Grand Exchange booth", not "Bank booth": a name test missed them, live
     * 2026-10-10), or a chest named "...bank..." with "Use". Returns that option number, or -1.
     */
    public static int bankOption(Scenery.SceneObject o) {
        String n = o.name().toLowerCase();
        if (n.contains("deposit")) return -1;
        int op = o.option("Bank");
        if (op > 0) return op;
        return n.contains("bank") ? o.option("Use") : -1;
    }
}
