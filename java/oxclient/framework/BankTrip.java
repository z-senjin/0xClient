package oxclient.framework;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import oxclient.api.Bank;
import oxclient.api.Dialog;
import oxclient.api.Game;
import oxclient.api.Inventory;
import oxclient.api.Items;
import oxclient.api.Local;
import oxclient.api.Scenery;
import oxclient.api.Walker;

/**
 * One trip to the bank, as a small state machine other tasks drive: walk to the nearest bank, open it,
 * deposit everything except the items to keep, withdraw what is wanted, close it.
 *
 * <p>Call {@link #tick} until it returns DONE or FAILED. The keep list is item NAME parts ("Bronze axe",
 * "Feather"), matched ignoring case. What to fetch is a list of {@link Need}s: one of a tool (withdraw-1,
 * best first), or all of a supply (withdraw-all). Something the bank does not hold fails the trip and is
 * named in {@link #missing}, so the caller can decide (another method, buy it, or stop).</p>
 */
public final class BankTrip {

    private enum Step { GO, OPEN, DEPOSIT, WITHDRAW, CLOSE }

    private final List<String> keep;
    private final List<Need> want;
    private final Random rng = new Random();
    private Step step = Step.GO;
    private Walker.Route route;
    private Place bankPlace, toward;
    private long waitUntil, stepSince, lastChange;
    private int lastUsed = -1, openTries;
    private int lastX = -1, lastY = -1;
    private long stillSince;
    private Scenery.SceneObject booth;
    private int lastWithdrawId = -1, lastInv = -1, sameWithdraws;

    /** Bank items that withdraw nothing (placeholders), for the rest of the session. */
    static final java.util.Set<Integer> DUDS = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private String state = "", failure = "", missing = "";
    private Need missingNeed;

    /** Deposit everything but {@code keep}; fetch every unmet need. */
    public BankTrip(List<String> keep, List<Need> want) {
        this.keep = new ArrayList<>(keep);
        this.want = new ArrayList<>(want);
    }

    /** Use the bank on the way to {@code spot} (shortest walk to the bank and on to it), not just the nearest. */
    public BankTrip toward(Place spot) { this.toward = spot; return this; }

    /** Every name part of these needs (what a trip for them should keep in the inventory). */
    public static List<String> names(List<Need> needs) {
        List<String> out = new ArrayList<>();
        for (Need n : needs) out.addAll(n.anyOf());
        return out;
    }

    /** The need the bank did not have, when the trip failed for that reason; "" otherwise. */
    public String missing() { return missing; }

    /** The need itself, or null. */
    public Need missingNeed() { return missingNeed; }

    public String state() { return state; }

    public String failure() { return failure; }

    public Task.Result tick(long now) {
        BankCache.observe();
        if (now < waitUntil) return Task.Result.CONTINUE;
        if (Dialog.canContinue()) { Dialog.continueDialog(); waitUntil = now + 800; return Task.Result.CONTINUE; }
        switch (step) {
            case GO: return go(now);
            case OPEN: return open(now);
            case DEPOSIT: return deposit(now);
            case WITHDRAW: return withdraw(now);
            default:
                if (Bank.isOpen()) { Bank.close(); waitUntil = now + 600; }
                return Task.Result.DONE;
        }
    }

    private void to(Step s, long now) { step = s; stepSince = now; }

    private Task.Result go(long now) {
        Scenery.SceneObject booth = Scenery.nearest(15, o -> Bank.bankOption(o) > 0);
        if (booth != null) { to(Step.OPEN, now); return Task.Result.CONTINUE; }
        if (bankPlace == null) {
            bankPlace = toward != null ? Places.bankOnTheWay(toward) : Places.nearestBank();
            System.out.println("[builder] bank for this trip: " + bankPlace.name() + (toward != null ? " (on the way to " + toward.name() + ")" : " (nearest)"));
        }
        state = "walking to " + bankPlace.name();
        if (route == null) route = new Walker.Route(bankPlace.x(), bankPlace.y(), bankPlace.plane(), 3);
        Walker.Status st = route.step();
        if (st == Walker.Status.NO_ROUTE) { failure = "no route to " + bankPlace.name(); return Task.Result.FAILED; }
        if (st == Walker.Status.STUCK) { failure = "stuck on the way to " + bankPlace.name(); return Task.Result.FAILED; }
        if (st == Walker.Status.ARRIVED) {
            failure = "no bank booth or chest found at " + bankPlace.name() + " (check its tile in Places)";
            return Task.Result.FAILED;
        }
        return Task.Result.CONTINUE;
    }

    private Task.Result open(long now) {
        // Always click the booth first: "open" can only be read as "loaded", and the client keeps the bank loaded
        // after it closes -- a trip that trusted it skipped opening the bank (live 2026-10-10).
        // ...and only once you are standing still beside the booth: "loaded" is true from the first visit of the
        // session, so it cannot say the bank has opened THIS time (audit 2026-10-10).
        Local me = Game.me();
        if (me.sceneX() != lastX || me.sceneY() != lastY) { lastX = me.sceneX(); lastY = me.sceneY(); stillSince = now; }
        boolean beside = booth != null && Math.max(Math.abs(booth.sceneX() - me.sceneX()), Math.abs(booth.sceneY() - me.sceneY())) <= 2;
        if (Bank.isOpen() && openTries > 0 && now - stepSince > 1500 && beside && now - stillSince > 600) {
            Bank.withdrawAsItems();                           // a Grand Exchange trip may have left it on notes
            Bank.quantity(1);                                 // so a left click withdraws exactly one
            BankCache.sawEmpty();
            to(Step.DEPOSIT, now);
            lastChange = now;
            waitUntil = now + 500;
            return Task.Result.CONTINUE;
        }
        if (now - stepSince < 6000 && openTries > 0) { state = "waiting for the bank to open"; return Task.Result.CONTINUE; }
        if (++openTries > 4) { failure = "the bank never opened"; return Task.Result.FAILED; }
        booth = Scenery.nearest(15, o -> Bank.bankOption(o) > 0);
        if (booth == null) { to(Step.GO, now); return Task.Result.CONTINUE; }
        state = "opening " + booth.name();
        booth.interact(Bank.bankOption(booth));
        stepSince = now;
        waitUntil = now + 1200 + rng.nextInt(600);
        return Task.Result.CONTINUE;
    }

    private Task.Result deposit(long now) {
        int[] ids = Inventory.ids();
        int slot = -1;
        for (int i = 0; i < ids.length; i++) if (ids[i] >= 0 && !Gear.matches(ids[i], keep) && !Items.name(ids[i]).isEmpty()) { slot = i; break; }
        if (slot < 0) { to(Step.WITHDRAW, now); return Task.Result.CONTINUE; }
        int used = Inventory.used();
        if (used != lastUsed) { lastUsed = used; lastChange = now; }
        else if (now - lastChange > 6000) { failure = "deposits do nothing -- check Bank.opDepositAll (now " + Bank.opDepositAll + ")"; return Task.Result.FAILED; }
        state = "depositing " + Items.name(ids[slot]);
        Bank.depositAll(slot);
        waitUntil = now + 700 + rng.nextInt(500);
        return Task.Result.CONTINUE;
    }

    private Task.Result withdraw(long now) {
        int[] f = Bank.contents();
        if (f.length == 0 && !want.isEmpty()) {                // not shown yet: never read that as "the bank has none"
            if (now - stepSince > 5000) { failure = "the bank shows no contents"; return Task.Result.FAILED; }
            state = "waiting for the bank's contents";
            waitUntil = now + 500;
            return Task.Result.CONTINUE;
        }
        for (Need w : want) {
            int held = w.bestHeld();
            // the best usable member the bank has (a tool family is listed best first)
            int best = -1, slot = -1;
            for (int k = 0; k < w.anyOf().size() && best < 0; k++) {
                if (!w.usable(k)) continue;
                String n = w.anyOf().get(k).toLowerCase();
                for (int i = 0; i + 1 < f.length; i += 2)
                    if (f[i + 1] > 0 && !DUDS.contains(f[i]) && Items.name(f[i]).equalsIgnoreCase(n)) { best = k; slot = i / 2; break; }
            }
            boolean upgrade = best >= 0 && held >= 0 && best < held && !w.consumable();
            boolean topUp = w.consumable() && w.count() > 0 && held >= 0 && w.carried() < w.count() && best >= 0;
            if (held >= 0 && !upgrade && !topUp) continue;
            if (slot < 0) { missing = w.label(); missingNeed = w; failure = "the bank has no " + w.label(); return Task.Result.FAILED; }
            int short_ = w.count() - w.carried();
            int op = !w.consumable() ? Bank.opWithdraw1 : w.count() <= 0 ? Bank.opWithdrawAll : short_ >= 5 ? Bank.opWithdraw5 : Bank.opWithdraw1;
            if (w.count() > 0 && Inventory.free() == 0) continue;            // full: carry what fits
            if (now - stepSince > 15000) { failure = "withdrawing " + w.label() + " does nothing -- check the bank's withdraw option (now " + op + ")"; return Task.Result.FAILED; }
            // a withdrawal that changes nothing twice is a dud -- a bank PLACEHOLDER shows the name with nothing
            // behind it (live 2026-10-10: "Salmon" item 15446, withdraw-5 ten times, nothing came) -- so skip it
            int bankId = f[slot * 2], inv = Inventory.used() * 100000 + Inventory.amount(bankId);
            if (bankId == lastWithdrawId && inv == lastInv) {
                if (++sameWithdraws >= 2) {
                    DUDS.add(bankId);
                    BankCache.forget(Items.name(bankId), bankId);
                    System.out.println("[builder] bank item " + bankId + " (" + Items.name(bankId) + ") withdraws nothing: ignored (a placeholder?)");
                    sameWithdraws = 0;
                    stepSince = now;                          // the next item gets its own time
                    return Task.Result.CONTINUE;
                }
            } else sameWithdraws = 0;
            lastWithdrawId = bankId;
            lastInv = inv;
            state = (upgrade ? "upgrading to " : topUp ? "topping up " : "withdrawing ") + w.anyOf().get(best);
            Bank.withdraw(slot, op);
            waitUntil = now + 900 + rng.nextInt(500);
            return Task.Result.CONTINUE;
        }
        // put back tools that a better one replaced (worse members of a family, still in the inventory)
        int[] ids = Inventory.ids();
        for (Need w : want) {
            if (w.consumable() || w.anyOf().size() < 2) continue;
            int held = w.bestHeld();
            for (int i = 0; i < ids.length; i++) {
                if (ids[i] < 0) continue;
                int k = w.indexOf(Items.name(ids[i]));
                if (k < 0 || k == held) continue;
                if (now - stepSince > 25000) { to(Step.CLOSE, now); return Task.Result.CONTINUE; }   // never stall on tidying
                state = "putting back " + Items.name(ids[i]);
                Bank.deposit(i, Bank.opDepositAll);
                waitUntil = now + 800 + rng.nextInt(400);
                return Task.Result.CONTINUE;
            }
        }
        to(Step.CLOSE, now);
        return Task.Result.CONTINUE;
    }

    /** True when the inventory is back to only kept items (used by callers that want a check). */
    public static boolean onlyKept(List<String> keep) {
        if (!Game.ready()) return false;
        for (int id : Inventory.ids()) if (id >= 0 && !Gear.matches(id, keep)) return false;
        return true;
    }
}
