package oxclient.framework;

import oxclient.api.Bank;
import oxclient.api.Inventory;
import oxclient.api.Local;

/**
 * Eats carried food below half health -- the one place that does it, for fights ({@link CombatTask}) and for
 * a skilling task that is attacked ({@link Defense}).
 *
 * <p>Not with the bank open: eating there closed the bank mid-trip (live 2026-10-10). Three Eats in a row that
 * did not raise health mean the Eat option does nothing, and {@link #broken} says so instead of standing in a
 * fight eating forever.</p>
 */
final class Eater {

    /** Cooked food, any kind ({@link CombatTask#FOODS}). */
    static final Need FOOD = Need.supply(CombatTask.FOODS, CombatTask.FOOD);

    private int failed, lastHp = -1;

    static int hpPercent(Local me) { return me.health() * 100 / Math.max(1, me.maxHealth()); }

    /** Eat if below half health and food is carried. True when it clicked Eat (wait about 1.5 s after). */
    boolean eat(Local me) {
        if (hpPercent(me) >= 50) { lastHp = -1; return false; }
        if (FOOD.slot() < 0 || failed >= 3 || Bank.isOpen()) return false;
        if (lastHp >= 0 && me.health() <= lastHp) failed++;
        else failed = 0;
        lastHp = me.health();
        Inventory.interact(FOOD.slot(), "Eat");
        return true;
    }

    /** Food is carried and eating works. */
    boolean hasFood() { return FOOD.slot() >= 0 && failed < 3; }

    /** Three Eats did not raise health. */
    boolean broken() { return failed >= 3; }
}
