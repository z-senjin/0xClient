package oxclient.framework;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import oxclient.api.Skill;

/**
 * Turns a plan written as text into tasks: {@code "woodcutting 30, mining 15, fishing 20"}.
 *
 * <p>Each entry is a skill and a target level; the skill's training methods below split it into level bands
 * (normal trees to 15, oaks to 30, willows after). Also understood: {@code "walk <place>"} and
 * {@code "buy <amount> <item name>"} (the Grand Exchange, once mapped). Unknown entries are reported, not
 * guessed.</p>
 */
public final class Plans {

    private Plans() {}

    /** One way to train: from a level, at a place, from a resource, with these tools. */
    public record Method(int fromLevel, String label, Place spot, GatherTask.Resource resource, List<String> tools) {}

    static final List<Method> WOODCUTTING = List.of(
            new Method(1, "Woodcutting (trees)", Places.LUMBRIDGE_TREES, GatherTask.Resource.object("Tree"), List.of("axe")),
            new Method(15, "Woodcutting (oaks)", Places.DRAYNOR_OAKS, GatherTask.Resource.object("Oak tree", "Oak"), List.of("axe")),
            new Method(30, "Woodcutting (willows)", Places.DRAYNOR_WILLOWS, GatherTask.Resource.object("Willow tree", "Willow"), List.of("axe")));

    static final List<Method> MINING = List.of(
            new Method(1, "Mining (copper & tin)", Places.VARROCK_EAST_MINE, GatherTask.Resource.object("Copper rocks", "Tin rocks"), List.of("pickaxe")),
            new Method(15, "Mining (iron)", Places.VARROCK_EAST_MINE, GatherTask.Resource.object("Iron rocks"), List.of("pickaxe")));

    // A "Net / Bait" fishing spot: option 1 is Net. NPC option names are not read from the client yet,
    // so the number is the OSRS convention, not a measurement.
    static final List<Method> FISHING = List.of(
            new Method(1, "Fishing (shrimps)", Places.DRAYNOR_FISHING, GatherTask.Resource.npc("Fishing spot", 1), List.of("Small fishing net")));

    /** The tasks for a plan, and any entries it did not understand (in {@code problems}). */
    public static List<Task> parse(String plan, boolean bank, List<String> problems) {
        List<Task> out = new ArrayList<>();
        int[] reached = new int[Skill.values().length];
        for (String raw : plan.split("[,;\\n]")) {
            String e = raw.trim().toLowerCase(Locale.ROOT);
            if (e.isEmpty()) continue;
            String[] p = e.split("\\s+");
            try {
                if (p[0].equals("walk") && p.length > 1) {
                    Place pl = placeNamed(e.substring(5).trim());
                    if (pl == null) problems.add("unknown place: " + e); else out.add(new WalkTask(pl));
                    continue;
                }
                if (p[0].equals("buy") && p.length > 2) {
                    int n = Integer.parseInt(p[1]);
                    String item = raw.trim().split("\\s+", 3)[2];
                    out.add(new BuyTask(item, n, 3));
                    continue;
                }
                Skill s = Skill.valueOf(p[0].toUpperCase(Locale.ROOT));
                int target = Integer.parseInt(p[1]);
                List<Method> methods = methodsFor(s);
                if (methods == null) { problems.add("no training method for " + s.displayName() + " yet"); continue; }
                for (int i = 0; i < methods.size(); i++) {
                    Method m = methods.get(i);
                    int bandEnd = i + 1 < methods.size() ? methods.get(i + 1).fromLevel() : 99;
                    int to = Math.min(target, bandEnd);
                    if (to <= Math.max(m.fromLevel(), reached[s.ordinal()])) continue;
                    out.add(new GatherTask(m.label(), s, to, m.spot(), m.resource(), m.tools(), bank));
                }
                reached[s.ordinal()] = Math.max(reached[s.ordinal()], target);
            } catch (RuntimeException ex) {
                problems.add("did not understand \"" + raw.trim() + "\"");
            }
        }
        return out;
    }

    static List<Method> methodsFor(Skill s) {
        return switch (s) {
            case WOODCUTTING -> WOODCUTTING;
            case MINING -> MINING;
            case FISHING -> FISHING;
            default -> null;
        };
    }

    static Place placeNamed(String name) {
        for (Place p : Places.BANKS) if (p.name().toLowerCase(Locale.ROOT).contains(name)) return p;
        for (Place p : List.of(Places.LUMBRIDGE_TREES, Places.DRAYNOR_OAKS, Places.DRAYNOR_WILLOWS, Places.VARROCK_EAST_MINE, Places.DRAYNOR_FISHING))
            if (p.name().toLowerCase(Locale.ROOT).contains(name)) return p;
        return null;
    }
}
