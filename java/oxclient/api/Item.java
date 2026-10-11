package oxclient.api;

/**
 * One stack in your inventory: what it is, how many, which slot. From {@link Inventory#items()} or
 * {@link Inventory#query()}.
 *
 * <pre>{@code
 * Item food = Inventory.query().withOption("Eat").first();
 * if (food != null) food.interact("Eat");
 * Inventory.query().named("Logs").first().drop();
 * tinderbox.useOn(logs);
 * }</pre>
 *
 * <p>A snapshot: the slot is where the item was when it was read. Every action re-checks that the slot still
 * holds the same item and refuses (false) when it does not, so an {@code Item} kept from an earlier tick can
 * never drop whatever has moved into that slot since.</p>
 */
public record Item(int id, int quantity, int slot) implements Interactable {

    /** Its name, or "" when the client has not cached it right now. */
    @Override public String name() { return Items.name(id); }

    /** Its five inventory options ("Eat", "Wield", "Bury", "Drop"); option n is {@code [n - 1]}. */
    @Override public String[] options() { return Items.inventoryOptions(id); }

    /**
     * Take option {@code option} (1..5, its position in {@link #options}). The inventory's own numbering
     * underneath is 2, 3, 4, 6, 7 ({@link Inventory#OPTION_NUMBERS}); this is where it is translated.
     */
    @Override
    public boolean interact(int option) {
        if (option < 1 || option > Inventory.OPTION_NUMBERS.length || !stillHere()) return false;
        return Inventory.interact(slot, Inventory.OPTION_NUMBERS[option - 1]);
    }

    /** Drop it (the "Drop" option, else the fifth -- where Drop always sits). */
    public boolean drop() {
        int n = option("Drop");
        return interact(n > 0 ? n : 5);
    }

    /** True when it stacks (coins, runes, noted items); false when not or not known right now. */
    public boolean stackable() { return Items.stackable(id) == 1; }

    /** Use it on another item you carry (a tinderbox on logs). */
    public boolean useOn(Item other) {
        return other != null && stillHere() && other.stillHere() && Inventory.useOn(slot, other.slot);
    }

    /** Use it on something in the world: an NPC, a piece of scenery (fish on a range), a ground item. */
    public boolean useOn(Interactable target) {
        if (target instanceof Item i) return useOn(i);
        if (!stillHere()) return false;
        if (target instanceof Entity e) return e.isNpc() && Inventory.useOn(slot, e);
        if (target instanceof Scenery.SceneObject o) return Inventory.useOn(slot, o);
        if (target instanceof GroundItems.GroundItem g) return Inventory.useOn(slot, g);
        return false;
    }

    /** True while the inventory still holds this item in this slot. */
    public boolean stillHere() {
        int[] ids = Inventory.ids();
        return slot >= 0 && slot < ids.length && ids[slot] == id;
    }
}
