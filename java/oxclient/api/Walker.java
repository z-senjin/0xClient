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
    /**
     * The toll gate as transports ("Open Gate 44050"), by origin: used only by a route planned with the coins
     * for it, and the ONLY way such a route crosses the gate (its plain steps stay locked, {@link #tollSteps}).
     * So the route has a transport step there, and the walker walks up to the gate and goes through it before
     * clicking beyond -- the way it takes stairs. Without this a route crossed the gate as two ordinary tiles,
     * the walker clicked past the shut gate, and the game walked the character along the fence to the nearest
     * point on its own side (3264,3202), where it stood (live 2026-10-10).
     */
    private static volatile Map<Integer, List<Transport>> tollTransports = Collections.emptyMap();
    /**
     * Steps the route must NOT take on foot: gates and doors that are transports WITH a requirement (the
     * Al Kharid toll gate wants 10 coins or a quest). The collision map lets you walk through them, so
     * without this the route would lead straight to a gate the character cannot pass -- seen live
     * 2026-10-09, a route to Al Kharid bank stuck at the toll gate. Keys are packed (from, to) tile pairs.
     */
    private static volatile Set<Long> lockedSteps = Collections.emptySet();

    /**
     * Gates the data lists WITHOUT a requirement that nonetheless stop you: the Al Kharid toll gate
     * ("Open Gate 44050" / "44051" in transports.tsv) opens a guard's "pay 10 coins" conversation rather
     * than the gate. Its steps are locked like a gate with a requirement. Found live 2026-10-09 (a route
     * to Al Kharid bank stuck there). A gate found at run time with a "Pay-..." option is locked too.
     */
    private static final Set<Integer> AVOID_OBJECTS = Set.of(44050, 44051);

    /**
     * The toll gate's own steps (AVOID_OBJECTS), kept apart from {@link #lockedSteps}: a route may cross them when
     * it is planned with coins for the toll (10 gp) in the inventory -- the user's choice, 2026-10-09: pay the
     * Al Kharid toll once the account has gold. Crossing one means paying at the gate ({@code Pay-toll}).
     */
    private static volatile Set<Long> tollSteps = Collections.emptySet();
    public static final int TOLL_COINS = 10;

    /** Steps locked at run time (a gate that turned out to charge); added to the planner's locked set. */
    private static final Set<Long> lockedAtRuntime = java.util.concurrent.ConcurrentHashMap.newKeySet();
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
                        transports = usableTransports(false);
                        tollTransports = usableTransports(true);
                        lockedSteps = lockedSteps(false);
                        tollSteps = lockedSteps(true);
                        map = m;
                        System.out.println("[walker] map loaded: " + transports.size() + " transport origins, " + lockedSteps.size() + " locked steps, " + tollSteps.size() + " toll steps");
                    } catch (Throwable e) {
                        loadError = String.valueOf(e);
                        System.out.println("[walker] loading the map failed: " + e);
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

    /** Plain transports with no requirements, by packed origin (toll: only the toll gate's). */
    private static Map<Integer, List<Transport>> usableTransports(boolean toll) {
        Map<Integer, List<Transport>> out = new HashMap<>();
        Map<Integer, Set<Transport>> all = TransportLoader.loadAllFromResources();
        for (Map.Entry<Integer, Set<Transport>> e : all.entrySet()) {
            for (Transport t : e.getValue()) {
                if (!t.isType(TransportType.TRANSPORT) || t.getObjectInfo() == null) continue;
                if (t.isQuestLocked() || t.getItemRequirements() != null || !t.getVarRequirements().isEmpty()) continue;
                boolean skills = false;
                for (int lvl : t.getSkillLevels()) if (lvl > 0) { skills = true; break; }
                if (skills || t.getOrigin() == Transport.UNDEFINED_ORIGIN || t.getDestination() == Transport.UNDEFINED_DESTINATION) continue;
                if (AVOID_OBJECTS.contains(objectId(t.getObjectInfo())) != toll) continue;
                out.computeIfAbsent(t.getOrigin(), k -> new ArrayList<>()).add(t);
            }
        }
        return out;
    }

    /** Every adjacent (from, to) step that a transport with a requirement covers (toll: the toll gate's), both ways. */
    private static Set<Long> lockedSteps(boolean toll) {
        Set<Long> out = new java.util.HashSet<>();
        for (Set<Transport> ts : TransportLoader.loadAllFromResources().values()) {
            for (Transport t : ts) {
                if (t.getOrigin() == Transport.UNDEFINED_ORIGIN || t.getDestination() == Transport.UNDEFINED_DESTINATION) continue;
                boolean isToll = AVOID_OBJECTS.contains(objectId(t.getObjectInfo()));
                if (toll ? !isToll : (isToll || !hasRequirement(t))) continue;
                int o = t.getOrigin(), d = t.getDestination();
                if (WorldPointUtil.unpackWorldPlane(o) != WorldPointUtil.unpackWorldPlane(d)) continue;
                int dx = Math.abs(WorldPointUtil.unpackWorldX(o) - WorldPointUtil.unpackWorldX(d));
                int dy = Math.abs(WorldPointUtil.unpackWorldY(o) - WorldPointUtil.unpackWorldY(d));
                if (dx > 1 || dy > 1) continue;                   // only steps that are otherwise walkable
                out.add(stepKey(o, d));
                out.add(stepKey(d, o));
            }
        }
        return out;
    }

    private static boolean hasRequirement(Transport t) {
        if (t.isQuestLocked() || t.getItemRequirements() != null || !t.getVarRequirements().isEmpty()) return true;
        for (int lvl : t.getSkillLevels()) if (lvl > 0) return true;
        return false;
    }

    private static long stepKey(int from, int to) { return ((long) from << 32) | (to & 0xFFFFFFFFL); }

    private static boolean isLocked(Set<Long> locked, int x, int y, int nx, int ny, int z) {
        long key = stepKey(WorldPointUtil.packWorldPoint(x, y, z), WorldPointUtil.packWorldPoint(nx, ny, z));
        return locked.contains(key) || lockedAtRuntime.contains(key);
    }

    /**
     * Does the step (x, y) -> (nx, ny) cross a locked gate? A diagonal step crosses the same edges as its two
     * straight halves, so it is checked against both orderings -- otherwise a route slips past a locked gate
     * corner-wise (it did, through the Al Kharid toll gate, in an offline test).
     */
    private static boolean crossesLocked(Set<Long> locked, int x, int y, int nx, int ny, int z) {
        if (locked.isEmpty() && lockedAtRuntime.isEmpty()) return false;
        if (isLocked(locked, x, y, nx, ny, z)) return true;
        if (x == nx || y == ny) return false;
        return isLocked(locked, x, y, nx, y, z) || isLocked(locked, nx, y, nx, ny, z)
            || isLocked(locked, x, y, x, ny, z) || isLocked(locked, x, ny, nx, ny, z);
    }

    /** The object id at the end of "Climb-up Staircase 16671", or -1. */
    static int objectId(String info) {
        if (info == null) return -1;
        String[] parts = info.trim().split(" ");
        try { return Integer.parseInt(parts[parts.length - 1]); } catch (NumberFormatException e) { return -1; }
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
        return findPath(fromX, fromY, fromPlane, toX, toY, toPlane, arrive, canPayToll());
    }

    /** Is the tile blocked on the collision map (a wall, a tree, water)? False before the map is loaded. */
    public static boolean blocked(int x, int y, int plane) {
        CollisionMap m = map;
        return m != null && m.isBlocked(x, y, plane);
    }

    /** Coins for the toll gate in the inventory right now. */
    public static boolean canPayToll() { return Inventory.amount("Coins") >= TOLL_COINS; }

    /**
     * Does going from a to b need the toll gate -- no route without paying, a route with it? (Al Kharid from
     * Lumbridge, or the way back.) Two searches; for setting up a trip, not every tick.
     */
    public static boolean tollNeeded(int ax, int ay, int az, int bx, int by, int bz, int arrive) {
        if (map == null) return false;
        return findPath(ax, ay, az, bx, by, bz, arrive, false).isEmpty() && !findPath(ax, ay, az, bx, by, bz, arrive, true).isEmpty();
    }

    /** As {@link #findPath(int, int, int, int, int, int, int)}, crossing the toll gate only when {@code payToll}. */
    public static List<Step> findPath(int fromX, int fromY, int fromPlane, int toX, int toY, int toPlane, int arrive, boolean payToll) {
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
        Set<Long> locked = lockedSteps;
        // the toll gate's plain steps are always locked; with the coins, the gate is a transport edge instead
        Set<Long> toll = tollSteps;
        Map<Integer, List<Transport>> tollTr = payToll ? tollTransports : Collections.emptyMap();
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
                if (crossesLocked(locked, x, y, nx, ny, z) || (!toll.isEmpty() && crossesLocked(toll, x, y, nx, ny, z))) continue;
                parent[ni] = cur;
                queue[tail++] = ni;
            }
            int here = WorldPointUtil.packWorldPoint(x, y, z);
            List<Transport> ts = tr.get(here);
            List<Transport> tt = tollTr.get(here);
            if (tt != null) { ts = ts == null ? tt : new ArrayList<>(ts); if (ts != tt) ts.addAll(tt); }
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

    /**
     * The length of the route from you to (x, y, plane), in steps, or -1 when there is none or the map is
     * not loaded. For choosing between destinations by how far they really are, not as the crow flies.
     */
    public static int routeLength(int toX, int toY, int toPlane, int arrive) {
        Local me = Game.me();
        if (!me.exists() || map == null) return -1;
        List<Step> p = findPath(me.worldX(), me.worldY(), me.plane(), toX, toY, toPlane, arrive);
        return p.isEmpty() ? -1 : p.size();
    }

    /** How a {@link Route#step} went. */
    /**
     * How a {@link Route#step} went. NEED_TOLL: there is no route without the toll gate and you do not carry the
     * coins for it -- the caller fetches coins (a bank trip toward the place does: BankTrip) and walks again.
     */
    public enum Status { ARRIVED, WALKING, LOADING, NO_ROUTE, STUCK, NEED_TOLL }

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
        private long lastClick, lastMove, lastProgress, lastTransport, doorClickAt, tollAt;
        private int tollTries;
        /** Clicks on each shut door/gate ("id@x,y"), to give up on one that will not open. */
        private final Map<String, Integer> doorClicks = new HashMap<>();
        private static final int DOOR_CLICKS_MAX = 4;
        private String lastDoor = "";
        private boolean straight, payToll;

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
            Run.keepOn();                                   // run when energy is over 60% (Run)
            // through the toll gate: "Open" starts the guard's talk -- continue it and answer "Yes, ok." (pay 10 gp)
            if (now - tollAt < 20_000 && Dialog.isOpen()) {
                if (now - lastClick < MIN_CLICK_GAP_MS) return Status.WALKING;
                lastClick = now;
                List<String> ops = Dialog.options();
                if (!ops.isEmpty()) {
                    log("toll gate guard asks: " + String.join(" / ", ops));
                    if (!Dialog.choose("yes")) Dialog.choose(1);
                } else Dialog.continueDialog();
                // the walk through the gate starts when the talk ends: wait for it like any transport. Live
                // 2026-10-10 it clicked the gate again the moment the talk ended, while the player was passing
                lastProgress = now;
                lastTransport = now;
                return Status.WALKING;
            }

            if (path == null || mz != lastPlane && !onPath(mx, my, mz)) {
                payToll = canPayToll();
                path = findPath(mx, my, mz, destX, destY, targetPlane, arrive, payToll);
                straight = path.isEmpty();
                // A straight line only where the map has no data (an instance): where it HAS data, no route
                // means there is none -- walking straight at it is how a character ends up at a toll gate.
                if (straight && mz == targetPlane && map.isBlocked(mx, my, mz)) path = straightLine(mx, my, mz, destX, destY);
                StringBuilder tl = new StringBuilder();
                for (Step st : path) if (st.transport() != null) tl.append(" | ").append(st.transport()).append(" -> ").append(st.x()).append(',').append(st.y()).append(',').append(st.plane());
                log("route from " + mx + "," + my + "," + mz + " to " + destX + "," + destY + "," + targetPlane + ": "
                        + (path.isEmpty() ? "NONE" : path.size() + " steps" + (straight ? " (straight line)" : "")) + tl);
                anchor = 0;
                best = Integer.MAX_VALUE;
                lastProgress = now;
                aim = null;
            }
            lastPlane = mz;
            if (path.isEmpty()) {
                if (!payToll && !findPath(mx, my, mz, destX, destY, targetPlane, arrive, true).isEmpty()) {
                    log("the way to " + destX + "," + destY + " is through the toll gate; carrying " + Inventory.amount("Coins") + " coins (needs " + TOLL_COINS + ")");
                    path = null;                                   // plan again once the coins are there
                    return Status.NEED_TOLL;
                }
                return Status.NO_ROUTE;
            }
            if (mx != lastX || my != lastY) { lastX = mx; lastY = my; lastMove = now; }

            // the route step nearest you on your floor, only ever moving forward
            int bestI = anchor, bestD = Integer.MAX_VALUE;
            for (int i = anchor; i < path.size() && i < anchor + 40; i++) {
                Step s = path.get(i);
                if (s.plane() != mz) continue;
                int d = Math.max(Math.abs(s.x() - mx), Math.abs(s.y() - my));
                if (d < bestD) { bestD = d; bestI = i; }
            }
            // only a step you are really at counts as progress: the nearest step can be on the far side of a fence
            // (it was, 3 tiles away through the cow pen fence, 2026-10-10), which read as progress while you stood
            if (bestD <= 2) anchor = bestI;
            else if (bestD > 8 && now - lastClick > 1500 && mz == lastPlane) {
                log("off the route at " + mx + "," + my + " (" + bestD + " tiles from it): planning again");
                path = null;
                return Status.WALKING;
            }
            int remaining = path.size() - anchor;
            if (remaining < best) { best = remaining; lastProgress = now; }
            if (now - lastProgress > STUCK_MS) {
                lastProgress = now;
                if (doorTries > 5) return Status.STUCK;          // a door that will not open
                if (openDoorOnRoute(mx, my) || openNearbyDoor()) { doorTries++; return Status.WALKING; }
                log("stuck at " + mx + "," + my + ": no closed door or gate near the route, planning again; objects by the route ahead:" + objectsAhead());
                path = null;
                return doorTries++ > 3 ? Status.STUCK : Status.WALKING;
            }
            if (now - lastClick < MIN_CLICK_GAP_MS) return Status.WALKING;

            // a shut door or gate ON the route ahead: open it before walking on. The collision map treats doors
            // and gates as open, so the route goes through them whether or not they are shut; clicking a tile past
            // a shut one makes the game walk you to the nearest point on YOUR side (live 2026-10-10, the Lumbridge
            // cow pen gate 3176,3315: twice the character stood at the fence at 3154,3316). Opened as soon as it
            // is within 15 route steps -- the game walks you to it -- and never clicked past while shut.
            {
                int[] at = {-1};
                Scenery.SceneObject d = shutDoorAhead(at, mz);
                if (d != null) {
                    String key = d.id() + "@" + d.worldX() + "," + d.worldY();
                    int dist = Math.max(Math.abs(d.worldX() - mx), Math.abs(d.worldY() - my));
                    boolean stood = now - lastMove > 1200;
                    // first click on this door at once; after that only once you have stopped (the game walks you
                    // there first -- up to 15 tiles) or 8 s have passed, and no faster than every 2.4 s
                    boolean again = key.equals(lastDoor) && (now - doorClickAt < 2400 || (!stood && now - doorClickAt < 8000));
                    if (again) return Status.WALKING;
                    lastDoor = key;
                    // only clicks made beside the door count toward giving up on it
                    int n = dist <= 2 ? doorClicks.merge(key, 1, Integer::sum) : doorClicks.getOrDefault(key, 0);
                    if (n > DOOR_CLICKS_MAX) {
                        // it does not open (locked, or it needs something): never route through it again
                        lockAround(at[0]);
                        log("the " + d.name() + "#" + d.id() + " at " + d.worldX() + "," + d.worldY() + " did not open after "
                                + DOOR_CLICKS_MAX + " clicks: planning a way around it");
                        doorClicks.remove(key);
                        path = null;
                        return Status.WALKING;
                    }
                    log("opening " + d.name() + "#" + d.id() + " at " + d.worldX() + "," + d.worldY() + " on the route (step " + at[0]
                            + ", you are near step " + anchor + ", " + dist + " tiles away, tries beside it " + n + ")");
                    if (d.interact(d.option("Open"))) { doorClickAt = now; lastClick = now; lastProgress = now; aim = null; }
                    return Status.WALKING;
                }
            }

            // a transport within reach ahead: walk to its origin, then take it
            int limit = Math.min(path.size() - 1, anchor + 13);
            for (int i = anchor + 1; i <= limit; i++) {
                Step s = path.get(i);
                if (s.transport() == null) continue;
                if (s.x() == mx && s.y() == my && s.plane() == mz) continue;       // already through it
                Step origin = path.get(i - 1);
                if (Math.max(Math.abs(origin.x() - mx), Math.abs(origin.y() - my)) <= 2 && origin.plane() == mz) {
                    if (now - lastTransport < 2500) return Status.WALKING;   // give the climb or door time
                    Taken t = takeTransport(s.transport(), payToll, origin, s, tollTries++);
                    if (t == Taken.CLICKED && isToll(s.transport())) tollAt = now;      // the guard may talk: answer it below
                    if (t == Taken.CLICKED) { lastClick = now; lastTransport = now; lastProgress = now; return Status.WALKING; }
                    boolean door = s.plane() == origin.plane() && Math.max(Math.abs(s.x() - origin.x()), Math.abs(s.y() - origin.y())) <= 1;
                    if (t == Taken.REFUSED) {
                        // a gate that charges: never route through it again, and plan around it
                        lockedAtRuntime.add(stepKey(WorldPointUtil.packWorldPoint(origin.x(), origin.y(), origin.plane()), WorldPointUtil.packWorldPoint(s.x(), s.y(), s.plane())));
                        lockedAtRuntime.add(stepKey(WorldPointUtil.packWorldPoint(s.x(), s.y(), s.plane()), WorldPointUtil.packWorldPoint(origin.x(), origin.y(), origin.plane())));
                        path = null;
                        return Status.WALKING;
                    }
                    if (door) {
                        // the door's closed object is not there: it stands open, so walk straight through
                        if (Actions.walkTo(s.x(), s.y())) { lastClick = now; aim = s; }
                        return Status.WALKING;
                    }
                    return Status.WALKING;                    // a staircase not loaded yet: try next tick
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

        enum Taken { CLICKED, MISSING, REFUSED }

        /**
         * "Climb-up Staircase 16671": the object with that id near you, and the option the text starts with.
         * MISSING when no such object is in sight (an open door has a different id); REFUSED for a gate that
         * offers "Pay-..." (a toll -- the walker does not pay).
         */
        private static boolean isToll(String info) { return AVOID_OBJECTS.contains(objectId(info)); }

        /**
         * The object of a transport when its id is not in sight: a wall object ON the transport's own tiles (origin or
         * destination). The toll gate's ids in the walking data (44050/44051) are stale on this game version -- live
         * 2026-10-10 the gate was 44598/44599, with no readable name or options (its displayed form depends on a
         * game variable), so neither the id nor the name matched and the walker stood at the gate.
         */
        private static Scenery.SceneObject onTiles(Step a, Step b) {
            for (Scenery.SceneObject o : Scenery.all(6))
                if (o.isWall() && ((o.worldX() == a.x() && o.worldY() == a.y()) || (o.worldX() == b.x() && o.worldY() == b.y()))) return o;
            return null;
        }

        private static Taken takeTransport(String info, boolean payToll, Step from, Step to, int tries) {
            int id = objectId(info);
            String[] words = info.trim().split(" ");
            String option = words.length > 0 ? words[0] : "";
            String name = words.length > 2 ? String.join(" ", java.util.Arrays.copyOfRange(words, 1, words.length - 1)) : "";
            // by id first; then -- ids change between game updates, the transport data may lag -- by the
            // object's name with an option of that text ("Staircase" with "Climb-up")
            Scenery.SceneObject o = Scenery.nearest(8, s -> s.id() == id);
            if (o == null && !name.isEmpty()) o = Scenery.nearest(8, s -> s.name().equalsIgnoreCase(name) && s.option(option) > 0);
            if (o == null && from != null && to != null && from.plane() == to.plane()) {
                Scenery.SceneObject w = onTiles(from, to);
                if (w != null) {
                    // a nameless object on the transport's tiles: take it by number, option 1. The toll gate: option 1 is
                    // "Open" -- the guard asks, the walker answers "Yes, okay." -- SEEN WORKING 2026-10-10 ("You pay the
                    // guards and pass through the gate."); option 2 did nothing that day
                    int op = 1;
                    if (isToll(info) && !payToll) { log("transport \"" + info + "\": refused, it charges"); return Taken.REFUSED; }
                    log("transport \"" + info + "\": not in sight by id; taking " + (w.name().isEmpty() ? "?" : w.name()) + "#" + w.id()
                            + " on its tile " + w.worldX() + "," + w.worldY() + " by option " + op + " (try " + (tries + 1) + ")");
                    return w.interact(op) ? Taken.CLICKED : Taken.MISSING;
                }
            }
            if (o == null) {
                StringBuilder near = new StringBuilder();
                for (Scenery.SceneObject n : Scenery.all(4)) near.append(' ').append(n.name().isEmpty() ? "?" : n.name()).append('#').append(n.id()).append(n.isWall() ? "(wall)" : "");
                log("transport \"" + info + "\": no such object in sight; within 4 tiles:" + near);
                return Taken.MISSING;
            }
            String[] ops = o.options();
            int pay = tollOption(o);
            if (pay > 0 && !(payToll && isToll(info))) { log("transport \"" + info + "\": refused, it charges"); return Taken.REFUSED; }
            int pick = pay > 0 ? pay : o.option(option);                 // the toll gate: pay, and it lets you through
            if (pick < 0) pick = 1;
            log("transport \"" + info + "\": " + o.name() + "#" + o.id() + " at " + o.worldX() + "," + o.worldY() + " option " + pick + " (" + String.join("/", ops) + ")");
            return o.interact(pick) ? Taken.CLICKED : Taken.MISSING;
        }

        private static long lastLog;
        private static String lastLine = "";

        /** One line to the client log (OXC_LOG), not repeated within 5 seconds. */
        private static void log(String line) {
            long now = System.currentTimeMillis();
            if (line.equals(lastLine) && now - lastLog < 5000) return;
            lastLine = line;
            lastLog = now;
            System.out.println("[walker] " + line);
        }

        /**
         * The closed door or gate ON THE ROUTE that is stopping you. The collision map treats gates as open, so a
         * route can lead through one the game has shut. Seen live 2026-10-10: leaving the Lumbridge cow pen
         * (3173,3326) the route went out through the gate at about 3176,3315; the gate was shut, so the game
         * walked the character to the pen's fence nearest the next click (3154,3316) and it stood there. The old
         * check only looked 3 tiles around you; the gate was 22 away. Now: every object with "Open" (a wall
         * object, or one named gate/door) within a tile of a route step up to 25 steps ahead, the earliest on the
         * route first -- you cannot be past a gate that is still shut.
         */
        private boolean openDoorOnRoute(int mx, int my) {
            if (path == null || path.isEmpty()) return false;
            int last = Math.min(path.size() - 1, anchor + 25);
            Scenery.SceneObject pick = null;
            int pickAt = Integer.MAX_VALUE;
            // the whole loaded scene: once the game has walked you along the wrong side of a fence, the gate can be
            // well over 20 tiles away (it was 22 on 2026-10-10)
            for (Scenery.SceneObject o : Scenery.all(48)) {
                if (!isShutDoor(o)) continue;
                int ox = o.worldX(), oy = o.worldY();
                for (int i = 0; i <= last && i < pickAt; i++) {
                    Step st = path.get(i);
                    if (Math.max(Math.abs(st.x() - ox), Math.abs(st.y() - oy)) <= 1) { pick = o; pickAt = i; break; }
                }
            }
            if (pick == null) return false;
            log("stuck at " + mx + "," + my + ": opening " + pick.name() + "#" + pick.id() + " at " + pick.worldX() + "," + pick.worldY()
                    + " (route step " + pickAt + ", you are near step " + anchor + ")");
            return pick.interact(pick.option("Open"));
        }

        /** A door or gate that is shut: it offers "Open" (an open one offers "Close"), and is not a toll. */
        private static boolean isShutDoor(Scenery.SceneObject o) {
            if (o.option("Open") <= 0 || AVOID_OBJECTS.contains(o.id())) return false;   // the toll gate is a transport step
            String n = o.name().toLowerCase();
            if (!o.isWall() && !n.contains("gate") && !n.contains("door")) return false;
            return java.util.Arrays.stream(o.options()).noneMatch(op -> op.toLowerCase().startsWith("pay"));
        }

        /**
         * The first shut door or gate standing ON a route tile from just behind you to 15 steps ahead, or null;
         * {@code at[0]} gets that step's index. On the tile itself, not beside it: a door in a wall the route only
         * walks past is left alone.
         */
        /** The toll gate's paying option ("Pay-toll(10gp)"), or -1. */
        private static int tollOption(Scenery.SceneObject o) {
            String[] ops = o.options();
            for (int i = 0; i < ops.length; i++) if (ops[i].toLowerCase().startsWith("pay")) return i + 1;
            return -1;
        }

        private Scenery.SceneObject shutDoorAhead(int[] at, int mz) {
            if (path == null || path.isEmpty()) return null;
            List<Scenery.SceneObject> doors = new ArrayList<>();
            for (Scenery.SceneObject o : Scenery.all(18)) if (isShutDoor(o)) doors.add(o);
            if (doors.isEmpty()) return null;
            int last = Math.min(path.size() - 1, anchor + 15);
            for (int i = Math.max(0, anchor - 1); i <= last; i++) {
                Step s = path.get(i);
                if (s.plane() != mz) continue;
                for (Scenery.SceneObject o : doors) if (o.worldX() == s.x() && o.worldY() == s.y()) { at[0] = i; return o; }
            }
            return null;
        }

        /** Lock the route's steps into and out of step {@code i} (both ways), so the planner goes around. */
        private void lockAround(int i) {
            for (int j = Math.max(1, i); j <= Math.min(path.size() - 1, i + 1); j++) {
                Step a = path.get(j - 1), b = path.get(j);
                if (a.plane() != b.plane()) continue;
                int pa = WorldPointUtil.packWorldPoint(a.x(), a.y(), a.plane()), pb = WorldPointUtil.packWorldPoint(b.x(), b.y(), b.plane());
                lockedAtRuntime.add(stepKey(pa, pb));
                lockedAtRuntime.add(stepKey(pb, pa));
            }
        }

        /** For the log when stuck: every object with options within a tile of the next route steps, "Gate#883(Open)". */
        private String objectsAhead() {
            if (path == null || path.isEmpty()) return " (no route)";
            StringBuilder b = new StringBuilder();
            java.util.Set<String> seen = new java.util.HashSet<>();
            int last = Math.min(path.size() - 1, anchor + 20);
            for (Scenery.SceneObject o : Scenery.all(30)) {
                String[] ops = o.options();
                StringBuilder os = new StringBuilder();
                for (String op : ops) if (!op.isEmpty()) os.append(os.length() == 0 ? "" : "/").append(op);
                if (os.length() == 0 && !o.isWall()) continue;           // walls too: a gate can show no name or options
                if (os.length() == 0) os.append("no options");
                for (int i = Math.max(0, anchor - 5); i <= last; i++) {
                    Step st = path.get(i);
                    if (Math.max(Math.abs(st.x() - o.worldX()), Math.abs(st.y() - o.worldY())) > 1) continue;
                    String k = o.name() + "#" + o.id() + "@" + o.worldX() + "," + o.worldY();
                    if (seen.add(k)) b.append(' ').append(o.name().isEmpty() ? "?" : o.name()).append('#').append(o.id()).append(" at ").append(o.worldX()).append(',').append(o.worldY()).append(" (").append(os).append(')');
                    break;
                }
            }
            return b.length() == 0 ? " none" : b.toString();
        }

        private static boolean openNearbyDoor() {
            // never a gate that charges or needs something: those offer "Pay-toll" and the like
            Scenery.SceneObject door = Scenery.nearest(3, o -> o.isWall() && o.option("Open") > 0
                    && java.util.Arrays.stream(o.options()).noneMatch(op -> op.toLowerCase().startsWith("pay")));
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
