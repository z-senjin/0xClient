package oxclient.framework;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import oxclient.api.Skills;
import oxclient.persist.JsonStore;

/**
 * One line per stint, saved per character ({@code ~/.0xclient/scripts/<character>/history.json}, the last
 * {@link #KEEP}): the goal, the levels before and after, the experience gained, how long it took, the methods
 * and spots used, and how it ended. So a run can be judged without reading the raw log -- which methods are
 * slow, which spots keep failing. The panel shows the current stint's experience per hour and the last few.
 */
public final class History {

    static final int KEEP = 200;

    private List<Map<String, Object>> rows;
    private Goal goal;
    private long start, startXp;
    private int startLevel;
    private final Set<String> methods = new LinkedHashSet<>(), spots = new LinkedHashSet<>(), notes = new LinkedHashSet<>();

    /** A stint begins. */
    public void start(Goal g) {
        goal = g;
        start = System.currentTimeMillis();
        startXp = Skills.experience(g.skill());
        startLevel = Skills.level(g.skill());
        methods.clear();
        spots.clear();
        notes.clear();
    }

    /** A task began within the stint. */
    public void method(String label, String spot) {
        if (goal == null) return;
        if (label != null && !label.isEmpty()) methods.add(label);
        if (spot != null && !spot.isEmpty()) spots.add(spot);
    }

    /** Something worth keeping with the stint ("died"). */
    public void note(String what) { if (goal != null) notes.add(what); }

    /** The stint ended (reached, time up, parked ...): append its line and save. */
    public void end(String result) {
        if (goal == null) return;
        long now = System.currentTimeMillis();
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("start", start);
        r.put("minutes", Math.round((now - start) / 6000.0) / 10.0);
        r.put("goal", goal.toString());
        r.put("from", startLevel);
        r.put("to", Skills.level(goal.skill()));
        r.put("xp", Skills.experience(goal.skill()) - startXp);
        r.put("methods", String.join(", ", methods));
        r.put("spots", String.join(", ", spots));
        r.put("result", result + (notes.isEmpty() ? "" : " (" + String.join(", ", notes) + ")"));
        rows().add(r);
        while (rows().size() > KEEP) rows().remove(0);
        Path f = Account.file("history");
        if (f != null) { Map<String, Object> m = new LinkedHashMap<>(); m.put("stints", rows()); JsonStore.write(f, m); }
        System.out.println("[builder] stint: " + r);
        goal = null;
    }

    /** Experience per hour in the current stint, or -1 when there is none (or under a minute in). */
    public long xpPerHour() {
        if (goal == null) return -1;
        long ms = System.currentTimeMillis() - start;
        if (ms < 60_000) return -1;
        return (Skills.experience(goal.skill()) - startXp) * 3_600_000L / ms;
    }

    /** The last {@code n} stints, newest first, one short line each: "Fishing 99: 12->15, +3,450 xp in 32 min (stint over)". */
    public List<String> recent(int n) {
        List<String> out = new ArrayList<>();
        List<Map<String, Object>> rs = rows();
        for (int i = rs.size() - 1; i >= 0 && out.size() < n; i--) {
            Map<String, Object> r = rs.get(i);
            out.add(r.get("goal") + ": " + num(r.get("from")) + "->" + num(r.get("to")) + ", +" + String.format("%,d", num(r.get("xp")))
                    + " xp in " + Math.round(dbl(r.get("minutes"))) + " min (" + r.get("result") + ")");
        }
        return out;
    }

    private static long num(Object o) { return o instanceof Number x ? x.longValue() : 0; }

    private static double dbl(Object o) { return o instanceof Number x ? x.doubleValue() : 0; }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> rows() {
        if (rows != null) return rows;
        rows = new ArrayList<>();
        Path f = Account.file("history");
        Map<String, Object> m = f == null ? null : JsonStore.read(f);
        if (m != null && m.get("stints") instanceof List<?> l)
            for (Object o : l) if (o instanceof Map<?, ?> r) rows.add((Map<String, Object>) r);
        return rows;
    }
}
