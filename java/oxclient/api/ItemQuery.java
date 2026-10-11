package oxclient.api;

import java.util.List;
import java.util.function.Supplier;

/**
 * Finding items you carry: {@link Inventory#query()}. Everything a {@link Query} does, plus amounts.
 *
 * <pre>{@code
 * Item food   = Inventory.query().withOption("Eat").first();
 * int  coins  = Inventory.query().named("Coins").amount();
 * List<Item> junk = Inventory.query().named("Logs", "Oak logs").list();
 * }</pre>
 */
public final class ItemQuery extends Query<Item, ItemQuery> {

    ItemQuery(Supplier<List<Item>> source) { super(source); }

    /** Keep only stacks of at least {@code n}. */
    public ItemQuery minQuantity(int n) { return where(i -> i.quantity() >= n); }

    /**
     * The total quantity across every match: 25 coins in one slot is 25 here, where {@link #count} -- which
     * counts SLOTS -- says 1.
     */
    public int amount() {
        int n = 0;
        for (Item i : list()) n += Math.max(0, i.quantity());
        return n;
    }
}
