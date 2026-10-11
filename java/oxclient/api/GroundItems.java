package oxclient.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;

import oxclient.Natives;

/**
 * Items lying on the ground: finding them and picking them up.
 *
 * <p>Read from the scene's per-tile item lists (client/items.hpp). Like {@link Scenery}, a call reads the
 * scene when you make it -- a few times a second at most. Derived from client-241-3 (2026-10-09), not yet
 * seen working: the self-check passes it the first time it finds a named item on the ground.</p>
 */
public final class GroundItems {

    private GroundItems() {}

    /** One stack on the ground. */
    public record GroundItem(int id, int quantity, int sceneX, int sceneY) implements Interactable, Located {
        public int worldX() { return Game.sceneBaseX() + sceneX; }

        public int worldY() { return Game.sceneBaseY() + sceneY; }

        @Override public String name() { return Items.name(id); }

        @Override public int distance() { return Game.distanceTo(sceneX, sceneY); }

        @Override public WorldPoint location() { return new WorldPoint(worldX(), worldY()); }

        /** Its five ground options ("Take" is usually the third); option n is {@code [n-1]}. */
        @Override public String[] options() { return Items.groundOptions(id); }

        /** Take option {@code op} (1..5) on it. False when the action was dropped. */
        @Override public boolean interact(int op) { return Natives.groundItemAction(sceneX, sceneY, id, quantity, op); }

        /**
         * Pick it up: the "Take" option by name, else option 3 -- the option the client itself sent for
         * Take on a dropped log (hook-and-log, client-241-3, 2026-10-09).
         */
        public boolean take() {
            int op = option("Take");
            return interact(op > 0 ? op : 3);
        }
    }

    /** How far {@link #query()} looks when not told. */
    public static final int DEFAULT_RADIUS = 20;

    /** Start a search over the ground items within {@link #DEFAULT_RADIUS} tiles. See {@link GroundItemQuery}. */
    public static GroundItemQuery query() { return query(DEFAULT_RADIUS); }

    /** Start a search over the ground items within {@code radius} tiles of you. */
    public static GroundItemQuery query(int radius) { return new GroundItemQuery(() -> all(radius)); }

    /** Every stack within {@code radius} tiles of you, on your floor. */
    public static List<GroundItem> all(int radius) {
        if (!Game.ready()) return Collections.emptyList();
        int[] f = Natives.groundItems(radius);
        List<GroundItem> out = new ArrayList<>(f.length / 4);
        for (int i = 0; i + 3 < f.length; i += 4) out.add(new GroundItem(f[i], f[i + 1], f[i + 2], f[i + 3]));
        return out;
    }

    /** The nearest stack within {@code radius} that {@code filter} accepts, or null. */
    public static GroundItem nearest(int radius, Predicate<GroundItem> filter) {
        GroundItem best = null;
        int bestD = Integer.MAX_VALUE;
        for (GroundItem g : all(radius)) {
            if (!filter.test(g)) continue;
            int d = g.distance();
            if (d < bestD) { bestD = d; best = g; }
        }
        return best;
    }

    /** The nearest stack with this name (ignoring case), or null. */
    public static GroundItem nearest(int radius, String name) {
        return nearest(radius, g -> g.name().equalsIgnoreCase(name));
    }
}
