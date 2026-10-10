package oxclient.framework;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import oxclient.api.Game;
import oxclient.api.Skills;
import oxclient.persist.JsonStore;

/**
 * Runs the account builder's goals in a random order, a stint at a time (account builder plan, Phase 1).
 *
 * <p>Each stint: pick one goal at random from those not reached, not parked and with a training method --
 * never the same goal twice in a row while another is available -- and train it for a random number of
 * minutes between the stint settings, or until it is reached. The method is chosen from the skill's level
 * <i>now</i>, at a random one of its spots; when the level passes into the next method's band, the next
 * method is chosen. No scoring: every eligible goal is equally likely.</p>
 *
 * <p>A spot where a task failed (attacked, nothing to gather, no route) is avoided for 15 minutes while
 * another spot for the method exists. A method whose items the bank does not have (no feathers) is set
 * aside for 15 minutes and the next method down is used instead (shrimps instead of trout) -- that does
 * not count as a failure. A method whose items the bank was last seen without ({@link BankCache}) is
 * skipped the same way, without walking to the bank to find out. What was missing is kept on a shopping
 * list ({@link #wants}) for the Grand Exchange (plan, Phase 3).</p>
 *
 * <p>A goal whose task fails 3 times in a row is <b>parked</b> for 15 minutes, with the reason kept for the
 * panel; the stint ends and another goal is picked. The current goal, the stint's end and the parked goals
 * are saved to {@code ~/.0xclient/scripts/account-builder.json}, so a restart carries on.</p>
 */
public final class Picker {

    static final int FAILS_TO_PARK = 3;
    static final long PARK_MS = 15 * 60_000L;

    private final String saveKey;
    private final List<Goal> goals;
    private final boolean bank;
    private final Random rng = new Random();

    private int stintMinMinutes = 20, stintMaxMinutes = 45;
    private Goal current;
    private String lastKey = "";
    private long stintEnd;
    private Task task;
    private boolean started;
    private int fails;
    private long nextTick, retryAt;
    private String note = "";
    private final Map<String, Long> parkedUntil = new LinkedHashMap<>();
    private final Map<String, String> parkedWhy = new LinkedHashMap<>();
    private final Map<String, Long> badSpotUntil = new LinkedHashMap<>();     // by spot name
    private final Map<String, Long> lackingUntil = new LinkedHashMap<>();     // by method label
    private final Map<Need, String> wants = new LinkedHashMap<>();            // item -> for which method
    private TradeTask trade;
    private String lastState = "";
    private long lastTrade;
    private boolean tradeOn = true;
    static final long TRADE_EVERY_MS = 30 * 60_000L;
    static final int SELL_AT = 300;                                           // gathered items banked before a selling trip

    /** @param saveKey identifies the profile and options: a different one starts fresh */
    public Picker(String saveKey, List<Goal> goals, boolean bank) {
        this.saveKey = saveKey;
        this.goals = new ArrayList<>(goals);
        this.bank = bank;
        load();
    }

    /** Stint length range, in minutes (from the plugin's settings; read every tick). */
    public void stintMinutes(int min, int max) {
        stintMinMinutes = Math.max(1, Math.min(min, max));
        stintMaxMinutes = Math.max(stintMinMinutes, Math.max(min, max));
    }

    /** Call every frame; it decides a few times a second. */
    public void tick() {
        if (!Game.ready()) return;
        long now = System.currentTimeMillis();
        if (now < nextTick) return;
        nextTick = now + 150;

        pruneWants();
        if (trade != null) {                                                  // a Grand Exchange trip runs between stints
            Task.Result tr;
            try { tr = trade.tick(now); } catch (RuntimeException e) { tr = Task.Result.FAILED; log("trade threw " + e); }
            if (!trade.state().equals(lastState)) { lastState = trade.state(); if (!lastState.isEmpty()) log("trade doing: " + lastState); }
            if (tr != Task.Result.CONTINUE) {
                note = "trade: " + (tr == Task.Result.DONE ? trade.summary() : trade.failure());
                log(note);
                trade = null;
                lastTrade = now;
                save();
            }
            return;
        }
        if (current != null && (current.reached() || now >= stintEnd)) endStint(current.reached() ? current + " reached" : "stint over");
        if (current == null && tradeDue(now)) {
            trade = new TradeTask(buyList(), keepList());
            trade.start();
            log("Grand Exchange trip: buy " + buyList());
            return;
        }
        if (current == null && !pick(now)) return;
        if (now < retryAt) return;
        if (task == null) {
            task = taskFor(current);
            started = false;
            if (task == null) { park(current, "no usable method at level " + Skills.level(current.skill()) + lackingNote(), now); return; }
            log("task: " + task.name());
        }
        if (!started) { task.start(); started = true; lastState = ""; }
        if (!task.state().equals(lastState)) { lastState = task.state(); if (!lastState.isEmpty()) log("doing: " + lastState); }
        Task.Result r;
        String threw = null;
        try {
            r = task.tick(now);
        } catch (RuntimeException e) {
            r = Task.Result.FAILED;
            threw = task.name() + " threw " + e;
        }
        if (r == Task.Result.DONE) { log("task done: " + task.name()); task = null; fails = 0; }
        else if (r == Task.Result.FAILED) {
            note = threw != null ? threw : task.name() + ": " + task.failure();
            log("failed: " + note);
            if (task instanceof Spotted gt && threw == null) {
                if (gt.missingSupplies()) {                       // not a failure: use a lower method
                    lackingUntil.put(methodLabel, now + PARK_MS);
                    if (gt.missingItem() != null) wants.put(gt.missingItem(), methodLabel);
                    task = null;
                    return;
                }
                if (!Places.BANKS.contains(gt.spot())) badSpotUntil.put(gt.spot().name(), now + PARK_MS);   // never avoid a bank
            }
            task = null;
            if (++fails >= FAILS_TO_PARK) park(current, note, now);
            else retryAt = now + 5000;                            // try again, at a freshly picked spot
        }
    }

    /** Pick the next goal; false when there is none to work on. */
    private boolean pick(long now) {
        parkedUntil.entrySet().removeIf(e -> e.getValue() <= now);
        parkedWhy.keySet().retainAll(parkedUntil.keySet());
        List<Goal> eligible = new ArrayList<>();
        for (Goal g : goals) if (!g.reached() && Methods.has(g.skill()) && !parkedUntil.containsKey(g.key())) eligible.add(g);
        if (eligible.size() > 1) eligible.removeIf(g -> g.key().equals(lastKey));
        if (eligible.isEmpty()) {
            String n = parkedUntil.isEmpty() ? "every goal with a method is reached" : "every goal is parked";
            if (!n.equals(note)) log(n + "; parked: " + parked());
            note = n;
            return false;
        }
        current = eligible.get(rng.nextInt(eligible.size()));
        log("picked " + current + " (level " + Skills.level(current.skill()) + ") from " + eligible.size() + " eligible");
        int span = stintMaxMinutes - stintMinMinutes;
        stintEnd = now + (stintMinMinutes + (span > 0 ? rng.nextInt(span + 1) : 0)) * 60_000L;
        task = null;
        fails = 0;
        note = "";
        save();
        return true;
    }

    private String methodLabel = "";

    private Task taskFor(Goal g) {
        long now = System.currentTimeMillis();
        lackingUntil.entrySet().removeIf(e -> e.getValue() <= now);
        badSpotUntil.entrySet().removeIf(e -> e.getValue() <= now);
        int level = Skills.level(g.skill());
        Methods.Method m = Methods.forLevel(g.skill(), level, x -> {
            if (lackingUntil.containsKey(x.label())) return false;
            List<Need> all = Methods.allNeeds(x);
            if (x.fight() != null && BankCache.known() && !Loadouts.hasWeapon(x.fight().style())) {   // no weapon for the style
                for (Loadouts.Piece p : Loadouts.upgrades(x.fight().style()))
                    if (p.slot() == oxclient.api.Equipment.WEAPON) wants.put(Need.tool(p.name()), x.label());
                return false;
            }
            if (BankCache.couldSupply(all)) return true;
            for (Need n : all) if (!n.met() && !BankCache.couldHave(n)) wants.put(n, x.label());
            return false;
        });
        if (m == null) return null;
        methodLabel = m.label();
        int to = Math.min(g.target(), Methods.nextBand(g.skill(), level));
        if (m.fight() != null) {
            // weapon upgrades only, for now: armour for every slot would spend the coins before tools and supplies
            for (Loadouts.Piece p : Loadouts.upgrades(m.fight().style()))
                if (p.slot() == oxclient.api.Equipment.WEAPON) wants.putIfAbsent(Need.tool(p.name()), "weapon upgrade");
            return new CombatTask(m.label(), g.skill(), to, spot(m), m.fight());
        }
        if (m.process() != null)
            return new ProcessTask(m.label(), g.skill(), to, spot(m), m.process().mode(), m.needs(), m.process().input(),
                    m.process().objects(), m.process().objectIds());
        return new GatherTask(m.label(), g.skill(), to, spot(m), m.resource(), m.needs(), bank);
    }

    /** A random spot of the method, leaving out ones that just failed (unless every one did). */
    private Place spot(Methods.Method m) {
        if (m.spots().isEmpty()) return Places.nearestBank();             // a method done "at the bank" (campfire)
        List<Place> ok = new ArrayList<>();
        for (Place p : m.spots()) if (!badSpotUntil.containsKey(p.name())) ok.add(p);
        if (ok.isEmpty()) ok = m.spots();
        return ok.get(rng.nextInt(ok.size()));
    }

    private void endStint(String why) {
        if (current != null) { lastKey = current.key(); log("stint ended: " + why); }
        current = null;
        task = null;
        note = why;
        save();
    }

    private void park(Goal g, String why, long now) {
        log("parked " + g + " for 15 min: " + why);
        parkedUntil.put(g.key(), now + PARK_MS);
        parkedWhy.put(g.key(), why);
        endStint("parked " + g + ": " + why);
    }

    /** End the current stint now; the next tick picks again. */
    public void skip() { if (current != null) endStint("skipped " + current); }

    /** Forget parked goals. */
    public void unpark() { parkedUntil.clear(); parkedWhy.clear(); badSpotUntil.clear(); lackingUntil.clear(); save(); }

    private String lackingNote() { return lackingUntil.isEmpty() ? "" : " (missing items for: " + String.join(", ", lackingUntil.keySet()) + ")"; }

    /** One line in the OXC_LOG file (System.out goes there), so a test run explains every decision. */
    static void log(String what) { System.out.println("[builder] " + what); }

    /** Drop shopping-list entries that have turned up (carried, or in the bank). Called from tick only. */
    private void pruneWants() { wants.keySet().removeIf(n -> n.met() || (BankCache.known() && BankCache.couldHave(n))); }

    /** Turn Grand Exchange trips on or off (the plugin's setting). */
    public void trading(boolean on) { tradeOn = on; }

    /** A trip is due: trading is on, none in the last half hour, and something to buy or plenty to sell. */
    private boolean tradeDue(long now) {
        if (!tradeOn || now - lastTrade < TRADE_EVERY_MS || !BankCache.known()) return false;
        int products = 0;
        List<String> keep = keepList();
        for (String p : TradeTask.PRODUCTS) if (!keep.contains(p)) products += BankCache.count(p);
        // worth the walk: things to sell, or at least a thousand coins (a new account's few coins cannot buy
        // anything and would send it to the exchange every half hour: audit 2026-10-10)
        boolean funds = products >= 50 || BankCache.count("Coins") >= 1000;
        return funds && (!wants.isEmpty() || products >= SELL_AT);
    }

    /**
     * What a trip must not sell: the inputs of goals not yet reached (raw fish while Cooking trains, logs while
     * Firemaking does -- the user's choice, 2026-10-10).
     */
    private List<String> keepList() {
        List<String> out = new ArrayList<>();
        for (Goal g : goals) {
            if (g.reached() || !Methods.has(g.skill())) continue;
            for (Methods.Method m : Methods.F2P.getOrDefault(g.skill(), List.of()))
                if (m.process() != null) out.addAll(m.process().input().anyOf());
        }
        return out;
    }

    /** The shopping list as things to buy: the best member usable now, in a round amount. */
    private List<TradeTask.Want> buyList() {
        List<TradeTask.Want> out = new ArrayList<>();
        pruneWants();                                                         // drops what has turned up since
        for (Need n : wants.keySet()) {
            String name = n.anyOf().get(n.anyOf().size() - 1);
            for (int i = 0; i < n.anyOf().size(); i++) if (n.usable(i)) { name = n.anyOf().get(i); break; }
            if (n.anyOf().contains("Trout")) name = "Trout";                  // food: a cheap, decent fish
            String l = name.toLowerCase();
            int amount = !n.consumable() ? 1 : l.equals("feather") ? 500 : l.endsWith(" rune") ? 300 : l.endsWith(" arrow") ? 300 : 100;
            out.add(new TradeTask.Want(name, amount));
        }
        return out;
    }

    // ---- for the panel

    /** The shopping list: items a method needed that neither the inventory nor the bank had, and for what. */
    public List<String> wants() {
        List<String> out = new ArrayList<>();
        for (Map.Entry<Need, String> e : wants.entrySet()) out.add(e.getKey().label() + " (for " + e.getValue() + ")");
        return out;
    }

    public Goal current() { return current; }

    public Task task() { return trade != null ? trade : task; }

    /** Milliseconds left in the stint, or 0. */
    public long stintLeft() { return current == null ? 0 : Math.max(0, stintEnd - System.currentTimeMillis()); }

    /** The last thing worth saying: why a stint ended, why a task failed. */
    public String note() { return note; }

    /** Parked goals and why, e.g. "Mining 99: no tool (pickaxe)". */
    public List<String> parked() {
        List<String> out = new ArrayList<>();
        for (Goal g : goals) if (parkedWhy.containsKey(g.key())) out.add(g + ": " + parkedWhy.get(g.key()));
        return out;
    }

    /** Goals with no training method yet, e.g. "Attack", for the panel's "not built yet" line. */
    public List<String> unbuilt() {
        List<String> out = new ArrayList<>();
        for (Goal g : goals) if (!Methods.has(g.skill()) && !g.reached()) out.add(g.skill().displayName());
        return out;
    }

    // ---- saving

    private void save() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("profile", saveKey);
        m.put("current", current == null ? "" : current.key());
        m.put("stintEnd", stintEnd);
        m.put("last", lastKey);
        m.put("lastTrade", lastTrade);
        Map<String, Object> p = new LinkedHashMap<>();
        for (Map.Entry<String, Long> e : parkedUntil.entrySet()) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("until", e.getValue());
            one.put("why", parkedWhy.getOrDefault(e.getKey(), ""));
            p.put(e.getKey(), one);
        }
        m.put("parked", p);
        Path f = file();
        if (f != null) JsonStore.write(f, m);
    }

    private void load() {
        Path f = file();
        Map<String, Object> s = f == null ? null : JsonStore.read(f);
        if (s == null || !saveKey.equals(s.get("profile"))) return;
        if (s.get("last") instanceof String l) lastKey = l;
        if (s.get("lastTrade") instanceof Number t) lastTrade = t.longValue();
        if (s.get("parked") instanceof Map<?, ?> p) {
            for (Map.Entry<?, ?> e : p.entrySet()) {
                if (e.getValue() instanceof Map<?, ?> one && one.get("until") instanceof Number u) {
                    parkedUntil.put(String.valueOf(e.getKey()), u.longValue());
                    parkedWhy.put(String.valueOf(e.getKey()), String.valueOf(one.get("why")));
                }
            }
        }
        long end = s.get("stintEnd") instanceof Number n ? n.longValue() : 0;
        if (s.get("current") instanceof String c && !c.isEmpty() && end > System.currentTimeMillis()) {
            for (Goal g : goals) if (g.key().equals(c)) { current = g; stintEnd = end; }
        }
    }

    private Path file() {
        return Account.file("account-builder");               // per character (the user's choice)
    }
}
