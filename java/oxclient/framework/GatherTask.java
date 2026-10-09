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
 * <p>The loop: make sure a tool is carried or worn (else a bank trip to fetch one); walk to the spot; take
 * the resource's option (an object by name, or an NPC such as "Fishing spot" by name and option number);
 * wait while animating; when the inventory is full, bank everything but the tools, or drop the products;
 * repeat until the skill reaches the target level. Level-up messages are continued with Space.</p>
 */
public final class GatherTask extends BaseTask {

    /** What to gather from. */
    public record Resource(boolean npc, List<String> names, int option) {
        public static Resource object(String... names) { return new Resource(false, List.of(names), 1); }

        public static Resource npc(String name, int option) { return new Resource(true, List.of(name), option); }
    }

    private enum Phase { CHECK, TOOL, WALK, FIND, WORK, FULL }

    private final String label;
    private final Skill skill;
    private final int target;
    private final Place spot;
    private final Resource resource;
    private final List<String> tools;
    private final boolean bank;

    private Phase phase = Phase.CHECK;
    private Walker.Route route;
    private BankTrip trip;
    private long lastAnim, clicked;
    private int dropsSent;

    /**
     * @param tools  name parts of acceptable tools ("axe", "pickaxe", "Small fishing net"); any one will do
     * @param bank   bank the products when full (true) or drop them (false)
     */
    public GatherTask(String label, Skill skill, int target, Place spot, Resource resource, List<String> tools, boolean bank) {
        this.label = label;
        this.skill = skill;
        this.target = target;
        this.spot = spot;
        this.resource = resource;
        this.tools = tools;
        this.bank = bank;
    }

    @Override public String name() { return label + " to " + target + " at " + spot.name(); }

    @Override public void start() { phase = Phase.CHECK; route = null; trip = null; }

    @Override
    protected Result step(long now) {
        Local me = Game.me();
        if (!me.isIdle()) lastAnim = now;
        if (Skills.level(skill) >= target) return Result.DONE;
        if (Dialog.canContinue()) { state("continuing a message"); Dialog.continueDialog(); sleep(now, 600, 1000); return Result.CONTINUE; }

        switch (phase) {
            case CHECK -> {
                if (!Gear.has(tools)) { phase = Phase.TOOL; trip = new BankTrip(tools, List.of(tools.get(0))); }
                else if (Inventory.isFull()) phase = Phase.FULL;
                else if (spot.distance(me.worldX(), me.worldY(), me.plane()) > spot.radius()) { phase = Phase.WALK; route = null; }
                else phase = Phase.FIND;
            }
            case TOOL -> {
                state("fetching a tool: " + trip.state());
                Result r = trip.tick(now);
                if (r == Result.FAILED) return fail("no tool (" + String.join(" / ", tools) + "): " + trip.failure());
                if (r == Result.DONE) phase = Phase.CHECK;
            }
            case WALK -> {
                if (route == null) route = new Walker.Route(spot.x(), spot.y(), spot.plane(), Math.max(1, spot.radius() / 2));
                Walker.Status st = route.step();
                state("walking to " + spot.name());
                if (st == Walker.Status.ARRIVED) phase = Phase.CHECK;
                else if (st == Walker.Status.NO_ROUTE) return fail("no route to " + spot.name());
                else if (st == Walker.Status.STUCK) return fail("stuck on the way to " + spot.name());
            }
            case FIND -> {
                if (Inventory.isFull()) { phase = Phase.FULL; return Result.CONTINUE; }
                if (!clickResource(me)) { state("nothing to gather in sight"); sleep(now, 1500, 2500); phase = Phase.CHECK; return Result.CONTINUE; }
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
                phase = Phase.CHECK;
                sleep(now, 300, 900);
            }
            case FULL -> {
                if (bank) {
                    if (trip == null) trip = new BankTrip(tools, List.of());
                    state("banking: " + trip.state());
                    Result r = trip.tick(now);
                    if (r == Result.FAILED) return fail("banking: " + trip.failure());
                    if (r == Result.DONE) { trip = null; phase = Phase.CHECK; route = null; }
                } else {
                    int[] ids = Inventory.ids();
                    for (int i = 0; i < ids.length; i++) {
                        if (ids[i] < 0 || Gear.matches(ids[i], tools) || Items.name(ids[i]).isEmpty()) continue;
                        state("dropping " + Items.name(ids[i]));
                        Inventory.interact(i, 7);                      // Drop (option 7, seen working on 241-3)
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
                if (!resource.names().contains(e.name())) continue;
                int d = e.distance();
                if (d < bestD && d <= spot.radius() + 6) { bestD = d; best = e; }
            }
            if (best == null) return false;
            state("clicking " + best.name());
            return Actions.npc(best, resource.option());
        }
        SceneObject o = Scenery.nearest(spot.radius() + 6, s -> !s.isWall() && resource.names().stream().anyMatch(n -> n.equalsIgnoreCase(s.name())));
        if (o == null) return false;
        state("clicking " + o.name());
        return o.interact(resource.option());
    }
}
