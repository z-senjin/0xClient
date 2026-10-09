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

    /** {packed component id, child} of the first offer slot's Buy button; null until mapped (see above). */
    public static volatile int[] buyButton = null;

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

    /** Click the search result whose text is exactly {@code itemName}. False when it is not listed. */
    public static boolean pickResult(String itemName) {
        Interfaces.Line l = Interfaces.find(CHATBOX, itemName);
        return l != null && l.click(1);
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
