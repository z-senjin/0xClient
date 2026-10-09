package oxclient.framework;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import oxclient.api.Bank;
import oxclient.api.Dialog;
import oxclient.api.Game;
import oxclient.api.Inventory;
import oxclient.api.Items;
import oxclient.api.Scenery;
import oxclient.api.Walker;

/**
 * One trip to the bank, as a small state machine other tasks drive: walk to the nearest bank, open it,
 * deposit everything except the items to keep, withdraw what is wanted, close it.
 *
 * <p>Call {@link #tick} until it returns DONE or FAILED. Keep and want lists are item NAME parts ("axe",
 * "Small fishing net"), matched ignoring case. A withdrawal of something the bank does not hold fails
 * the trip with that name, so the caller can decide (buy it, or stop).</p>
 */
public final class BankTrip {

    private enum Step { GO, OPEN, DEPOSIT, WITHDRAW, CLOSE }

    private final List<String> keep, want;
    private final Random rng = new Random();
    private Step step = Step.GO;
    private Walker.Route route;
    private Place bankPlace;
    private long waitUntil, stepSince, lastChange;
    private int lastUsed = -1, openTries;
    private String state = "", failure = "";

    public BankTrip(List<String> keep, List<String> want) {
        this.keep = new ArrayList<>(keep);
        this.want = new ArrayList<>(want);
    }

    public String state() { return state; }

    public String failure() { return failure; }

    public Task.Result tick(long now) {
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
        if (bankPlace == null) bankPlace = Places.nearestBank();
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
        if (Bank.isOpen()) { to(Step.DEPOSIT, now); lastChange = now; return Task.Result.CONTINUE; }
        if (now - stepSince < 6000 && openTries > 0) { state = "waiting for the bank to open"; return Task.Result.CONTINUE; }
        if (++openTries > 4) { failure = "the bank never opened"; return Task.Result.FAILED; }
        Scenery.SceneObject booth = Scenery.nearest(15, o -> Bank.bankOption(o) > 0);
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
        for (String w : want) {
            if (Gear.carrying(w) || Gear.wearing(w)) continue;
            int[] f = Bank.contents();
            int slot = -1;
            for (int i = 0; i + 1 < f.length; i += 2) if (f[i + 1] > 0 && Items.name(f[i]).toLowerCase().contains(w.toLowerCase())) { slot = i / 2; break; }
            if (slot < 0) { failure = "the bank has no " + w; return Task.Result.FAILED; }
            if (now - stepSince > 15000) { failure = "withdrawing " + w + " does nothing -- check Bank.opWithdraw1 (now " + Bank.opWithdraw1 + ")"; return Task.Result.FAILED; }
            state = "withdrawing " + w;
            Bank.withdraw(slot, Bank.opWithdraw1);
            waitUntil = now + 900 + rng.nextInt(500);
            return Task.Result.CONTINUE;
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
