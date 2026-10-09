package oxclient.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import shortestpath.pathfinder.CollisionMap;
import shortestpath.pathfinder.SplitFlagMap;

/**
 * Walking somewhere further than one click: a route over the world's collision map, followed a few
 * tiles at a time with the game's own Walk here ({@link Actions#walkTo}).
 *
 * <p>The route is a breadth-first search over the collision data the Shortest Path plugin ships
 * ({@code collision-map.zip}), on one floor, around walls and water. It does not take stairs, ladders
 * or other transports; a route that needs one is not found. Doors: the collision data treats most of
 * them as passable, so a {@link Route} that stops making progress opens the nearest closed door or
 * gate it can see (a wall with an "Open" option) and carries on.</p>
 *
 * <p>Each click aims 9 to 13 tiles along the route and is re-sent when you are a few tiles from it or
 * have stopped moving; the game's own pathing covers the gap. Walking with the game's walk function
 * was seen working on client-241-3 (2026-10-09); routes and door handling are new and not yet seen.</p>
 */
public final class Walker {

    private Walker() {}

    private static volatile CollisionMap map;
    private static volatile boolean loading;
    private static volatile String loadError = "";

    /**
     * True once the collision map is loaded. The first call starts loading it on a background thread
     * (a second or two) and returns false meanwhile.
     */
    public static boolean ready() {
        if (map != null) return true;
        synchronized (Walker.class) {
            if (!loading && map == null) {
                loading = true;
                Thread t = new Thread(() -> {
                    try {
                        map = new CollisionMap(SplitFlagMap.fromResources());
                    } catch (Throwable e) {
                        loadError = String.valueOf(e);
                        loading = false;
                    }
                }, "oxc-collision-map");
                t.setDaemon(true);
                t.start();
            }
        }
        return false;
    }

    /** Why the collision map failed to load, or "". */
    public static String loadError() { return loadError; }

    /**
     * A route from {@code (fromX, fromY)} to within {@code arrive} tiles of {@code (toX, toY)}, world
     * tiles on {@code plane}, as {x, y} pairs from the first step to the last. Empty when there is no
     * route inside the search box (the two points plus a 64-tile margin) or the map is not loaded.
     */
    public static List<int[]> findPath(int fromX, int fromY, int toX, int toY, int plane, int arrive) {
        CollisionMap m = map;
        if (m == null) return Collections.emptyList();
        final int margin = 64;
        final int minX = Math.min(fromX, toX) - margin, minY = Math.min(fromY, toY) - margin;
        final int w = Math.abs(fromX - toX) + 2 * margin + 1, h = Math.abs(fromY - toY) + 2 * margin + 1;
        if ((long) w * h > 1_500_000L) return Collections.emptyList();
        int[] parent = new int[w * h];
        java.util.Arrays.fill(parent, -2);
        int[] queue = new int[w * h];
        int head = 0, tail = 0;
        int start = (fromY - minY) * w + (fromX - minX);
        parent[start] = -1;
        queue[tail++] = start;
        final int[] dx = {0, 0, 1, -1, 1, -1, 1, -1};
        final int[] dy = {1, -1, 0, 0, 1, 1, -1, -1};
        int goal = -1;
        while (head < tail) {
            int cur = queue[head++];
            int x = cur % w + minX, y = cur / w + minY;
            if (Math.max(Math.abs(x - toX), Math.abs(y - toY)) <= arrive) { goal = cur; break; }
            for (int d = 0; d < 8; d++) {
                int nx = x + dx[d], ny = y + dy[d];
                if (nx < minX || ny < minY || nx >= minX + w || ny >= minY + h) continue;
                int ni = (ny - minY) * w + (nx - minX);
                if (parent[ni] != -2 || !canStep(m, x, y, d, plane)) continue;
                parent[ni] = cur;
                queue[tail++] = ni;
            }
        }
        if (goal < 0) return Collections.emptyList();
        List<int[]> out = new ArrayList<>();
        for (int c = goal; c != -1; c = parent[c]) out.add(new int[] {c % w + minX, c / w + minY});
        Collections.reverse(out);
        return out;
    }

    /** One step in direction d (N, S, E, W, NE, NW, SE, SW), the rules the Shortest Path map uses. */
    private static boolean canStep(CollisionMap m, int x, int y, int d, int z) {
        switch (d) {
            case 0: return m.n(x, y, z);
            case 1: return m.s(x, y, z);
            case 2: return m.e(x, y, z);
            case 3: return m.w(x, y, z);
            case 4: return m.n(x, y, z) && m.e(x, y + 1, z) && m.e(x, y, z) && m.n(x + 1, y, z);
            case 5: return m.n(x, y, z) && m.w(x, y + 1, z) && m.w(x, y, z) && m.n(x - 1, y, z);
            case 6: return m.s(x, y, z) && m.e(x, y - 1, z) && m.e(x, y, z) && m.s(x + 1, y, z);
            default: return m.s(x, y, z) && m.w(x, y - 1, z) && m.w(x, y, z) && m.s(x - 1, y, z);
        }
    }

    /** How a {@link Route#step} went. */
    public enum Status { ARRIVED, WALKING, LOADING, NO_ROUTE, STUCK }

    /**
     * Walking to one world tile. Call {@link #step} once per plugin tick until it says ARRIVED; it
     * decides on its own when to click.
     */
    public static final class Route {
        private static final long MIN_CLICK_GAP_MS = 600;
        private static final long STILL_MS = 1800;
        private static final long STUCK_MS = 9000;

        private final int destX, destY, arrive;
        private final Random rng = new Random();
        private List<int[]> path;
        private int plane = -1, anchor, best = Integer.MAX_VALUE, doorTries;
        private int[] aim;
        private int lastX, lastY, lead = 3;
        private long lastClick, lastMove, lastProgress;
        private boolean straight;

        public Route(int worldX, int worldY, int arrive) {
            this.destX = worldX;
            this.destY = worldY;
            this.arrive = Math.max(0, arrive);
        }

        public int destX() { return destX; }

        public int destY() { return destY; }

        /** The tile the last click aimed at, or null. */
        public int[] aim() { return aim; }

        public Status step() {
            Local me = Game.me();
            if (!me.exists()) return Status.WALKING;
            long now = System.currentTimeMillis();
            int mx = me.worldX(), my = me.worldY();
            if (Math.max(Math.abs(mx - destX), Math.abs(my - destY)) <= arrive) return Status.ARRIVED;
            if (!ready()) return Status.LOADING;

            if (path == null || plane != me.plane()) {
                plane = me.plane();
                path = findPath(mx, my, destX, destY, plane, arrive);
                straight = path.isEmpty();
                if (straight) path = straightLine(mx, my, destX, destY);
                anchor = 0;
                best = Integer.MAX_VALUE;
                lastProgress = now;
            }
            if (path.isEmpty()) return Status.NO_ROUTE;

            if (mx != lastX || my != lastY) { lastX = mx; lastY = my; lastMove = now; }

            // the route tile nearest you, only ever moving forward
            int bestI = anchor, bestD = Integer.MAX_VALUE;
            for (int i = anchor; i < path.size() && i < anchor + 40; i++) {
                int d = Math.max(Math.abs(path.get(i)[0] - mx), Math.abs(path.get(i)[1] - my));
                if (d < bestD) { bestD = d; bestI = i; }
            }
            anchor = bestI;
            int remaining = path.size() - anchor;
            if (remaining < best) { best = remaining; lastProgress = now; }
            if (now - lastProgress > STUCK_MS) {
                lastProgress = now;
                if (openNearbyDoor()) { doorTries++; return Status.WALKING; }
                path = null;                      // re-plan from here next tick
                return doorTries++ > 3 ? Status.STUCK : Status.WALKING;
            }

            boolean nearAim = aim != null && Math.max(Math.abs(aim[0] - mx), Math.abs(aim[1] - my)) <= lead;
            boolean still = now - lastMove > STILL_MS;
            if (aim != null && !nearAim && !still) return Status.WALKING;
            if (now - lastClick < MIN_CLICK_GAP_MS) return Status.WALKING;

            int idx = Math.min(path.size() - 1, anchor + 9 + rng.nextInt(5));
            while (idx > anchor && Game.toScene(path.get(idx)[0], path.get(idx)[1]) == null) idx--;
            int[] t = path.get(idx);
            if (Actions.walkTo(t[0], t[1])) {
                aim = t;
                lastClick = now;
                lead = 2 + rng.nextInt(3);
            }
            return Status.WALKING;
        }

        /** True when the route came from the collision map; false when it is a straight line. */
        public boolean planned() { return path != null && !straight; }

        private boolean openNearbyDoor() {
            Scenery.SceneObject door = Scenery.nearest(3, o -> o.isWall() && o.option("Open") > 0);
            return door != null && door.interact(door.option("Open"));
        }

        private static List<int[]> straightLine(int x0, int y0, int x1, int y1) {
            List<int[]> out = new ArrayList<>();
            int n = Math.max(Math.abs(x1 - x0), Math.abs(y1 - y0));
            for (int i = 1; i <= n; i++) {
                out.add(new int[] {x0 + Math.round((x1 - x0) * (float) i / n), y0 + Math.round((y1 - y0) * (float) i / n)});
            }
            return out;
        }
    }
}
