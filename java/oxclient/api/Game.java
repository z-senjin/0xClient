package oxclient.api;

import java.awt.Point;
import java.awt.Polygon;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import oxclient.Natives;

/**
 * The world, as of the start of this frame.
 *
 * <p>Read it from anywhere; it is refreshed once per tick before any plugin runs, so every plugin and
 * every overlay in a single frame sees exactly the same world. That matters more than it sounds: if
 * entities were re-read on demand, a plugin could pick a target in {@code tick()} and then draw a box
 * somewhere else in {@code render()} because the NPC moved in between.</p>
 */
public final class Game {

    private Game() {}

    private static final int FINE = 128;          // fine units per tile
    private static final int HALF = FINE / 2;

    private static List<Entity> entities = Collections.emptyList();
    private static List<Entity> npcs = Collections.emptyList();
    private static List<Entity> players = Collections.emptyList();
    private static Local local = Local.ABSENT;
    private static int baseX, baseY;
    private static boolean loaded;
    private static int lastLocalUid = -1;
    private static boolean wasReady;

    /** Called once per frame by the client, before plugins run. You should not need to call it. */
    public static void refresh() {
        int[] base = Natives.sceneBase();
        loaded = base.length == 2;
        int nbx = loaded ? base[0] : 0;
        int nby = loaded ? base[1] : 0;
        boolean baseMoved = nbx != baseX || nby != baseY;   // scene moved: uids about to churn
        baseX = nbx;
        baseY = nby;

        local = Local.read();

        // review 2026-09-06: the base move alone is NOT every uid churn. Hop worlds standing still,
        // or log out and back in on the spot, and the scene reloads at the same base -- while every
        // handle in the game's player/NPC tables is reassigned. The name cache would then serve the
        // old world's name for whatever entity inherited a uid (the GE banker labelled "Guard"),
        // and npchighlight/playerindicators would tag the wrong entities until the player walked far
        // enough to re-centre. Logging in and losing/changing our own handle are the two observable
        // edges of exactly that reload, so clear on either as well.
        int localUid = local.exists() ? local.uid() : -1;
        boolean nowReady = Natives.ready() && loaded && local.exists();
        if (baseMoved || localUid != lastLocalUid || (nowReady && !wasReady)) {
            Entity.clearNameCache();
        }
        lastLocalUid = localUid;
        wasReady = nowReady;

        int[] flat = Natives.entities();
        final int stride = 11;                          // see nEntities in client/jvm.hpp
        List<Entity> all = new ArrayList<>(flat.length / stride);
        List<Entity> n = new ArrayList<>();
        List<Entity> p = new ArrayList<>();
        int me = local.uid();
        for (int i = 0; i + stride - 1 < flat.length; i += stride) {
            int uid = flat[i];
            // You are Local, not one of the crowd -- but only the PLAYER with your handle is you.
            // NPC uids are a separate keyspace, and an NPC sharing your handle was dropped here too.
            if (uid == me && flat[i + 3] != 0) continue;
            Entity e = new Entity(uid, flat[i + 1], flat[i + 2], flat[i + 3] != 0,
                                  flat[i + 4], flat[i + 5], flat[i + 6],
                                  flat[i + 7], flat[i + 8], flat[i + 9], flat[i + 10]);
            all.add(e);
            (e.isPlayer() ? p : n).add(e);
        }
        entities = Collections.unmodifiableList(all);
        npcs = Collections.unmodifiableList(n);
        players = Collections.unmodifiableList(p);
    }

    /** True once you are actually in the world -- not at the login screen, not still loading. */
    public static boolean ready() { return Natives.ready() && loaded && local.exists(); }

    /** You. Never null; ask {@link Local#exists()} before trusting it. */
    public static Local me() { return local; }

    /** Every visible NPC and player except you. */
    public static List<Entity> entities() { return entities; }

    /** Every visible NPC. */
    public static List<Entity> npcs() { return npcs; }

    /** Every visible player except you. */
    public static List<Entity> players() { return players; }

    /** World x of the loaded scene's south-west corner. */
    public static int sceneBaseX() { return baseX; }

    /** World y of the loaded scene's south-west corner. */
    public static int sceneBaseY() { return baseY; }

    // -----------------------------------------------------------------------------------------------
    // Coordinates
    // -----------------------------------------------------------------------------------------------

    /**
     * World to scene. Returns null when that tile is not in the loaded chunk -- which is a real answer,
     * not an error: you cannot click a tile the client has not loaded.
     */
    public static Point toScene(int worldX, int worldY) {
        if (!loaded) return null;
        int sx = worldX - baseX, sy = worldY - baseY;
        // The scene is 104 tiles, indices 0..103 (Constants.SCENE_SIZE; WorldPoint.isInScene uses the
        // same strict bound). 104 used to pass here, so a tile one past the edge counted as loaded.
        if (sx < 0 || sy < 0 || sx >= 104 || sy >= 104) return null;
        return new Point(sx, sy);
    }

    /** Diagonal distance from you to a scene tile. */
    public static int distanceTo(int sceneX, int sceneY) {
        if (!local.exists()) return Integer.MAX_VALUE;
        return Math.max(Math.abs(sceneX - local.sceneX()), Math.abs(sceneY - local.sceneY()));
    }

    // -----------------------------------------------------------------------------------------------
    // Projection -- turning world positions into places on screen
    // -----------------------------------------------------------------------------------------------

    /**
     * Project a fine-coordinate point. Fine units are tiles &times; 128, and {@code height} is the
     * vertical axis in the client's own height datum (negative = up).
     *
     * <p><b>0 is the datum, not the ground.</b> The terrain sits at a per-tile height in that axis
     * which the shim cannot read yet (no heightmap offset is derived), so {@link #projectTile} and
     * {@link #tileOutline}, which pass 0, draw at datum height: below or above the feet by however
     * far the ground is from 0 at that tile, and ~240 units (a full floor) off on upper floors. A
     * known limitation, tracked in PROGRESS.md; the OXC_LOG probe in {@code client/jvm.hpp} sweeps
     * candidate heights so the residual can be measured in-game.</p>
     *
     * @return where to draw, or null if it is behind the camera or off in the distance
     */
    public static Point projectFine(int fineX, int height, int fineY) {
        long packed = Natives.project(fineX, height, fineY);
        if (packed == Long.MIN_VALUE) return null;
        return new Point((int) (packed >> 32), (int) packed);
    }

    /**
     * The best ground height this API has for a tile: the ground under YOU (read off your own entity,
     * see {@link Local#height}), because the terrain heightmap itself is still unreadable. Exact on
     * your tile, right on flat ground around you, off by the slope elsewhere -- and far better than
     * the datum 0 it replaced, which drew ~290 px low in a bank (2026-09-05). Entities carry their
     * own exact height ({@link Entity#height}); this is for tiles.
     */
    public static int groundHeightGuess() {
        return me().height();
    }

    /**
     * The best ground height this API has for a SPECIFIC tile: the height under the nearest entity
     * (NPC, player, or you) standing on or next to it, else {@link #groundHeightGuess}. There is no
     * heightmap, but an entity carries the exact ground under its own feet, and one standing on the
     * tile IS the ground sample -- which is the case for every "true tile"/"south-west tile" an NPC
     * highlighter asks about. Both an entity's server tile and its render tile count as "on it", so
     * a walking NPC still samples for the tile it is between. Never worse than the guess: with no
     * entity within a tile it returns exactly that.
     */
    public static int heightNear(int sceneX, int sceneY) {
        int best = Integer.MAX_VALUE;
        int height = groundHeightGuess();
        Local me = local;
        if (me.exists()) {
            int d = tileDistance(sceneX, sceneY, me.sceneX(), me.sceneY(), me.fineX(), me.fineY());
            if (d < best) { best = d; height = me.height(); }
        }
        for (Entity e : entities) {
            int d = tileDistance(sceneX, sceneY, e.sceneX(), e.sceneY(), e.fineX(), e.fineY());
            if (d < best) { best = d; height = e.height(); }
            if (best == 0) break;
        }
        return best <= 1 ? height : groundHeightGuess();
    }

    /** Chebyshev distance from a tile to the nearer of an entity's server tile and its render tile. */
    private static int tileDistance(int sceneX, int sceneY, int ex, int ey, int fineX, int fineY) {
        int d = Math.max(Math.abs(ex - sceneX), Math.abs(ey - sceneY));
        if (fineX != 0 || fineY != 0) {
            int rx = fineX >> 7, ry = fineY >> 7;
            d = Math.min(d, Math.max(Math.abs(rx - sceneX), Math.abs(ry - sceneY)));
        }
        return d;
    }

    /** The centre of a scene tile, at {@link #groundHeightGuess}. Null when off screen. */
    public static Point projectTile(int sceneX, int sceneY) {
        return projectTile(sceneX, sceneY, groundHeightGuess());
    }

    /** The centre of a scene tile at an explicit height (see {@link #projectFine}). Null when off screen. */
    public static Point projectTile(int sceneX, int sceneY, int height) {
        return projectFine((sceneX << 7) + HALF, height, (sceneY << 7) + HALF);
    }

    /** The centre of a world tile. Null when it is not loaded or not on screen. */
    public static Point projectWorld(int worldX, int worldY) {
        Point scene = toScene(worldX, worldY);
        return scene == null ? null : projectTile(scene.x, scene.y);
    }

    /**
     * The four corners of a scene tile as a polygon you can draw or fill.
     *
     * <p>This is what makes a tile marker look like it is lying on the ground rather than stuck to your
     * screen: each corner goes through the game's own projection, so the shape gets the perspective
     * right by construction and stays right while the camera turns.</p>
     *
     * @return the outline, or null if any corner is off screen
     */
    public static Polygon tileOutline(int sceneX, int sceneY) {
        return tileOutline(sceneX, sceneY, groundHeightGuess());
    }

    /** {@link #tileOutline(int, int)} at an explicit ground height (an entity's own, usually). */
    public static Polygon tileOutline(int sceneX, int sceneY, int height) {
        int x0 = sceneX << 7, y0 = sceneY << 7;
        Point a = projectFine(x0, height, y0);
        Point b = projectFine(x0 + FINE, height, y0);
        Point c = projectFine(x0 + FINE, height, y0 + FINE);
        Point d = projectFine(x0, height, y0 + FINE);
        if (a == null || b == null || c == null || d == null) return null;

        Polygon poly = new Polygon();
        poly.addPoint(a.x, a.y);
        poly.addPoint(b.x, b.y);
        poly.addPoint(c.x, c.y);
        poly.addPoint(d.x, d.y);
        return poly;
    }

    /** The tile outline for a world tile, or null. */
    public static Polygon tileOutlineWorld(int worldX, int worldY) {
        Point scene = toScene(worldX, worldY);
        return scene == null ? null : tileOutline(scene.x, scene.y);
    }
}
