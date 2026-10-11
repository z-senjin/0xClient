package oxclient.framework;

import java.util.ArrayList;
import java.util.List;

import oxclient.api.Chat;
import oxclient.api.Dialog;
import oxclient.api.Game;
import oxclient.api.Interfaces;
import oxclient.api.Inventory;
import oxclient.api.Keyboard;
import oxclient.api.Local;
import oxclient.api.Scenery;
import oxclient.api.Skill;
import oxclient.api.Skills;
import oxclient.api.Walker;

/**
 * Train a processing skill to a level: take a bank-load of the input, process it, bank, repeat.
 *
 * <ul>
 *   <li>{@link Mode#USE_ON_OBJECT} -- Cooking: use the raw food on a range, pick "All" in the make-X chooser
 *       (interface 270), wait while it cooks.</li>
 *   <li>{@link Mode#LIGHT} -- Firemaking in a line: use the tinderbox on the logs; the game steps you west
 *       after each fire. "You can't light a fire here" (or nothing happening) moves you a few tiles.</li>
 *   <li>{@link Mode#CAMPFIRE} -- Firemaking the Forester's Campfire way (the user's choice): step a few tiles
 *       out of the bank, light one fire, then use the logs on it so they all go on the same fire (choosing
 *       "All" if the game asks how many). A fire that goes out is lit again.</li>
 * </ul>
 *
 * <p>The input is the best one the account can use that the bank has ({@link Need} family, best first). An
 * empty bank fails the task with {@link #missingSupplies} set, so the picker sets the method aside and puts
 * the input on the shopping list.</p>
 *
 * <p><b>NOT SEEN WORKING YET.</b> Use-item-on-object and use-item-on-item go through the senders verified for
 * a manual click (ACT_ON_LOC, ACT_ON_ITEM); the make-X "All" button is found by its text and is a guess until
 * the probe run maps interface 270.</p>
 */
public final class ProcessTask extends BaseTask implements Spotted {

    public enum Mode { USE_ON_OBJECT, LIGHT, CAMPFIRE }

    /** What a lit fire is called before and after logs are added to it. */
    static final List<String> FIRES = List.of("Forester's Campfire", "Fire", "Campfire");

    /** The make-X chooser ("How many would you like to cook?"). */
    static final int MAKE_X = 270;

    private enum Phase { CHECK, SUPPLY, WALK, ACT, MAKEX, WAIT }

    private final List<Integer> objectIds;

    private final String label;
    private final Skill skill;
    private final int target;
    private final Place spot;
    private final Mode mode;
    private final List<Need> tools;
    private final Need input;
    private final List<String> objects;
    private final List<String> keep;

    private Phase phase = Phase.CHECK;
    private BankTrip trip;
    private Walker.Route route;
    private long acted, lastAnim, makexAt, progressAt;
    private int progressXp = -1, progressCount = -1;
    private int fails;
    private boolean missingSupplies;
    private int asides, tollTrips;
    private volatile boolean cantLight;
    private final Defense defense;

    @Override
    public void onEvent(ChatEvent event, String line) {
        super.onEvent(event, line);
        if (event == ChatEvent.CANT_LIGHT) cantLight = true;   // the picker is the chatbox's only reader
    }
    private Need missingItem;

    /**
     * @param tools    items kept (a tinderbox); may be empty
     * @param input    what gets used up, best first (raw fish, logs)
     * @param objects  for USE_ON_OBJECT, the object names to use it on ("Range", "Cooking range")
     */
    public ProcessTask(String label, Skill skill, int target, Place spot, Mode mode, List<Need> tools, Need input, List<String> objects) {
        this(label, skill, target, spot, mode, tools, input, objects, List.of());
    }

    /** @param objectIds  ids of the object to use the input on (read in game), matched besides the names */
    public ProcessTask(String label, Skill skill, int target, Place spot, Mode mode, List<Need> tools, Need input,
                       List<String> objects, List<Integer> objectIds) {
        this.objectIds = objectIds;
        this.label = label;
        this.skill = skill;
        this.target = target;
        this.spot = spot;
        this.mode = mode;
        this.tools = tools;
        this.input = input;
        this.objects = objects;
        this.keep = BankTrip.names(tools);
        this.defense = new Defense(spot.name());
    }

    @Override public String name() { return label + " to " + target + " at " + spot.name(); }

    @Override public void start() { phase = Phase.CHECK; trip = null; route = null; fails = 0; }

    @Override public Place spot() { return spot; }

    @Override public boolean missingSupplies() { return missingSupplies; }

    @Override public Need missingItem() { return missingItem; }

    @Override
    protected Result step(long now) {
        Local me = Game.me();
        if (!me.isIdle()) lastAnim = now;
        if (Skills.level(skill) >= target) return Result.DONE;
        Result d = defense.tick(now);                     // attacked: fight back or run (Defense), then start over
        if (d == Result.FAILED) return fail(defense.failure());
        if (d == Result.CONTINUE) { state("under attack: " + defense.state()); phase = Phase.CHECK; route = null; trip = null; return Result.CONTINUE; }
        if (Dialog.canContinue()) {
            state("continuing a message");
            Dialog.continueDialog();
            // a level-up message stops whatever the player was doing (cooking, feeding the fire): start it again.
            // Live 2026-10-10 the campfire stopped at Firemaking 2 and the task sat in "tending the fire".
            if (phase == Phase.WAIT || phase == Phase.MAKEX) { phase = Phase.ACT; fails = 0; }
            sleep(now, 600, 1000);
            return Result.CONTINUE;
        }

        switch (phase) {
            case CHECK -> {
                boolean toolsOk = true;
                for (Need t : tools) toolsOk &= t.met();
                if (!toolsOk || input.slot() < 0) {
                    List<Need> want = new ArrayList<>(tools);
                    want.add(input);
                    trip = new BankTrip(keep, want).toward(spot);
                    phase = Phase.SUPPLY;
                } else if (spot.distance(me.worldX(), me.worldY(), me.plane()) > spot.radius() + (mode == Mode.CAMPFIRE ? 12 : 0)) { phase = Phase.WALK; route = null; }
                else if (mode == Mode.CAMPFIRE && Scenery.nearest(5, o -> oxclient.api.Bank.bankOption(o) > 0) != null && fire() == null) {
                    if (++asides > 8) return fail("no room to light a fire near " + spot.name());   // e.g. inside a bank building
                    moveAside(me);                                    // fires are not lit right at the booth
                }
                else phase = Phase.ACT;
            }
            case SUPPLY -> {
                state("fetching " + input.label() + ": " + trip.state());
                Result r = trip.tick(now);
                if (r == Result.FAILED) {
                    missingSupplies = trip.missingNeed() != null;
                    missingItem = trip.missingNeed();
                    return fail("supplies: " + trip.failure());
                }
                if (r == Result.DONE) { trip = null; phase = Phase.CHECK; }
            }
            case WALK -> {
                if (route == null) route = new Walker.Route(spot.x(), spot.y(), spot.plane(), Math.max(1, spot.radius() / 2));
                Walker.Status st = route.step();
                state("walking to " + spot.name());
                if (st == Walker.Status.ARRIVED) phase = Phase.CHECK;
                else if (st == Walker.Status.NEED_TOLL) {                 // coins for the toll gate: a bank trip takes them
                    if (++tollTrips > 2) return fail("no coins for the toll gate on the way to " + spot.name());
                    List<Need> want = new ArrayList<>(tools);
                    want.add(input);
                    trip = new BankTrip(keep, want).toward(spot);
                    phase = Phase.SUPPLY;
                    route = null;
                }
                else if (st == Walker.Status.NO_ROUTE) return fail("no route to " + spot.name());
                else if (st == Walker.Status.STUCK) return fail("stuck on the way to " + spot.name());
            }
            case ACT -> {
                int in = input.slot();
                if (in < 0) { phase = Phase.CHECK; return Result.CONTINUE; }
                if (++fails > 8) return fail(mode == Mode.LIGHT ? "no fire would light here" : "nothing cooks");
                if (mode == Mode.USE_ON_OBJECT) {
                    Scenery.SceneObject o = Scenery.nearest(spot.radius() + 6, s -> objectIds.contains(s.id()) || objects.stream().anyMatch(n -> n.equalsIgnoreCase(s.name())));
                    if (o == null) return fail("no " + objects.get(0) + " at " + spot.name());
                    state("using " + oxclient.api.Items.name(Inventory.ids()[in]) + " on " + o.name());
                    Inventory.useOn(in, o);
                    makexAt = now;
                    phase = Phase.MAKEX;
                } else if (mode == Mode.CAMPFIRE && fire() != null) {
                    Scenery.SceneObject f = fire();
                    state("adding " + oxclient.api.Items.name(Inventory.ids()[in]) + " to the " + f.name());
                    Inventory.useOn(in, f);
                    makexAt = now;
                    phase = Phase.MAKEX;
                } else {
                    int tb = tools.isEmpty() ? -1 : tools.get(0).slot();
                    if (tb < 0) { phase = Phase.CHECK; return Result.CONTINUE; }
                    cantLight = false;                                // only messages after this click count
                    state("lighting " + oxclient.api.Items.name(Inventory.ids()[in]));
                    Inventory.useOn(tb, in);
                    phase = Phase.WAIT;
                }
                acted = now;
                sleep(now, 900, 1300);
            }
            case MAKEX -> {
                // give the chooser time to come up: "open" only means "loaded", and after the first cook it stays
                // loaded, so reading it at once clicked "All" before the chooser was there (audit 2026-10-10)
                if (now - makexAt < 1500) return Result.CONTINUE;
                if (Interfaces.isOpen(MAKE_X) && !me.isIdle() && lastAnim > makexAt) { phase = Phase.WAIT; acted = now; return Result.CONTINUE; }   // already working
                if (Interfaces.isOpen(MAKE_X) && now - makexAt < 3500) {
                    state("cooking all");
                    // "All" only SETS the quantity -- live 2026-10-10 the chooser stayed up after it (270:12 child 9)
                    // and nothing cooked. Space then makes the first product at that quantity (the chooser's own key).
                    Interfaces.clickText(MAKE_X, "All");
                    Keyboard.press(' ');
                    state(mode == Mode.CAMPFIRE ? "adding all the logs" : "cooking all");
                    phase = Phase.WAIT;
                    acted = now;
                    sleep(now, 900, 1300);
                } else if (now - makexAt > 3500) {
                    phase = me.isIdle() ? Phase.ACT : Phase.WAIT;     // a single item cooks without the chooser
                }
            }
            case WAIT -> {
                if (input.slot() < 0) { fails = 0; phase = Phase.CHECK; return Result.CONTINUE; }   // all used: bank
                // progress = experience or the inputs going down. Not the animation alone: the player can hold a
                // pose after it stops, which kept "tending the fire" going with nothing happening (2026-10-10)
                int xp = Skills.experience(skill), count = input.carried();
                if (xp != progressXp || count != progressCount) { progressXp = xp; progressCount = count; progressAt = now; fails = 0; }
                if (now - progressAt > 6000 && now - acted > 4000 && (mode != Mode.CAMPFIRE || fire() != null)) {
                    System.out.println("[builder] " + label + ": no experience and no " + input.label() + " used for 6 s (animation "
                            + me.animation() + "): doing it again");
                    progressAt = now;
                    phase = Phase.ACT;
                    return Result.CONTINUE;
                }
                if (mode == Mode.CAMPFIRE) {
                    state(fire() == null ? "lighting a fire" : "tending the fire");
                    if (fire() == null && me.isIdle() && lastAnim < acted && now - acted > 4500) { moveAside(me); phase = Phase.ACT; }   // would not light
                    else if (me.isIdle() && now - lastAnim > 3500 && now - acted > 3500) phase = Phase.ACT;   // out, or the fire died: again
                    else if (lastAnim >= acted) fails = 0;
                } else if (mode == Mode.LIGHT) {
                    if (cantLight) { cantLight = false; moveAside(me); phase = Phase.ACT; return Result.CONTINUE; }
                    if (lastAnim >= acted && me.isIdle() && now - lastAnim > 1200) { fails = 0; phase = Phase.ACT; }   // lit; next log
                    else if (lastAnim < acted && now - acted > 4500) { moveAside(me); phase = Phase.ACT; }          // nothing happened
                    state("lighting");
                } else {
                    state("cooking");
                    if (me.isIdle() && now - lastAnim > 3500 && now - acted > 3500) { phase = Phase.ACT; }   // stopped (level-up): again
                    else if (lastAnim >= acted) fails = 0;
                }
            }
        }
        return Result.CONTINUE;
    }

    /** The nearest lit fire within a few tiles, or null. */
    private static Scenery.SceneObject fire() {
        return Scenery.nearest(6, o -> FIRES.stream().anyMatch(n -> n.equalsIgnoreCase(o.name())));
    }

    /** Step to a random tile a few tiles away (a fire is in the way, or this tile allows none). */
    private void moveAside(Local me) {
        int dx = (rng.nextBoolean() ? 1 : -1) * (3 + rng.nextInt(4)), dy = rng.nextInt(7) - 3;
        if (dx == 0 && dy == 0) dx = 2;
        state("moving to light elsewhere");
        oxclient.api.Actions.walk(me.worldX() + dx, me.worldY() + dy);
        waitUntil = System.currentTimeMillis() + 1800;
    }
}
