package oxclient.framework;

import java.util.ArrayList;
import java.util.List;

import oxclient.api.Bank;
import oxclient.api.Dialog;
import oxclient.api.Game;
import oxclient.api.GrandExchange;
import oxclient.api.Interfaces;
import oxclient.api.Inventory;
import oxclient.api.Items;
import oxclient.api.Keyboard;
import oxclient.api.Local;
import oxclient.api.Scenery;
import oxclient.api.Walker;

/**
 * One trip to the Grand Exchange (account builder plan, Phase 3): sell everything gathered, buy the shopping
 * list, collect, and leave.
 *
 * <ol>
 *   <li>Walk to the Grand Exchange and open a bank booth there: deposit the inventory, switch to notes,
 *       withdraw every gathered product ({@link #PRODUCTS}) and the coins.</li>
 *   <li>Open the exchange. For each product: Sell on a free slot, offer it from the inventory (the whole
 *       stack), lower the price 5% twice (guide -10%, the user's choice), Confirm.</li>
 *   <li>For each wanted item: Buy on a free slot, type its name into the search, click the result, add to the
 *       quantity with the +100/+10/+1 buttons, raise the price 5% twice (guide +10%), Confirm. An offer the
 *       coins do not cover stays on the setup screen and is backed out of.</li>
 *   <li>Wait up to {@link #WAIT_MS} for offers to fill, collecting as they do; close. Offers still open are
 *       collected on the next trip.</li>
 * </ol>
 *
 * <p>Items the picker says to keep (the inputs of skills still training) are neither withdrawn nor sold.
 * Free-to-play has three offer slots. <b>NOT SEEN WORKING YET.</b> Every button was hooked from the user's
 * own clicks (2026-10-10) except Collect (found by its text); typing the search goes through posted keys,
 * which have not been seen to work in this client (posted Space did not continue a dialogue).</p>
 */
public final class TradeTask extends BaseTask {

    /** What gathering produces: the only things a trip sells. */
    public static final List<String> PRODUCTS = List.of("Logs", "Oak logs", "Willow logs", "Copper ore", "Tin ore",
            "Iron ore", "Raw shrimps", "Raw anchovies", "Raw trout", "Raw salmon", "Cowhide");

    static final long WAIT_MS = 90_000;

    private enum Step { WALK, BANK_OPEN, BANK, GE_OPEN, ABORT, SELL, BUY, WAIT, CLOSE }

    /** An item to buy, and how many. */
    public record Want(String name, int amount) {}

    private final List<Want> buys;
    private Step step = Step.WALK;
    private Walker.Route route;
    private long stepAt, waitStart;
    private int bankStage, tries;
    private final List<String> sold = new ArrayList<>(), bought = new ArrayList<>(), skipped = new ArrayList<>();

    private final List<String> keep;
    private volatile boolean restricted;

    /** The account may not trade yet (a new account): stop offering and close the exchange. */
    @Override
    public void onEvent(ChatEvent event, String line) {
        super.onEvent(event, line);
        if (event == ChatEvent.TRADE_RESTRICTED) restricted = true;
    }

    /** @param keep items never to sell (the inputs of skills still training) */
    public TradeTask(List<Want> buys, List<String> keep) { this.buys = new ArrayList<>(buys); this.keep = List.copyOf(keep); }

    @Override public String name() { return "Grand Exchange trip (sell gathered, buy " + buys.size() + ")"; }

    @Override public void start() { step = Step.WALK; route = null; bankStage = 0; tries = 0; }

    /** What happened, for the panel's "last" line. */
    public String summary() { return "sold " + sold + ", bought " + bought + (skipped.isEmpty() ? "" : ", skipped " + skipped); }

    private void to(Step s, long now) { step = s; stepAt = now; tries = 0; }

    @Override
    protected Result step(long now) {
        Local me = Game.me();
        if (Dialog.canContinue()) { Dialog.continueDialog(); sleep(now, 600, 900); return Result.CONTINUE; }
        // live 2026-10-10 a new account went on trying to sell after the game said it may not trade yet
        if (restricted && (step == Step.ABORT || step == Step.SELL || step == Step.BUY || step == Step.WAIT)) {
            restricted = false;
            skipped.add("everything (the account may not trade yet)");
            to(Step.CLOSE, now);
        }
        switch (step) {
            case WALK -> {
                Place ge = Places.GRAND_EXCHANGE;
                if (ge.distance(me.worldX(), me.worldY(), me.plane()) <= ge.radius()) { to(Step.BANK_OPEN, now); return Result.CONTINUE; }
                if (route == null) route = new Walker.Route(ge.x(), ge.y(), ge.plane(), 3);
                state("walking to the Grand Exchange");
                Walker.Status st = route.step();
                if (st == Walker.Status.NO_ROUTE) return fail("no route to the Grand Exchange");
                if (st == Walker.Status.STUCK) return fail("stuck on the way to the Grand Exchange");
            }
            case BANK_OPEN -> {
                // click first, read after -- and only once standing still: "open" only means "loaded" (the bank
                // stays loaded once closed), so it cannot tell that the bank opened THIS time
                if (tries > 0 && Bank.isOpen() && now - stepAt > 1500 && settled(me, now)) { to(Step.BANK, now); bankStage = 0; return Result.CONTINUE; }
                if (++tries > 5) return fail("the Grand Exchange bank never opened");
                Scenery.SceneObject booth = Scenery.nearest(15, o -> Bank.bankOption(o) > 0);
                if (booth == null) return fail("no bank booth at the Grand Exchange");
                state("opening the bank");
                booth.interact(Bank.bankOption(booth));
                stepAt = now;
                sleep(now, 2000, 2800);
            }
            case BANK -> { return bank(now); }
            case GE_OPEN -> {
                if (tries > 0 && GrandExchange.isOpen() && now - stepAt > 1500 && settled(me, now)) { to(Step.ABORT, now); return Result.CONTINUE; }
                if (++tries > 5) return fail("the exchange never opened");
                state("opening the exchange");
                if (!GrandExchange.open()) return fail("no Grand Exchange booth in sight");
                stepAt = now;
                sleep(now, 2000, 2800);
            }
            case ABORT -> { return abortOld(now); }
            case SELL -> { return sell(now); }
            case BUY -> { return buy(now); }
            case WAIT -> {
                if (GrandExchange.canCollect()) { state("collecting"); GrandExchange.collect(); sleep(now, 1200, 1600); return Result.CONTINUE; }
                boolean open = false;
                for (int i = 0; i < 3; i++) open |= !GrandExchange.slotEmpty(i);
                if (!open || now - waitStart > WAIT_MS) { to(Step.CLOSE, now); return Result.CONTINUE; }
                state("waiting for offers (" + (now - waitStart) / 1000 + "s)");
                sleep(now, 2500, 3500);
            }
            case CLOSE -> {
                // once: "open" stays true after closing (loaded), so a loop on it never ends (audit 2026-10-10)
                if (tries++ == 0) { state("closing the exchange"); GrandExchange.closeButton(); sleep(now, 800, 1200); return Result.CONTINUE; }
                return Result.DONE;
            }
        }
        return Result.CONTINUE;
    }

    /** At the bank: deposit all, notes on, withdraw every product and the coins; one click per tick. */
    private Result bank(long now) {
        switch (bankStage) {
            case 0 -> { state("depositing"); Bank.depositInventory(); bankStage = 1; sleep(now, 900, 1300); }
            case 1 -> { state("switching to notes"); Bank.withdrawAsNotes(); bankStage = 2; sleep(now, 700, 1000); }
            case 2 -> {
                int[] f = Bank.contents();
                for (int i = 0; i + 1 < f.length; i += 2) {
                    if (f[i] < 0 || f[i + 1] <= 0) continue;
                    String n = Items.name(f[i]);
                    boolean coins = n.equalsIgnoreCase("Coins");
                    if (!coins && (!PRODUCTS.contains(n) || wanted(n))) continue;
                    if (Inventory.count(n) > 0 || Inventory.free() == 0) continue;
                    state("withdrawing " + n);
                    Bank.withdraw(i / 2, Bank.opWithdrawAll);
                    if (++tries > 30) { bankStage = 3; tries = 0; return Result.CONTINUE; }
                    sleep(now, 900, 1300);
                    return Result.CONTINUE;
                }
                bankStage = 3;
                tries = 0;
            }
            case 3 -> { state("back to items"); Bank.withdrawAsItems(); bankStage = 4; sleep(now, 700, 1000); }   // later bank trips want items
            case 4 -> { state("closing the bank"); Bank.close(); bankStage = 5; sleep(now, 800, 1100); }
            default -> to(Step.GE_OPEN, now);
        }
        return Result.CONTINUE;
    }

    private final int[] aborts = new int[3];

    /**
     * Offers already in the slots (left from an earlier trip, or placed by hand) are aborted and collected
     * first, so the trip has its slots -- the user's choice, 2026-10-10 (a trip found every slot taken).
     */
    private Result abortOld(long now) {
        if (GrandExchange.canCollect()) { state("collecting"); GrandExchange.collect(); sleep(now, 1200, 1600); return Result.CONTINUE; }
        for (int i = 0; i < 3; i++) {
            if (GrandExchange.slotEmpty(i)) continue;
            String shows = GrandExchange.slotText(i);
            if (aborts[i] >= 2) continue;                      // two tries: leave it (the option number is unverified)
            if (aborts[i] > 0) System.out.println("[builder] slot " + i + " still shows \"" + shows + "\" after abort try " + aborts[i]);
            aborts[i]++;
            state("aborting the old offer in slot " + i + " (" + shows + ")");
            GrandExchange.abort(i);
            sleep(now, 1500, 2000);
            return Result.CONTINUE;
        }
        to(Step.SELL, now);
        return Result.CONTINUE;
    }

    private int pendingOffer = -1;      // inventory slot to offer once the Sell screen is up
    private int backs;
    private boolean priced;

    private String lastOfferLog = "";

    private Result sell(long now) {
        String d = GrandExchange.describeOffer();
        if (!d.equals(lastOfferLog)) { lastOfferLog = d; System.out.println("[ge] " + d); }   // checks Interfaces.shown live
        if (GrandExchange.settingUp()) {                       // an offer is on screen: price it and confirm, once
            if (!priced && now - stepAt > 1500) {
                state("pricing at guide -10%");
                GrandExchange.nudgePrice(-2);
                GrandExchange.confirmOffer();
                priced = true;
                stepAt = now;
                sleep(now, 1500, 2000);
            } else if (priced && now - stepAt > 2500) {       // Confirm did not take: back out
                if (++backs > 3) {                             // Back does not leave it either: stop selling
                    System.out.println("[builder] the sell screen will not take an offer or go back; stopping sales");
                    to(Step.BUY, now);
                    return Result.CONTINUE;
                }
                Interfaces.click(GrandExchange.BACK, -1, 1);
                priced = false;
                sleep(now, 900, 1200);
            }
            return Result.CONTINUE;
        }
        priced = false;
        if (pendingOffer >= 0) {
            if (now - stepAt < 1000) return Result.CONTINUE;
            GrandExchange.offer(pendingOffer);
            pendingOffer = -1;
            stepAt = now;
            sleep(now, 900, 1200);
            return Result.CONTINUE;
        }
        int[] ids = Inventory.ids();
        for (int s = 0; s < ids.length; s++) {
            String n = ids[s] < 0 ? "" : Items.name(ids[s]);
            if (!PRODUCTS.contains(n) || sold.contains(n) || wanted(n)) continue;   // never sell what it came to buy
            int slot = GrandExchange.freeSlot();
            if (slot < 0) { state("no free slot to sell in"); break; }
            if (!buys.isEmpty() && freeSlots() <= 1) break;   // keep a slot for buying (audit 2026-10-10)
            state("selling " + n);
            GrandExchange.sellSlot(slot);
            sold.add(n);
            pendingOffer = s;
            stepAt = now;
            return Result.CONTINUE;
        }
        to(Step.BUY, now);
        return Result.CONTINUE;
    }

    private boolean confirmed;
    private int opens;

    private Result buy(long now) {
        if (buys.isEmpty()) { waitStart = now; to(Step.WAIT, now); return Result.CONTINUE; }
        Want w = buys.get(0);
        if (GrandExchange.settingUp()) {
            if (!confirmed) {
                state("buying " + w.amount() + " x " + w.name() + " at guide +10%");
                GrandExchange.addQuantity(Math.max(0, w.amount() - 1));
                GrandExchange.nudgePrice(2);
                GrandExchange.confirmOffer();
                confirmed = true;
                stepAt = now;
                sleep(now, 1500, 2000);
            } else if (now - stepAt > 2500) {                  // still on screen after Confirm: not enough coins
                Interfaces.click(GrandExchange.BACK, -1, 1);
                skipped.add(w.name() + " (coins)");
                next();
                sleep(now, 900, 1200);
            }
            return Result.CONTINUE;
        }
        if (confirmed) { bought.add(w.name()); next(); return Result.CONTINUE; }    // the offer went in
        if (now - stepAt < 1200) return Result.CONTINUE;
        if (searched) {                                         // typed last tick: pick the result
            if (!GrandExchange.pickResult(w.name())) {
                // typing does not reach the game's search (live 2026-10-10: nothing appeared in the box; posted keys
                // also failed to continue a dialogue earlier). Leave the offer and skip every buy this trip rather
                // than typing the next name into a prompt that is still open.
                System.out.println("[builder] buying " + w.name() + ": no search result (typed text did not reach the game); skipping the buys");
                Interfaces.click(GrandExchange.BACK, -1, 1);
                for (Want rest : buys) skipped.add(rest.name() + " (search typing does not work yet)");
                buys.clear();
                searched = false;
                waitStart = now;
                to(Step.WAIT, now);
                return Result.CONTINUE;
            }
            searched = false;
            stepAt = now;
            return Result.CONTINUE;
        }
        if (Interfaces.findContaining(162, "would you like to buy") == null) {         // the search prompt is not up yet
            int slot = GrandExchange.freeSlot();          // only checked here: the slots hide while an offer is set up
            if (slot < 0) { waitStart = now; to(Step.WAIT, now); return Result.CONTINUE; }   // full: the next trip buys the rest
            if (++opens > 3) { skipped.add(w.name() + " (no search)"); next(); return Result.CONTINUE; }
            state("opening a buy offer");
            GrandExchange.buySlot(slot);
            stepAt = now;
            sleep(now, 1200, 1600);
            return Result.CONTINUE;
        }
        state("searching for " + w.name());
        Keyboard.type(w.name());
        sleep(now, 1800, 2400);
        stepAt = now;
        searched = true;
        return Result.CONTINUE;
    }

    private boolean searched;

    private int lastX = -1, lastY = -1;
    private long stillSince;

    /** Standing on the same tile for 600 ms: a walk to a booth has finished. */
    private boolean settled(Local me, long now) {
        if (me.sceneX() != lastX || me.sceneY() != lastY) { lastX = me.sceneX(); lastY = me.sceneY(); stillSince = now; }
        return now - stillSince > 600;
    }

    private static int freeSlots() {
        int n = 0;
        for (int i = 0; i < 3; i++) if (GrandExchange.slotEmpty(i)) n++;
        return n;
    }

    private boolean wanted(String item) {
        for (String k : keep) if (k.equalsIgnoreCase(item)) return true;  // kept for a skill still training
        for (Want w : buys) if (w.name().equalsIgnoreCase(item)) return true;
        return false;
    }

    private void next() { buys.remove(0); confirmed = false; opens = 0; searched = false; }
}
