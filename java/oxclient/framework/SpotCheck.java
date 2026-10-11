package oxclient.framework;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import oxclient.api.Walker;

/**
 * An offline check of every place the builder walks to, against the walking map -- no game needed:
 *
 * <pre>  java -cp build/dist/0xclient.jar oxclient.framework.SpotCheck</pre>
 *
 * For every training spot in {@link Methods} and every bank in {@link Places}: is there a route from Lumbridge
 * (the spawn), is the toll gate on it, and is the spot's own tile walkable. Prints one line per place and
 * exits 1 when any place is unreachable -- run it after changing a table, before
 * a live run. Both the coop gate and the toll problems of 2026-10-10 were visible this way before a run.
 * What it cannot see: doors and gates (the map treats them as open), and NPCs.
 */
public final class SpotCheck {

    private SpotCheck() {}

    /** Where every route starts: the Lumbridge spawn. */
    static final int[] FROM = {3222, 3218, 0};

    public static void main(String[] args) throws Exception {
        Walker.ready();
        long until = System.currentTimeMillis() + 120_000;
        while (!Walker.ready()) {
            if (!Walker.loadError().isEmpty()) { System.out.println("walking map failed: " + Walker.loadError()); System.exit(2); }
            if (System.currentTimeMillis() > until) { System.out.println("walking map did not load"); System.exit(2); }
            Thread.sleep(200);
        }
        Map<String, Place> places = new LinkedHashMap<>();
        for (Place b : Places.BANKS) places.put(b.name(), b);
        places.put(Places.GRAND_EXCHANGE.name(), Places.GRAND_EXCHANGE);
        List<String> uses = new ArrayList<>();
        for (var e : Methods.F2P.entrySet())
            for (Methods.Method m : e.getValue())
                for (Place p : m.spots()) { places.putIfAbsent(p.name(), p); uses.add(p.name() + " <- " + m.label()); }
        int bad = 0;
        for (Place p : places.values()) {
            boolean blocked = Walker.blocked(p.x(), p.y(), p.plane());
            int free = Walker.findPath(FROM[0], FROM[1], FROM[2], p.x(), p.y(), p.plane(), Math.max(1, p.radius() / 2), false).size();
            int paid = free > 0 ? free : Walker.findPath(FROM[0], FROM[1], FROM[2], p.x(), p.y(), p.plane(), Math.max(1, p.radius() / 2), true).size();
            // a blocked centre is fine when the route still arrives near it (a tree spot's centre is often the tree);
            // the route is planned to within radius/2, as the tasks walk
            String verdict = (free > 0 ? "ok" : paid > 0 ? "ok, through the toll gate" : "NO ROUTE") + (blocked ? " (centre tile blocked)" : "");
            if (paid == 0) bad++;
            System.out.printf("%-34s %5d,%d,%d  %-26s %s%n", p.name(), p.x(), p.y(), p.plane(), verdict, paid > 0 ? paid + " steps" : "");
        }
        System.out.println(places.size() + " places, " + bad + " problem(s)");
        System.exit(bad == 0 ? 0 : 1);
    }
}
