package oxclient.framework;

import java.util.List;

import oxclient.api.Actions;
import oxclient.api.Dialog;
import oxclient.api.Entity;
import oxclient.api.Game;
import oxclient.api.Inventory;
import oxclient.api.Items;
import oxclient.api.Local;
import oxclient.api.Npcs;
import oxclient.api.Scenery;
import oxclient.api.Scenery.SceneObject;
import oxclient.api.Skill;
import oxclient.api.Skills;
import oxclient.api.Walker;

/**
 * Train a gathering skill to a level at one spot: woodcutting, mining or fishing -- MntnChopper's loop,
 * made general.
 *
 * <p>The loop: make sure every item the method needs is carried or worn -- tools and supplies such as
 * feathers (else a bank trip to fetch them); walk to the spot; take the resource's option (an object by
 * name, or an NPC such as "Fishing spot" by name and option number); wait while animating; when the
 * inventory is full, bank everything but the needed items, or drop the products; repeat until the skill
 * reaches the target level. Level-up messages are continued ({@link Dialog#continueDialog}).</p>
 *
 * <p><b>Under attack:</b> {@link Defense} fights back, or runs and rests, then the loop starts over. Too many
 * attacks in one task fail it ("attacked ... times at ..."), and the picker moves to another spot.</p>
 *
 * <p>A spot with nothing to gather for 30 seconds fails the task, so a wrong spot does not hold a stint.</p>
 */
public final class GatherTask extends BaseTask implements Spotted {

    /**
     * What to gather from, and which option: by NAME ("Chop down", "Net", "Lure") when the client has the
     * definition's options, else by number ({@code option}, 1..5).
     */
    public record Resource(boolean npc, List<String> names, String optionName, int option, List<Integer> ids) {
        public static Resource object(String... names) { return new Resource(false, List.of(names), null, 1, List.of()); }

        public static Resource npc(String name, String optionName, int fallback) { return new Resource(true, List.of(name), optionName, fallback, List.of()); }

        /** The same, also matching these ids (read in game with the Inspector) whatever their name. */
        public Resource withIds(Integer... ids) { return new Resource(npc, names, optionName, option, List.of(ids)); }

        boolean matches(int id, String name) { return ids.contains(id) || names.stream().anyMatch(n -> n.equalsIgnoreCase(name)); }
    }

    private enum Phase { CHECK, TOOL, WALK, FIND, WORK, FULL }

    static final long NOTHING_MS = 30_000;

    private final String label;
    private final Skill skill;
    private final int target;
    private final Place spot;
    private final Resource resource;
    private final List<Need> needs;
    private final List<String> keep;
    private final boolean bank;

    private Phase phase = Phase.CHECK;
    private Walker.Route route;
    private BankTrip trip;
    private long lastAnim, clicked;
    private int dropsSent, tollTrips;
    private long nothingSince;
    private final Defense defense;
    private boolean missingSupplies;
    private int deadClicks;
    private Need missingItem;

    /**
     * @param needs  every item the method needs (tools and supplies); all of them must be carried
     * @param bank   bank the products when full (true) or drop them (false)
     */
    public GatherTask(String label, Skill skill, int target, Place spot, Resource resource, List<Need> needs, boolean bank) {
        this.label = label;
        this.skill = skill;
        this.target = target;
        this.spot = spot;
        this.resource = resource;
        this.needs = needs;
        this.keep = BankTrip.names(needs);
        this.bank = bank;
        this.defense = new Defense(spot.name());
    }

    @Override public String name() { return label + " to " + target + " at " + spot.name(); }

    @Override public void start() { phase = Phase.CHECK; route = null; trip = null; nothingSince = 0; }

    /** The spot this task trains at. */
    public Place spot() { return spot; }

    /** True when it failed because the bank did not have an item the method needs. */
    public boolean missingSupplies() { return missingSupplies; }

    /** Which item that was. */
    public Need missingItem() { return missingItem; }

    @Override
    public void onEvent(ChatEvent event, String line) {
        super.onEvent(event, line);
        if (event == ChatEvent.CANT_REACH) deadClicks += 2;      // that resource is behind something: try others sooner
    }

    private boolean needsMet() {
        for (Need n : needs) if (!n.met()) return false;
        return true;
    }

    @Override
    protected Result step(long now) {
        Local me = Game.me();
        if (!me.isIdle()) lastAnim = now;
        if (Skills.level(skill) >= target) return Result.DONE;
        Result d = defense.tick(now);                     // attacked: fight back or run (Defense), then start over
        if (d == Result.FAILED) return fail(defense.failure());
        if (d == Result.CONTINUE) { state("under attack: " + defense.state()); phase = Phase.CHECK; route = null; trip = null; return Result.CONTINUE; }
        if (Dialog.canContinue()) { state("continuing a message"); Dialog.continueDialog(); sleep(now, 600, 1000); return Result.CONTINUE; }

        switch (phase) {
            case CHECK -> {
                if (!needsMet()) { phase = Phase.TOOL; trip = new BankTrip(keep, needs).toward(spot); }
                else if (Inventory.isFull()) phase = Phase.FULL;
                else if (spot.distance(me.worldX(), me.worldY(), me.plane()) > spot.radius()) { phase = Phase.WALK; route = null; }
                else phase = Phase.FIND;
            }
            case TOOL -> {
                state("fetching supplies: " + trip.state());
                Result r = trip.tick(now);
                if (r == Result.FAILED) {
                    missingSupplies = !trip.missing().isEmpty();
                    missingItem = trip.missingNeed();
                    return fail("supplies: " + trip.failure());
                }
                if (r == Result.DONE) phase = Phase.CHECK;
            }
            case WALK -> {
                if (route == null) route = new Walker.Route(spot.x(), spot.y(), spot.plane(), Math.max(1, spot.radius() / 2));
                Walker.Status st = route.step();
                state("walking to " + spot.name());
                if (st == Walker.Status.ARRIVED) phase = Phase.CHECK;
                else if (st == Walker.Status.NEED_TOLL) {                 // coins for the toll gate: a bank trip takes them
                    if (++tollTrips > 2) return fail("no coins for the toll gate on the way to " + spot.name());
                    trip = new BankTrip(keep, needs).toward(spot);
                    phase = Phase.TOOL;
                    route = null;
                }
                else if (st == Walker.Status.NO_ROUTE) return fail("no route to " + spot.name());
                else if (st == Walker.Status.STUCK) return fail("stuck on the way to " + spot.name());
            }
            case FIND -> {
                if (Inventory.isFull()) { phase = Phase.FULL; return Result.CONTINUE; }
                if (!clickResource(me)) {
                    if (nothingSince == 0) nothingSince = now;
                    if (now - nothingSince > NOTHING_MS) return fail("nothing to gather at " + spot.name());
                    state("nothing to gather in sight");
                    sleep(now, 1500, 2500);
                    phase = Phase.CHECK;
                    return Result.CONTINUE;
                }
                nothingSince = 0;
                clicked = now;
                phase = Phase.WORK;
                sleep(now, 900, 1400);
            }
            case WORK -> {
                state("working");
                if (Inventory.isFull()) { phase = Phase.FULL; return Result.CONTINUE; }
                if (!me.isIdle()) return Result.CONTINUE;
                if (now - lastAnim < 1800 && lastAnim > clicked) return Result.CONTINUE;   // a pause between swings
                if (lastAnim < clicked && now - clicked < 8000) return Result.CONTINUE;    // still walking there
                // a click that never started the work ("I can't reach that", an ignored click): count them, so an
                // unreachable rock or tree cannot hold the stint (audit 2026-10-10)
                if (lastAnim < clicked) { if (++deadClicks >= 6) return fail("clicks at " + spot.name() + " start nothing (unreachable?)"); }
                else deadClicks = 0;
                phase = Phase.CHECK;
                sleep(now, 300, 900);
            }
            case FULL -> {
                if (bank) {
                    if (trip == null) trip = new BankTrip(keep, needs).toward(spot);
                    state("banking: " + trip.state());
                    Result r = trip.tick(now);
                    if (r == Result.FAILED) return fail("banking: " + trip.failure());
                    if (r == Result.DONE) { trip = null; phase = Phase.CHECK; route = null; }
                } else {
                    int[] ids = Inventory.ids();
                    for (int i = 0; i < ids.length; i++) {
                        if (ids[i] < 0 || Gear.matches(ids[i], keep) || Items.name(ids[i]).isEmpty()) continue;
                        state("dropping " + Items.name(ids[i]));
                        Inventory.interact(i, Inventory.OPTION_NUMBERS[4]);   // Drop (7, seen working on 241-3)
                        dropsSent++;
                        sleep(now, 300, 550);
                        if (dropsSent > 60) return fail("dropping does nothing");
                        return Result.CONTINUE;
                    }
                    dropsSent = 0;
                    phase = Phase.CHECK;
                }
            }
        }
        return Result.CONTINUE;
    }

    private boolean clickResource(Local me) {
        if (resource.npc()) {
            Entity best = null;
            int bestD = Integer.MAX_VALUE;
            for (Entity e : Npcs.all()) {
                if (!resource.matches(e.id(), e.name())) continue;
                int d = e.distance();
                if (d < bestD && d <= spot.radius() + 6) { bestD = d; best = e; }
            }
            if (best == null) return false;
            int op = resource.optionName() == null ? -1 : best.option(resource.optionName());
            state("clicking " + best.name() + (op > 0 ? " (" + resource.optionName() + ")" : ""));
            return Actions.npc(best, op > 0 ? op : resource.option());
        }
        SceneObject o = Scenery.nearest(spot.radius() + 6, s -> !s.isWall() && resource.matches(s.id(), s.name()));
        if (o == null) return false;
        state("clicking " + o.name());
        return o.interact(resource.option());
    }
}
