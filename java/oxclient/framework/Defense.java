package oxclient.framework;

import java.util.ArrayList;
import java.util.List;

import oxclient.api.Combat;
import oxclient.api.Dialog;
import oxclient.api.Entity;
import oxclient.api.Game;
import oxclient.api.Local;
import oxclient.api.Npcs;
import oxclient.api.Walker;

/**
 * What a non-combat task does when something attacks it: fight back while health is over half (eating if food
 * is carried), else run toward a bank away from the attacker until nothing targets us, and rest until health
 * is back over 60%.
 *
 * <p>Why not "walk to the bank and open it", as before: live 2026-10-10 a Dark wizard followed the player to
 * Draynor bank, every hit made the game's auto-retaliate turn the player to fight and the bank never stayed
 * open ("opening Bank booth / waiting" eight times), and the player died there. Fighting back is what the game
 * does anyway; doing it on purpose means nothing else gets interrupted.</p>
 *
 * <p>The task calls {@link #tick} first every step: DONE = no threat, carry on; CONTINUE = handling one (the
 * task re-checks from the start afterwards); FAILED = attacked {@link #MAX_ATTACKS} times, so the picker moves
 * to another spot.</p>
 */
final class Defense {

    static final int MAX_ATTACKS = 3;
    private static final int NEAR = 10, RUN_CLEAR = 15;
    private static final long QUIET_MS = 3000, REST_MAX_MS = 10 * 60_000L;

    private enum Mode { IDLE, FIGHT, RUN, REST }

    private final String where;
    private final Eater eater = new Eater();
    private Mode mode = Mode.IDLE;
    private int attacks, lastHp = -1, lastMax = -1, runs;
    private long quietSince, restSince, waitUntil;
    private Walker.Route route;
    private String state = "", failure = "";

    /** @param where the spot's name, for the log and the failure */
    Defense(String where) { this.where = where; }

    String state() { return state; }

    String failure() { return failure; }

    Task.Result tick(long now) {
        Local me = Game.me();
        boolean hit = hit(me);                         // always: it tracks the last hitpoints
        if (now < waitUntil) return mode == Mode.IDLE ? Task.Result.DONE : Task.Result.CONTINUE;
        // not while a dialogue is up: whoever we are talking to faces us (the toll gate's guard, 2026-10-10)
        List<Entity> near = Dialog.isOpen() ? List.of() : attackers(NEAR);
        int hp = Eater.hpPercent(me);

        switch (mode) {
            case IDLE -> {
                if (!hit && near.isEmpty()) return Task.Result.DONE;
                if (++attacks > MAX_ATTACKS) { failure = "attacked " + attacks + " times at " + where; return Task.Result.FAILED; }
                System.out.println("[builder] under attack at " + where + " by " + describe(near) + ", hp " + me.health() + "/" + me.maxHealth()
                        + ", attack " + attacks + " of " + MAX_ATTACKS);
                mode = hp >= 50 || eater.hasFood() ? Mode.FIGHT : Mode.RUN;
                quietSince = 0;
                route = null;
            }
            case FIGHT -> {
                if (eater.eat(me)) { state = "eating"; waitUntil = now + 1400; return Task.Result.CONTINUE; }
                if (hp < 50 && !eater.hasFood()) { mode = Mode.RUN; route = null; return Task.Result.CONTINUE; }
                if (near.isEmpty()) {                                         // a hit with nobody targeting us ends here too
                    if (quietSince == 0) quietSince = now;
                    if (now - quietSince > QUIET_MS) return end(me);          // it died or gave up
                    state = "nothing attacking now";
                    return Task.Result.CONTINUE;
                }
                quietSince = 0;
                int mine = Combat.target();
                if (near.stream().noneMatch(e -> e.uid() == mine)) {
                    // one we cannot attack (no readable Attack option: a nameless NPC, live 2026-10-10) -- move away
                    Entity foe = near.stream().filter(e -> e.option("Attack") > 0).findFirst().orElse(null);
                    if (foe == null) { mode = Mode.RUN; route = null; return Task.Result.CONTINUE; }
                    state = "fighting back: " + foe.name();
                    foe.interact("Attack");
                    waitUntil = now + 1800;
                } else state = "fighting back";
            }
            case RUN -> {
                if (eater.eat(me)) { state = "eating"; waitUntil = now + 1400; return Task.Result.CONTINUE; }
                if (route == null) {
                    if (++runs > MAX_ATTACKS) { failure = "had to run " + runs + " times at " + where; return Task.Result.FAILED; }
                    Place to = refuge(near);
                    System.out.println("[builder] running to " + to.name() + " (hp " + me.health() + "/" + me.maxHealth() + ")");
                    route = new Walker.Route(to.x(), to.y(), to.plane(), 3);
                }
                Walker.Status st = route.step();
                state = "running away";
                // far enough once nothing within RUN_CLEAR tiles targets us: rest there, not a long walk further
                if (st != Walker.Status.WALKING && st != Walker.Status.LOADING || attackers(RUN_CLEAR).isEmpty()) {
                    mode = Mode.REST;
                    restSince = now;
                }
            }
            case REST -> {
                if (!near.isEmpty()) { mode = hp >= 50 || eater.hasFood() ? Mode.FIGHT : Mode.RUN; route = null; return Task.Result.CONTINUE; }
                if (hp >= 60 || now - restSince > REST_MAX_MS) return end(me);
                state = "resting (" + me.health() + "/" + me.maxHealth() + ")";
                waitUntil = now + 2500;
            }
        }
        return Task.Result.CONTINUE;
    }

    private Task.Result end(Local me) {
        mode = Mode.IDLE;
        route = null;
        lastHp = -1;
        state = "";
        System.out.println("[builder] safe again at hp " + me.health() + "/" + me.maxHealth());
        return Task.Result.CONTINUE;
    }

    /** Did hitpoints just drop? (A level-up of Hitpoints is not a hit.) */
    private boolean hit(Local me) {
        int hp = me.health(), max = me.maxHealth();
        boolean dropped = lastHp >= 0 && max == lastMax && hp < lastHp;
        lastHp = hp;
        lastMax = max;
        return dropped;
    }

    private static List<Entity> attackers(int tiles) {
        int[] uids = Combat.attackersNear(tiles);
        List<Entity> out = new ArrayList<>();
        if (uids.length == 0) return out;
        for (Entity e : Npcs.all()) for (int u : uids) if (e.uid() == u) out.add(e);
        return out;
    }

    /** The nearest bank at least {@link #RUN_CLEAR} tiles from the attackers (so it is not where they stand). */
    private static Place refuge(List<Entity> foes) {
        Local me = Game.me();
        Place best = null;
        long bestD = Long.MAX_VALUE;
        for (Place b : Places.banksNow()) {
            boolean clear = foes.stream().allMatch(e -> Math.max(Math.abs(b.x() - e.worldX()), Math.abs(b.y() - e.worldY())) >= RUN_CLEAR);
            if (!clear) continue;
            long d = Math.max(Math.abs(b.x() - me.worldX()), Math.abs(b.y() - me.worldY())) + (b.plane() != me.plane() ? 40L : 0L);
            if (d < bestD) { bestD = d; best = b; }
        }
        return best != null ? best : Places.nearestBank();
    }

    private static String describe(List<Entity> who) {
        if (who.isEmpty()) return "nobody targeting (hitpoints dropped)";
        StringBuilder b = new StringBuilder();
        for (Entity e : who) b.append(b.length() == 0 ? "" : ", ").append(e.name()).append(" #").append(e.id()).append(" (").append(e.distance()).append(" tiles)");
        return b.toString();
    }
}
