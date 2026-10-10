package oxclient.api;

/**
 * The Grand Exchange, driven by the TEXT on its interface and the keyboard, like a player.
 *
 * <p>Buying is a fixed series of steps, each a method here so a script can run them one per tick and
 * check the screen between them:</p>
 * <ol>
 *   <li>{@link #open()}: click a "Grand Exchange booth" (its "Exchange" option, read from the object).</li>
 *   <li>{@link #clickBuy()}: the Buy button of an empty offer slot.</li>
 *   <li>{@link #search(String)}: type the item's name into the chatbox search and press Enter.</li>
 *   <li>{@link #pickResult(String)}: click the search result with exactly that name.</li>
 *   <li>{@link #setQuantity(int)} / {@link #raisePrice(int)}: "..." then the amount; "+5%" n times.</li>
 *   <li>{@link #confirm()}: the "Confirm" button.</li>
 *   <li>{@link #collect()}: the "Collect" button once the offer has completed.</li>
 * </ol>
 *
 * <p><b>Status: not seen working.</b> Every click goes through the interface-option sender that works for
 * the inventory, and every button is found by its text, so nothing here depends on a pixel position.
 * The Buy buttons are pictures with no text; {@link #clickBuy()} uses {@link #buyButton} -- the
 * component and child of the first slot's Buy button -- which is null until one manual buy has been
 * hooked (OXC_ACTIONPROBE_RVAS=38d3d0 logs the widget id and slot of every interface click). Set it then,
 * e.g. {@code GrandExchange.buyButton = new int[] {(465 << 16) | 7, 0}}.</p>
 */
public final class GrandExchange {

    private GrandExchange() {}

    /** The Grand Exchange interface group. */
    public static final int GROUP = 465;

    /** The chatbox group, where the item search and its results appear. */
    static final int CHATBOX = 162;

    /** {packed component id, child} of the first offer slot's Buy button. */
    public static volatile int[] buyButton = {(465 << 16) | 7, 3};

    // From a hook of the user's own clicks (client-241-3, 2026-10-09/10). Seen working by hand; NOT YET SEEN
    // clicked by 0xClient.
    //   offer slot i (0..7) is component 465:(7 + i); its child 3 is Buy, child 4 is Sell
    //   an item offered from the inventory beside the exchange: 467:0, child = inventory slot, op 1
    //   Confirm: 465:30;  close: child 11 of 465:2;  a search result: child n of 162:53, op 1
    // Typed amounts (the "..." price and quantity buttons) cannot be sent: the reply to "Enter amount" is
    // built inline in the script interpreter (packet 0x44 at 0x1a5ea2, inside 0x1a59d0), with no sender of
    // its own to call -- so prices move with the +/-5% buttons and quantities with the +1/+10/... buttons.
    public static final int SLOT0 = (465 << 16) | 7, BUY_CHILD = 3, SELL_CHILD = 4;
    public static final int OFFER_INVENTORY = (467 << 16);
    public static final int CONFIRM = (465 << 16) | 30;
    public static final int CLOSE = (465 << 16) | 2, CLOSE_CHILD = 11;
    public static final int SEARCH_RESULTS = (162 << 16) | 53;

    // The offer screen (465:26): quantity +1 / +10 / +100 are children 3 / 4 / 5, price -5% is child 10 and +5%
    // child 13; Back is 465:4 (hooked from the user's clicks, 2026-10-10). A search result is clicked through
    // 162:53 -- the client then sends the pick itself (packet 0x3, from the script interpreter at 0x1a67c9).
    // The search TEXT has to be typed: the one step that goes through posted keys, NOT SEEN WORKING YET.
    public static final int OFFER = (465 << 16) | 26;
    public static final int QTY_PLUS_1 = 3, QTY_PLUS_10 = 4, QTY_PLUS_100 = 5, PRICE_MINUS_5 = 10, PRICE_PLUS_5 = 13;
    public static final int BACK = (465 << 16) | 4;

    /** Nudge the price by 5% {@code times} times: negative lowers it, positive raises it. */
    public static boolean nudgePrice(int times) {
        boolean ok = true;
        for (int i = 0; i < Math.abs(times); i++) ok &= Interfaces.click(OFFER, times < 0 ? PRICE_MINUS_5 : PRICE_PLUS_5, 1);
        return ok;
    }

    /** Add to the quantity with the +100 / +10 / +1 buttons (a new buy offer starts at 1). */
    public static boolean addQuantity(int n) {
        boolean ok = true;
        for (int i = 0; i < n / 100; i++) ok &= Interfaces.click(OFFER, QTY_PLUS_100, 1);
        for (int i = 0; i < (n % 100) / 10; i++) ok &= Interfaces.click(OFFER, QTY_PLUS_10, 1);
        for (int i = 0; i < n % 10; i++) ok &= Interfaces.click(OFFER, QTY_PLUS_1, 1);
        return ok;
    }

    /** True while an offer is being set up (the offer screen shows its "per item" price label). */
    public static boolean settingUp() { return Interfaces.findContaining(GROUP, "per item") != null; }

    /** True when slot {@code i} shows "Empty". */
    public static boolean slotEmpty(int i) {
        for (Interfaces.Line l : Interfaces.text(GROUP))
            if (l.component() == 7 + i && !l.hidden() && l.plain().equalsIgnoreCase("Empty")) return true;
        return false;
    }

    /** The first empty slot (F2P has 3), or -1. */
    public static int freeSlot() {
        for (int i = 0; i < 3; i++) if (slotEmpty(i)) return i;
        return -1;
    }

    /** True when any slot shows a finished or partly filled offer to collect. */
    public static boolean canCollect() { return Interfaces.find(GROUP, "Collect") != null; }

    /** Click the search result whose text is {@code name}: the row (the child before the name) and the name. */
    public static boolean pickResult(String name) {
        for (Interfaces.Line l : Interfaces.text(CHATBOX)) {
            if (l.hidden() || l.component() != 53 || !l.plain().equalsIgnoreCase(name)) continue;
            boolean ok = Interfaces.click(SEARCH_RESULTS, Math.max(0, l.child() - 1), 1);
            return Interfaces.click(SEARCH_RESULTS, l.child(), 1) || ok;
        }
        return false;
    }

    /** Click Sell on offer slot {@code i}. */
    public static boolean sellSlot(int i) { return Interfaces.click(SLOT0 + i, SELL_CHILD, 1); }

    /** Click Buy on offer slot {@code i}. */
    public static boolean buySlot(int i) { return Interfaces.click(SLOT0 + i, BUY_CHILD, 1); }

    /** Offer the item in inventory slot {@code slot} (while choosing what to sell). */
    public static boolean offer(int slot) { return Interfaces.click(OFFER_INVENTORY, slot, 1); }

    /** Pick search result {@code n} (0 = the first). */
    public static boolean pickResult(int n) { return Interfaces.click(SEARCH_RESULTS, n, 1); }

    /** The Confirm button of the offer being set up. */
    public static boolean confirmOffer() { return Interfaces.click(CONFIRM, -1, 1); }

    /** Close the exchange with its close button. */
    public static boolean closeButton() { return Interfaces.click(CLOSE, CLOSE_CHILD, 1); }

    public static boolean isOpen() { return Interfaces.isOpen(GROUP); }

    /** Click the nearest Grand Exchange booth's "Exchange" option. False when none is in sight. */
    public static boolean open() {
        Scenery.SceneObject booth = Scenery.nearest(20, o -> o.option("Exchange") > 0 && o.name().toLowerCase().contains("exchange"));
        return booth != null && booth.interact(booth.option("Exchange"));
    }

    /** The first offer slot's Buy button. False until {@link #buyButton} is mapped. */
    public static boolean clickBuy() {
        int[] b = buyButton;
        return b != null && Interfaces.click(b[0], b[1], 1);
    }

    /** Type into the item search (the chatbox prompt) and press Enter. */
    public static boolean search(String itemName) {
        return Keyboard.type(itemName) && Keyboard.enter();
    }


    /** Set the quantity: the first "..." button, then the amount. */
    public static boolean setQuantity(int n) {
        Interfaces.Line dots = Interfaces.find(GROUP, "...");
        return dots != null && dots.click(1) && Keyboard.amount(n);
    }

    /** Click "+5%" {@code times} times -- pays a little over the guide price so a buy fills. */
    public static boolean raisePrice(int times) {
        Interfaces.Line l = Interfaces.find(GROUP, "+5%");
        if (l == null) return false;
        boolean ok = true;
        for (int i = 0; i < times; i++) ok &= l.click(1);
        return ok;
    }

    public static boolean confirm() { return Interfaces.clickText(GROUP, "Confirm"); }

    public static boolean collect() { return Interfaces.clickText(GROUP, "Collect"); }

    /** Close the exchange (Escape). */
    public static boolean close() { return Keyboard.escape(); }
}
