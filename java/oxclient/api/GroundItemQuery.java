package oxclient.api;

import java.util.List;
import java.util.function.Supplier;

import oxclient.api.GroundItems.GroundItem;

/**
 * Finding items on the ground: {@link GroundItems#query()}. Everything a {@link WorldQuery} does, plus stack size.
 *
 * <pre>{@code
 * GroundItem coins = GroundItems.query().named("Coins").minQuantity(100).within(8).nearest();
 * }</pre>
 */
public final class GroundItemQuery extends WorldQuery<GroundItem, GroundItemQuery> {

    GroundItemQuery(Supplier<List<GroundItem>> source) { super(source); }

    /** Keep only stacks of at least {@code n}. */
    public GroundItemQuery minQuantity(int n) { return where(g -> g.quantity() >= n); }
}
