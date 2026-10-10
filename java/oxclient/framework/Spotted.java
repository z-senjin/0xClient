package oxclient.framework;

/** A training task done at a spot, that can fail for want of an item -- what the {@link Picker} learns from. */
public interface Spotted extends Task {

    /** Where it trains. */
    Place spot();

    /** True when it failed because the bank lacked an item the method needs. */
    boolean missingSupplies();

    /** That item, or null. */
    Need missingItem();
}
