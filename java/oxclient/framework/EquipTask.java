package oxclient.framework;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import oxclient.api.Equipment;
import oxclient.api.Inventory;

/**
 * Put on the best gear you own for a combat style ({@link Loadouts#bestOwned}): fetch what is in the bank,
 * then take each piece's "Wield" or "Wear" option (by name, from the item definition). Pieces that are
 * replaced end up in the inventory and go back to the bank on the next bank trip.
 *
 * <p>For ranged it also wields the best arrows the bow can fire (all of them). NOT SEEN WORKING YET: the
 * equip options go through the inventory option numbering, of which only Drop has been seen working.</p>
 */
public final class EquipTask extends BaseTask {

    private enum Step { CHECK, BANK, WEAR }

    private final Loadouts.Style style;
    private Step step = Step.CHECK;
    private BankTrip trip;
    private int trips;
    private final Map<String, Integer> tries = new HashMap<>();

    public EquipTask(Loadouts.Style style) { this.style = style; }

    @Override public String name() { return "Gear up for " + style.name().toLowerCase(); }

    @Override public void start() { step = Step.CHECK; trip = null; trips = 0; tries.clear(); }

    /** The pieces (and arrows) to have on, as name -> slot. */
    private Map<String, Integer> wanted() {
        Map<String, Integer> w = new java.util.LinkedHashMap<>();
        for (Loadouts.Piece p : Loadouts.bestOwned(style)) w.put(p.name(), p.slot());
        if (style == Loadouts.Style.RANGED) { String a = Loadouts.arrows(); if (a != null) w.put(a, Equipment.AMMO); }
        return w;
    }

    private static boolean worn(String name, int slot) { return Equipment.name(slot).equalsIgnoreCase(name); }

    @Override
    protected Result step(long now) {
        switch (step) {
            case CHECK -> {
                Map<String, Integer> w = wanted();
                List<String> missing = new ArrayList<>();
                for (Map.Entry<String, Integer> e : w.entrySet()) if (!worn(e.getKey(), e.getValue())) missing.add(e.getKey());
                if (missing.isEmpty()) return Result.DONE;
                List<Need> fetch = new ArrayList<>();
                for (String m : missing) if (!Gear.carrying(m)) fetch.add(w.get(m) == Equipment.AMMO ? Need.supply(m) : Need.tool(m));
                if (fetch.isEmpty()) { step = Step.WEAR; return Result.CONTINUE; }
                if (++trips > 3) return fail("the bank did not hand over " + fetch.get(0).label());
                trip = new BankTrip(new ArrayList<>(w.keySet()), fetch);
                step = Step.BANK;
            }
            case BANK -> {
                state("fetching gear: " + trip.state());
                Result r = trip.tick(now);
                if (r == Result.FAILED) return fail("gear: " + trip.failure());
                if (r == Result.DONE) step = Step.CHECK;
            }
            case WEAR -> {
                for (Map.Entry<String, Integer> e : wanted().entrySet()) {
                    if (worn(e.getKey(), e.getValue())) continue;
                    int slot = Inventory.firstSlot(e.getKey());
                    if (slot < 0) continue;
                    int n = tries.merge(e.getKey(), 1, Integer::sum);
                    if (n > 4) return fail("wearing " + e.getKey() + " does nothing");
                    String op = Inventory.hasOption(slot, "Wield") ? "Wield" : "Wear";
                    state(op.toLowerCase() + "ing " + e.getKey());
                    Inventory.interact(slot, op);
                    sleep(now, 900, 1400);
                    return Result.CONTINUE;
                }
                step = Step.CHECK;
            }
        }
        return Result.CONTINUE;
    }
}
