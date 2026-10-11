package oxclient.framework;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the account needs and does not have -- a tool, a supply, a quest item -- and what it is for. Goals add to
 * it when the bank lacks something; a Grand Exchange trip ({@link TradeTask}) buys from it; anything that turns
 * up (carried, or seen in the bank) drops off it by itself.
 */
public final class ShoppingList {

    private final Map<Need, String> wants = new LinkedHashMap<>();

    /** Want {@code need}, for {@code what} ("Woodcutting (oaks)", "Cook's Assistant"). The latest reason wins. */
    public void add(Need need, String what) { if (need != null) wants.put(need, what); }

    /** Want {@code need} unless it is already on the list (so an earlier, more specific reason is kept). */
    public void addIfAbsent(Need need, String what) { if (need != null) wants.putIfAbsent(need, what); }

    public boolean isEmpty() { return wants.isEmpty(); }

    /** Drop entries that have turned up: carried or worn, or in the bank as last seen. */
    public void prune() { wants.keySet().removeIf(n -> n.met() || (BankCache.known() && BankCache.couldHave(n))); }

    /** One line per item for the panel: "Feather (for Fishing (trout & salmon))". */
    public List<String> lines() {
        List<String> out = new ArrayList<>();
        for (Map.Entry<Need, String> e : wants.entrySet()) out.add(e.getKey().label() + " (for " + e.getValue() + ")");
        return out;
    }

    /** The list as things to buy: for a family, the best member usable now; in a sensible amount. */
    public List<TradeTask.Want> toBuy() {
        prune();
        List<TradeTask.Want> out = new ArrayList<>();
        for (Need n : wants.keySet()) {
            String name = n.anyOf().get(n.anyOf().size() - 1);
            for (int i = 0; i < n.anyOf().size(); i++) if (n.usable(i)) { name = n.anyOf().get(i); break; }
            boolean food = CombatTask.isFood(n);
            if (food) name = "Trout";                                         // food: a cheap, decent fish
            out.add(new TradeTask.Want(name, amount(name, n.consumable(), food)));
        }
        return out;
    }

    /**
     * How many to buy: one of a tool or quest item; 50 food (the user's choice, 2026-10-10); 500 feathers; 300
     * runes or arrows; 100 of any other supply.
     */
    static int amount(String name, boolean consumable, boolean food) {
        if (!consumable) return 1;
        if (food) return 50;
        String l = name.toLowerCase();
        if (l.equals("feather")) return 500;
        if (l.endsWith(" rune") || l.endsWith(" arrow")) return 300;
        return 100;
    }
}
