package oxclient.framework;

import java.util.List;

import oxclient.api.Skill;
import oxclient.api.Skills;

/**
 * One item a training method needs: a tool kept for good (an axe, a fishing rod) or a supply that gets used
 * up (feathers, bait). A method can need several; it can only run with all of them.
 *
 * <p>Names are matched as parts of the item's name, ignoring case, so list them exactly enough not to catch
 * something else -- "Bronze axe", not "axe", which also matches every pickaxe. A tool family lists its
 * members best first with the level each needs; the best one usable at your level is the one that counts,
 * and the bank trip swaps up to it ({@link BankTrip}).</p>
 *
 * @param anyOf       acceptable items, best first (any one will do)
 * @param consumable  used up as you train: the bank trip withdraws all of it, and running out means a trip
 * @param skill       the skill whose level gates the members of a tool family, or null
 * @param levels      the level each of {@code anyOf} needs in {@code skill}, or null when none does
 * @param count       for a supply: how many to carry (topped up at every bank visit); 0 = withdraw all
 */
public record Need(List<String> anyOf, boolean consumable, Skill skill, List<Integer> levels, int count) {

    /** A tool: one of these, carried or worn. */
    public static Need tool(String name) { return new Need(List.of(name), false, null, null, 0); }

    /** A tool family, best first, each usable from a level of {@code skill}. */
    public static Need tool(Skill skill, List<String> anyOf, List<Integer> levels) { return new Need(anyOf, false, skill, levels, 0); }

    /** A supply that is used up, e.g. "Feather"; withdrawn all at once. */
    public static Need supply(String name) { return new Need(List.of(name), true, null, null, 0); }

    /** A supply family, best first, each usable from a level of {@code skill} (raw fish to cook, logs to burn); all of it. */
    public static Need supply(Skill skill, List<String> anyOf, List<Integer> levels) { return new Need(anyOf, true, skill, levels, 0); }

    /** {@code count} of a supply family, best first (food: any of these, topped up to {@code count}). */
    public static Need supply(List<String> anyOf, int count) { return new Need(anyOf, true, null, null, count); }

    /** How many usable members are carried. */
    public int carried() {
        int n = 0;
        for (int i = 0; i < anyOf.size(); i++) if (usable(i)) n += oxclient.api.Inventory.count(anyOf.get(i));
        return n;
    }

    /** The first inventory slot holding a usable member (best first), or -1. */
    public int slot() {
        for (int i = 0; i < anyOf.size(); i++) {
            if (!usable(i)) continue;
            int s = oxclient.api.Inventory.firstSlot(anyOf.get(i));
            if (s >= 0) return s;
        }
        return -1;
    }

    /** Can member {@code i} be used at your level? */
    public boolean usable(int i) { return skill == null || levels == null || Skills.level(skill) >= levels.get(i); }

    /** Which member an item name is (its index in {@link #anyOf}), or -1. */
    public int indexOf(String itemName) {
        String n = itemName.toLowerCase();
        if (n.isEmpty()) return -1;
        for (int i = 0; i < anyOf.size(); i++) if (n.contains(anyOf.get(i).toLowerCase())) return i;
        return -1;
    }

    /** The best usable member carried (or, for a tool, worn): its index, or -1. */
    public int bestHeld() {
        for (int i = 0; i < anyOf.size(); i++) {
            if (!usable(i)) continue;
            String s = anyOf.get(i);
            if (Gear.carrying(s) || (!consumable && Gear.wearing(s))) return i;
        }
        return -1;
    }

    /** Is a usable one carried (or worn)? */
    public boolean met() { return bestHeld() >= 0; }

    /** What to call it on the panel: "Feather", "Rune axe or similar". */
    public String label() { return anyOf.size() == 1 ? anyOf.get(0) : anyOf.get(anyOf.size() - 1).replaceFirst("^\\S+ ", "") + " (any)"; }

    // ---- tool families, best first (OSRS item names and level requirements; free-to-play metals)

    public static final List<Integer> TOOL_LEVELS = List.of(41, 31, 21, 11, 6, 1, 1);
    public static final List<String> AXES = List.of("Rune axe", "Adamant axe", "Mithril axe", "Black axe", "Steel axe", "Iron axe", "Bronze axe");
    public static final List<String> PICKAXES = List.of("Rune pickaxe", "Adamant pickaxe", "Mithril pickaxe", "Black pickaxe", "Steel pickaxe", "Iron pickaxe", "Bronze pickaxe");
}
