package oxclient.api;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import shortestpath.WorldPointUtil;
import shortestpath.pathfinder.CollisionMap;
import shortestpath.pathfinder.SplitFlagMap;
import shortestpath.transport.Transport;
import shortestpath.transport.TransportLoader;
import shortestpath.transport.TransportType;

/**
 * Walking anywhere: a route over the world's collision map, including stairs, ladders and doors, followed
 * a few tiles at a time with the game's own Walk here ({@link Actions#walkTo}).
 *
 * <p>The data is the Shortest Path plugin's ({@code collision-map.zip} and {@code transports.tsv}). The
 * search is breadth-first over (x, y, floor); a transport edge -- "Climb-up Staircase 16671" -- joins its
 * origin tile to its destination. Only plain transports with no requirements are used (no quests, items,
 * skill levels or game variables): stairs, ladders, doors, gates. Teleports, boats and shortcuts are not.</p>
 *
 * <p>Following a route: each click aims 9 to 13 tiles ahead and is re-sent when you are a few tiles from
 * it or have stopped; at a transport the route walks to its origin, then takes the object's option by the
 * name the transport gives ("Climb-up"), read off the object's own options. A route that stops making
 * progress opens the nearest closed door or gate it can see, and re-plans.</p>
 *
 * <p>Ground walking with the game's walk function was seen working on client-241-3 (2026-10-09); routes,
 * transports and doors are new and not yet seen.</p>
 */
public final class Walker {

    private Walker() {}

    private static volatile CollisionMap map;
    private static volatile Map<Integer, List<Transport>> transports = Collections.emptyMap();
    private static volatile boolean loading;
    private static volatile String loadError = "";

    /** True once the collision map and transports are loaded; the first call starts loading them. */
    public static boolean ready() {
        if (map != null) return true;
        synchronized (Walker.class) {
            if (!loading && map == null) {
                loading = true;
                Thread t = new Thread(() -> {
                    try {
                        CollisionMap m = new CollisionMap(SplitFlagMap.fromResources());
                        transports = usableTransports();
                        map = m;
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

    /** Why loading failed, or "". */
    public static String loadError() { return loadError; }

    /** Plain transports with no requirements, by packed origin. */
    private static Map<Integer, List<Transport>> usableTransports() {
        Map<Integer, List<Transport>> out = new HashMap<>();
        Map<Integer, Set<Transport>> all = TransportLoader.loadAllFromResources();
        for (Map.Entry<Integer, Set<Transport>> e : all.entrySet()) {
            for (Transport t : e.getValue()) {
                if (!t.isType(TransportType.TRANSPORT) || t.getObjectInfo() == null) continue;
                if (t.isQuestLocked() || t.getItemRequirements() != null || !t.getVarRequirements().isEmpty()) continue;
                boolean skills = false;
                for (int lvl : t.getSkillLevels()) if (lvl > 0) { skills = true; break; }
                if (skills || t.getOrigin() == Transport.UNDEFINED_ORIGIN || t.getDestination() == Transport.UNDEFINED_DESTINATION) continue;
                out.computeIfAbsent(t.getOrigin(), k -> new ArrayList<>()).add(t);
            }
        }
        return out;
    }

    /**
     * One step of a route: a tile, and -- when this step is reached by a transport from the step before --
     * that transport's object description ("Climb-up Staircase 16671").
     */
    public record Step(int x, int y, int plane, String transport) {}

    /**
     * A route from (fromX, fromY, fromPlane) to within {@code arrive} tiles of (toX, toY, toPlane), or empty
     * when there is none inside the search box (both points plus a 64-tile margin) or the map is not loaded.
     */
    public static List<Step> findPath(int fromX, int fromY, int fromPlane, int toX, int toY, int toPlane, int arrive) {
        CollisionMap m = map;
        if (m == null) return Collections.emptyList();
        final int margin = 64;
        final int minX = Math.min(fromX, toX) - margin, minY = Math.min(fromY, toY) - margin;
        final int w = Math.abs(fromX - toX) + 2 * margin + 1, h = Math.abs(fromY - toY) + 2 * margin + 1;
        if ((long) w * h * 4 > 2_000_000L) return Collections.emptyList();
        final int area = w * h;
        int[] parent = new int[area * 4];
        Arrays.fill(parent, -2);
        Transport[] via = new Transport[area * 4];
        int[] queue = new int[area * 4];
        int head = 0, tail = 0;
        int start = fromPlane * area + (fromY - minY) * w + (fromX - minX);
        parent[start] = -1;
        queue[tail++] = start;
        final int[] dx = {0, 0, 1, -1, 1, -1, 1, -1};
        final int[] dy = {1, -1, 0, 0, 1, 1, -1, -1};
        Map<Integer, List<Transport>> tr = transports;
        int goal = -1;
        while (head < tail) {
            int cur = queue[head++];
            int z = cur / area, rem = cur % area;
            int x = rem % w + minX, y = rem / w + minY;
            if (z == toPlane && Math.max(Math.abs(x - toX), Math.abs(y - toY)) <= arrive) { goal = cur; break; }
            for (int d = 0; d < 8; d++) {
                int nx = x + dx[d], ny = y + dy[d];
                if (nx < minX || ny < minY || nx >= minX + w || ny >= minY + h) continue;
                int ni = z * area + (ny - minY) * w + (nx - minX);
                if (parent[ni] != -2 || !canStep(m, x, y, d, z)) continue;
                parent[ni] = cur;
                queue[tail++] = ni;
            }
            List<Transport> ts = tr.get(WorldPointUtil.packWorldPoint(x, y, z));
            if (ts == null) continue;
            for (Transport t : ts) {
                int dst = t.getDestination();
                int tx = WorldPointUtil.unpackWorldX(dst), ty = WorldPointUtil.unpackWorldY(dst), tz = WorldPointUtil.unpackWorldPlane(dst);
                if (tx < minX || ty < minY || tx >= minX + w || ty >= minY + h || tz < 0 || tz > 3) continue;
                int ni = tz * area + (ty - minY) * w + (tx - minX);
                if (parent[ni] != -2) continue;
                parent[ni] = cur;
                via[ni] = t;
                queue[tail++] = ni;
            }
        }
        if (goal < 0) return Collections.emptyList();
        List<Step> out = new ArrayList<>();
        for (int c = goal; c != -1; c = parent[c]) {
            int z = c / area, rem = c % area;
            out.add(new Step(rem % w + minX, rem / w + minY, z, via[c] == null ? null : via[c].getObjectInfo()));
        }
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
     * Walking to one world tile. Call {@link #step} once per plugin tick until it says ARRIVED; it decides
     * on its own when to click.
     */
    public static final class Route {
        private static final long MIN_CLICK_GAP_MS = 600;
        private static final long STILL_MS = 1800;
        private static final long STUCK_MS = 9000;

        private final int destX, destY, destPlane, arrive;
        private final Random rng = new Random();
        private List<Step> path;
        private int anchor, best = Integer.MAX_VALUE, doorTries, lastPlane = -1;
        private Step aim;
        private int lastX, lastY, lead = 3;
        private long lastClick, lastMove, lastProgress;
        private boolean straight;

        /** To (x, y) on your current floor. */
        public Route(int worldX, int worldY, int arrive) { this(worldX, worldY, -1, arrive); }

        /** To (x, y) on floor {@code plane} (-1 = whatever floor you are on when the route is planned). */
        public Route(int worldX, int worldY, int plane, int arrive) {
            this.destX = worldX;
            this.destY = worldY;
            this.destPlane = plane;
            this.arrive = Math.max(0, arrive);
        }

        public int destX() { return destX; }

        public int destY() { return destY; }

        /** The tile the last click aimed at, as {x, y}, or null. */
        public int[] aim() { return aim == null ? null : new int[] {aim.x(), aim.y()}; }

        /** True when the route came from the collision map; false when it is a straight line. */
        public boolean planned() { return path != null && !straight; }

        public Status step() {
            Local me = Game.me();
            if (!me.exists()) return Status.WALKING;
            long now = System.currentTimeMillis();
            int mx = me.worldX(), my = me.worldY(), mz = me.plane();
            int targetPlane = destPlane < 0 ? mz : destPlane;
            if (mz == targetPlane && Math.max(Math.abs(mx - destX), Math.abs(my - destY)) <= arrive) return Status.ARRIVED;
            if (!ready()) return Status.LOADING;

            if (path == null || mz != lastPlane && !onPath(mx, my, mz)) {
                path = findPath(mx, my, mz, destX, destY, targetPlane, arrive);
                straight = path.isEmpty();
                if (straight && mz == targetPlane) path = straightLine(mx, my, mz, destX, destY);
                anchor = 0;
                best = Integer.MAX_VALUE;
                lastProgress = now;
                aim = null;
            }
            lastPlane = mz;
            if (path.isEmpty()) return Status.NO_ROUTE;
            if (mx != lastX || my != lastY) { lastX = mx; lastY = my; lastMove = now; }

            // the route step nearest you on your floor, only ever moving forward
            int bestI = anchor, bestD = Integer.MAX_VALUE;
            for (int i = anchor; i < path.size() && i < anchor + 40; i++) {
                Step s = path.get(i);
                if (s.plane() != mz) continue;
                int d = Math.max(Math.abs(s.x() - mx), Math.abs(s.y() - my));
                if (d < bestD) { bestD = d; bestI = i; }
            }
            anchor = bestI;
            int remaining = path.size() - anchor;
            if (remaining < best) { best = remaining; lastProgress = now; }
            if (now - lastProgress > STUCK_MS) {
                lastProgress = now;
                if (openNearbyDoor()) { doorTries++; return Status.WALKING; }
                path = null;
                return doorTries++ > 3 ? Status.STUCK : Status.WALKING;
            }
            if (now - lastClick < MIN_CLICK_GAP_MS) return Status.WALKING;

            // a transport within reach ahead: walk to its origin, then take it
            int limit = Math.min(path.size() - 1, anchor + 13);
            for (int i = anchor + 1; i <= limit; i++) {
                Step s = path.get(i);
                if (s.transport() == null) continue;
                Step origin = path.get(i - 1);
                if (Math.max(Math.abs(origin.x() - mx), Math.abs(origin.y() - my)) <= 2 && origin.plane() == mz) {
                    if (takeTransport(s.transport(), origin)) { lastClick = now; lastProgress = now; }
                    return Status.WALKING;
                }
                limit = i - 1;                            // walk no further than the transport's origin
                break;
            }

            boolean nearAim = aim != null && Math.max(Math.abs(aim.x() - mx), Math.abs(aim.y() - my)) <= lead;
            boolean still = now - lastMove > STILL_MS;
            if (aim != null && aim.plane() == mz && !nearAim && !still) return Status.WALKING;

            int idx = Math.min(limit, anchor + 9 + rng.nextInt(5));
            while (idx > anchor && (path.get(idx).plane() != mz || Game.toScene(path.get(idx).x(), path.get(idx).y()) == null)) idx--;
            Step t = path.get(idx);
            if (Actions.walkTo(t.x(), t.y())) {
                aim = t;
                lastClick = now;
                lead = 2 + rng.nextInt(3);
            }
            return Status.WALKING;
        }

        private boolean onPath(int x, int y, int z) {
            if (path == null) return false;
            for (Step s : path) if (s.plane() == z && Math.max(Math.abs(s.x() - x), Math.abs(s.y() - y)) <= 2) return true;
            return false;
        }

        /** "Climb-up Staircase 16671": the object with that id near the origin, the option the text starts with. */
        private static boolean takeTransport(String info, Step origin) {
            String[] parts = info.trim().split(" ");
            int id;
            try { id = Integer.parseInt(parts[parts.length - 1]); } catch (NumberFormatException e) { return false; }
            Scenery.SceneObject o = Scenery.nearest(6, s -> s.id() == id);
            if (o == null) return false;
            String[] ops = o.options();
            for (int i = 0; i < ops.length; i++) {
                if (!ops[i].isEmpty() && info.toLowerCase().startsWith(ops[i].toLowerCase() + " ")) return o.interact(i + 1);
            }
            return o.interact(1);
        }

        private static boolean openNearbyDoor() {
            Scenery.SceneObject door = Scenery.nearest(3, o -> o.isWall() && o.option("Open") > 0);
            return door != null && door.interact(door.option("Open"));
        }

        private static List<Step> straightLine(int x0, int y0, int z, int x1, int y1) {
            List<Step> out = new ArrayList<>();
            int n = Math.max(Math.abs(x1 - x0), Math.abs(y1 - y0));
            for (int i = 1; i <= n; i++) {
                out.add(new Step(x0 + Math.round((x1 - x0) * (float) i / n), y0 + Math.round((y1 - y0) * (float) i / n), z, null));
            }
            return out;
        }
    }
}
