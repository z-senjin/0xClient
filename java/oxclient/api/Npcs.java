package oxclient.api;

import java.util.ArrayList;
import java.util.List;

/**
 * Finding NPCs.
 *
 * <p>Everything here filters {@link Game#npcs()}, which is already a snapshot of this frame, so these
 * are ordinary list operations and cost nothing worth thinking about.</p>
 */
public final class Npcs {

    private Npcs() {}

    /** Every visible NPC. */
    public static List<Entity> all() { return Game.npcs(); }

    /**
     * Start a search over the visible NPCs -- the usual way to find one:
     * {@code Npcs.query().named("Goblin").withOption("Attack").free().nearest()}. See {@link EntityQuery}.
     */
    public static EntityQuery query() { return new EntityQuery(Game::npcs); }

    /** The closest NPC named exactly {@code name} (ignoring case), or null. */
    public static Entity nearest(String name) { return query().named(name).nearest(); }

    /** Every visible NPC whose type is one of {@code ids}. Passing no ids matches everything. */
    public static List<Entity> withId(int... ids) {
        if (ids == null || ids.length == 0) return all();
        List<Entity> out = new ArrayList<>();
        for (Entity e : all()) {
            for (int id : ids) {
                if (e.id() == id) { out.add(e); break; }
            }
        }
        return out;
    }

    /**
     * The closest NPC of one of these types, or null if none is visible.
     *
     * <p>Closest by tile distance, not by screen distance -- an NPC behind you on screen may well be
     * the one you can actually reach.</p>
     */
    public static Entity nearest(int... ids) {
        Entity best = null;
        int bestDist = Integer.MAX_VALUE;
        for (Entity e : withId(ids)) {
            int d = e.distance();
            if (d < bestDist) { bestDist = d; best = e; }
        }
        return best;
    }

    /** The closest NPC of one of these types within {@code radius} tiles, or null. */
    public static Entity nearestWithin(int radius, int... ids) {
        Entity e = nearest(ids);
        return e != null && e.distance() <= radius ? e : null;
    }

    /** How many of these are visible. */
    public static int count(int... ids) { return withId(ids).size(); }
}
