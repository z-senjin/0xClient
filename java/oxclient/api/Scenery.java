package oxclient.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

import oxclient.Natives;

/**
 * Finding scenery: trees, rocks, bank booths, doors -- the things the game calls locs.
 *
 * <p>Two kinds are listed: game objects (the big clickable things) and walls (walls, fences, doors,
 * gates). Each is reported once, at its origin tile (the south-west corner of a large object), which
 * is also the tile {@link #interact} and {@link Actions#object} want.</p>
 *
 * <p>Unlike {@link Game#npcs()}, nothing here is refreshed every frame: a call reads the scene when you
 * make it. Call it a few times a second at most -- the native side walks every tile in the radius.</p>
 *
 * <p>Game objects and names were seen working on client-241-3 (2026-10-09: the self-check, and
 * MntnChopper finding and chopping trees). Walls and option names are derived but not yet seen.</p>
 */
public final class Scenery {

    private Scenery() {}

    /** The layer of a wall (doors, gates, fences). */
    public static final int LAYER_WALL = 0;

    /** The layer of a game object (trees, booths, rocks). */
    public static final int LAYER_GAME_OBJECT = 2;

    /** One object: its loc id, origin tile and layer. */
    public record SceneObject(int id, int sceneX, int sceneY, int layer) {
        public int worldX() { return Game.sceneBaseX() + sceneX; }

        public int worldY() { return Game.sceneBaseY() + sceneY; }

        public boolean isWall() { return layer == LAYER_WALL; }

        /** "Tree", "Bank booth"... or "" when the client has not cached its definition right now. */
        public String name() { return Scenery.name(id); }

        /** Its right-click options; option n is {@code [n-1]}, "" where there is none. */
        public String[] options() { return Scenery.options(id); }

        /** The option number (1..5) whose text is {@code text}, ignoring case, or -1. */
        public int option(String text) {
            String[] ops = options();
            for (int i = 0; i < ops.length; i++) if (ops[i].equalsIgnoreCase(text)) return i + 1;
            return -1;
        }

        /** Take option {@code op} (1..5) on it. False when the action was dropped. */
        public boolean interact(int op) { return Natives.objectAction(sceneX, sceneY, id, op); }

        /** Tile distance from you (the larger of the x and y gaps). */
        public int distance() {
            Local me = Game.me();
            return Math.max(Math.abs(sceneX - me.sceneX()), Math.abs(sceneY - me.sceneY()));
        }
    }

    // Names and options never change for an id within a build, so a hit is kept for the session. A miss
    // is not cached: it means the client had not looked the definition up yet, and it will shortly.
    private static final Map<Integer, String> NAMES = new ConcurrentHashMap<>();
    private static final Map<Integer, String[]> OPTIONS = new ConcurrentHashMap<>();
    private static final String[] NO_OPTIONS = {"", "", "", "", ""};

    /** Everything within {@code radius} tiles of you, on your floor. Empty before you spawn. */
    public static List<SceneObject> all(int radius) {
        if (!Game.ready()) return Collections.emptyList();
        int[] flat = Natives.locs(radius);
        List<SceneObject> out = new ArrayList<>(flat.length / 4);
        for (int i = 0; i + 3 < flat.length; i += 4) out.add(new SceneObject(flat[i], flat[i + 1], flat[i + 2], flat[i + 3]));
        return out;
    }

    /** The closest object within {@code radius} that {@code filter} accepts, or null. */
    public static SceneObject nearest(int radius, Predicate<SceneObject> filter) {
        SceneObject best = null;
        int bestDist = Integer.MAX_VALUE;
        for (SceneObject o : all(radius)) {
            if (!filter.test(o)) continue;
            int d = o.distance();
            if (d < bestDist) { bestDist = d; best = o; }
        }
        return best;
    }

    /** Is there still an object with this id on this scene tile? (A felled tree turns into a stump.) */
    public static boolean exists(int id, int sceneX, int sceneY) {
        Local me = Game.me();
        int r = Math.max(Math.abs(sceneX - me.sceneX()), Math.abs(sceneY - me.sceneY())) + 1;
        for (SceneObject o : all(r)) {
            if (o.id() == id && o.sceneX() == sceneX && o.sceneY() == sceneY) return true;
        }
        return false;
    }

    /**
     * A loc's name, or "" when unknown right now. The client pads some names with a no-break space;
     * those come back as ordinary spaces, trimmed.
     */
    public static String name(int id) {
        String n = NAMES.get(id);
        if (n != null) return n;
        String raw = Natives.locName(id);
        if (raw == null || raw.isEmpty()) return "";
        n = clean(raw);
        if (!n.isEmpty()) NAMES.put(id, n);
        return n;
    }

    /** A loc's five options (option n is {@code [n-1]}), all "" when unknown right now. */
    public static String[] options(int id) {
        String[] o = OPTIONS.get(id);
        if (o != null) return o.clone();
        String raw = Natives.locOptions(id);
        if (raw == null || raw.isEmpty()) return NO_OPTIONS.clone();
        String[] parts = raw.split("\n", -1);
        o = new String[5];
        for (int i = 0; i < 5; i++) o[i] = i < parts.length ? clean(parts[i]) : "";
        OPTIONS.put(id, o);
        return o.clone();
    }

    private static String clean(String s) { return s.replace(' ', ' ').trim(); }
}
