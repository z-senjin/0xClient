package oxclient.framework;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import oxclient.api.Skill;
import oxclient.api.Spells;

/**
 * How each skill is trained, by level: from what level a method applies, the spots it can be done at, the
 * items it needs, and what kind of task it is -- gathering ({@link GatherTask}), processing
 * ({@link ProcessTask}) or fighting ({@link CombatTask}). A stint picks the best method for the skill's
 * <b>current</b> level whose items the account has, and one of its spots at random.
 *
 * <p>Spot coordinates were read off the Inspector plugin in game by the user (2026-10-09/10), except the
 * training spots in {@link Places}, which are approximate. Skills with no methods here are goals
 * the picker leaves alone ("not built yet"). Magic is offered only once its spells are mapped
 * ({@link Spells#mapped}).</p>
 */
public final class Methods {

    private Methods() {}

    /** What a processing method does: how, with what input (best first), on which objects. */
    public record Process(ProcessTask.Mode mode, Need input, List<String> objects, List<Integer> objectIds) {}

    /** One way to train, from a level, at any of these spots, with all of these items; exactly one kind. */
    public record Method(int fromLevel, String label, List<Place> spots, GatherTask.Resource resource, List<Need> needs,
                         Process process, CombatTask.Fight fight) {
        /** A gathering method. */
        public Method(int fromLevel, String label, List<Place> spots, GatherTask.Resource resource, List<Need> needs) {
            this(fromLevel, label, spots, resource, needs, null, null);
        }
    }

    // ---- spots read in game (user, 2026-10-09)
    static final Place LUMBRIDGE_OAKS = new Place("Lumbridge oaks", 3194, 3236, 0, 8);
    static final Place LUMBRIDGE_WILLOWS = new Place("Lumbridge willows", 3164, 3266, 0, 8);
    static final Place VARROCK_EAST_TREES = new Place("Varrock east trees", 3269, 3474, 0, 10);
    static final Place LUMBRIDGE_SWAMP_MINE = new Place("Lumbridge Swamp mine", 3227, 3146, 0, 8);
    static final Place LUMBRIDGE_SWAMP_FISHING = new Place("Lumbridge Swamp fishing", 3242, 3150, 0, 8);
    static final Place BARBARIAN_VILLAGE_FISHING = new Place("Barbarian Village fishing", 3102, 3425, 0, 10);

    // ---- read in game (user, 2026-10-10)
    static final Place LUMBRIDGE_KITCHEN = new Place("Lumbridge castle kitchen", 3209, 3213, 0, 4);
    static final Place LUMBRIDGE_CHICKENS = new Place("Lumbridge chickens", 3188, 3278, 0, 6);
    static final Place LUMBRIDGE_COWS = new Place("Lumbridge cows", 3173, 3326, 0, 8);
    static final Place BARBARIAN_VILLAGE = new Place("Barbarian Village barbarians", 3082, 3424, 0, 8);

    static final Need AXE = Need.tool(Skill.WOODCUTTING, Need.AXES, Need.TOOL_LEVELS);
    static final Need PICKAXE = Need.tool(Skill.MINING, Need.PICKAXES, Need.TOOL_LEVELS);

    static final Map<Skill, List<Method>> F2P = new EnumMap<>(Skill.class);

    private static Method fight(int from, String label, Place spot, Loadouts.Style style, boolean bury, String... npcs) {
        // food is required only past the easy monsters: chickens and cows cannot really hurt you
        boolean needsFood = !(npcs[0].equals("Chicken") || npcs[0].equals("Cow"));
        return new Method(from, label, List.of(spot), null, List.of(), null, new CombatTask.Fight(style, List.of(npcs), idsOf(npcs[0]), bury, needsFood));
    }

    // NPC ids read in game with the Inspector (user, 2026-10-10)
    static final List<Integer> CHICKEN_IDS = List.of(1173, 1174);
    static final List<Integer> COW_IDS = List.of(2790, 2792, 2793, 2794);
    static final List<Integer> BARBARIAN_IDS = List.of(3058, 3059, 3060, 3066, 3067, 3070, 3072);

    private static List<Integer> idsOf(String npc) {
        return switch (npc) { case "Chicken" -> CHICKEN_IDS; case "Cow" -> COW_IDS; case "Barbarian" -> BARBARIAN_IDS; default -> List.of(); };
    }

    /** Chickens to 10, cows to 20, barbarians after: for one combat style. */
    private static List<Method> fights(String what, Loadouts.Style style) {
        return List.of(fight(1, what + " (chickens)", LUMBRIDGE_CHICKENS, style, false, "Chicken"),
                fight(10, what + " (cows)", LUMBRIDGE_COWS, style, false, "Cow", "Cow calf"),
                fight(20, what + " (barbarians)", BARBARIAN_VILLAGE, style, false, "Barbarian"));
    }

    static {
        F2P.put(Skill.WOODCUTTING, List.of(
                new Method(1, "Woodcutting (trees)", List.of(Places.LUMBRIDGE_TREES, VARROCK_EAST_TREES),
                        GatherTask.Resource.object("Tree"), List.of(AXE)),
                new Method(15, "Woodcutting (oaks)", List.of(Places.DRAYNOR_OAKS, LUMBRIDGE_OAKS, VARROCK_EAST_TREES),
                        GatherTask.Resource.object("Oak tree", "Oak"), List.of(AXE)),
                new Method(30, "Woodcutting (willows)", List.of(Places.DRAYNOR_WILLOWS, LUMBRIDGE_WILLOWS),
                        GatherTask.Resource.object("Willow tree", "Willow"), List.of(AXE))));
        F2P.put(Skill.MINING, List.of(
                new Method(1, "Mining (copper & tin)", List.of(Places.VARROCK_EAST_MINE, LUMBRIDGE_SWAMP_MINE),
                        GatherTask.Resource.object("Copper rocks", "Tin rocks"), List.of(PICKAXE)),
                new Method(15, "Mining (iron)", List.of(Places.VARROCK_EAST_MINE),
                        GatherTask.Resource.object("Iron rocks"), List.of(PICKAXE))));
        // Fishing spots are NPCs; the option is taken by name (NPCDEF_OPS), with the number as a fallback.
        // Net = 1 on a "Fishing spot" was seen working (shrimps at Draynor). "Rod Fishing spot" with Lure is
        // the OSRS naming -- NOT SEEN WORKING YET (the user checks the name in game).
        F2P.put(Skill.FISHING, List.of(
                new Method(1, "Fishing (shrimps)", List.of(Places.DRAYNOR_FISHING, LUMBRIDGE_SWAMP_FISHING),
                        GatherTask.Resource.npc("Fishing spot", "Net", 1), List.of(Need.tool("Small fishing net"))),
                new Method(20, "Fishing (trout & salmon)", List.of(BARBARIAN_VILLAGE_FISHING),
                        GatherTask.Resource.npc("Rod Fishing spot", "Lure", 1).withIds(1526),     // id: user, 2026-10-10
                        List.of(Need.tool("Fly fishing rod"), Need.supply("Feather")))));
        F2P.put(Skill.COOKING, List.of(new Method(1, "Cooking (fish)", List.of(LUMBRIDGE_KITCHEN), null, List.of(),
                new Process(ProcessTask.Mode.USE_ON_OBJECT,
                        Need.supply(Skill.COOKING, List.of("Raw salmon", "Raw trout", "Raw anchovies", "Raw shrimps"), List.of(25, 15, 1, 1)),
                        List.of("Range", "Cooking range"), List.of(114)), null)));   // range id 114: user, 2026-10-10
        // Forester's Campfire (the user's choice): just outside whichever bank is nearest -- no fixed spot.
        F2P.put(Skill.FIREMAKING, List.of(new Method(1, "Firemaking (campfire)", List.of(), null, List.of(Need.tool("Tinderbox")),
                new Process(ProcessTask.Mode.CAMPFIRE,
                        Need.supply(Skill.FIREMAKING, List.of("Willow logs", "Oak logs", "Logs"), List.of(30, 15, 1)), List.of(), List.of()), null)));
        F2P.put(Skill.ATTACK, fights("Attack", Loadouts.Style.MELEE));
        F2P.put(Skill.STRENGTH, fights("Strength", Loadouts.Style.MELEE));
        F2P.put(Skill.DEFENCE, fights("Defence", Loadouts.Style.MELEE));
        F2P.put(Skill.RANGED, fights("Ranged", Loadouts.Style.RANGED));
        F2P.put(Skill.MAGIC, fights("Magic", Loadouts.Style.MAGIC));
        F2P.put(Skill.PRAYER, List.of(fight(1, "Prayer (cow bones)", LUMBRIDGE_COWS, Loadouts.Style.MELEE, true, "Cow", "Cow calf")));
    }

    /** True when the skill has any training method yet (Magic: once its spells are mapped). */
    public static boolean has(Skill s) {
        if (s == Skill.MAGIC && !Spells.mapped()) return false;
        return F2P.containsKey(s);
    }

    /**
     * The best method for this level that {@code usable} allows (the picker leaves out methods whose items
     * the bank turned out not to have), or null when there is none.
     */
    public static Method forLevel(Skill s, int level, Predicate<Method> usable) {
        if (!has(s)) return null;
        Method best = null;
        for (Method m : F2P.get(s)) if (m.fromLevel() <= level && usable.test(m)) best = m;
        return best;
    }

    /** The level at which the next method takes over (so a task stops there and the next one is chosen), or 99. */
    public static int nextBand(Skill s, int level) {
        List<Method> ms = F2P.get(s);
        if (ms != null) for (Method m : ms) if (m.fromLevel() > level) return m.fromLevel();
        return 99;
    }

    /** Everything a method needs before it can start, for the bank check: its items, a process's input. */
    static List<Need> allNeeds(Method m) {
        if (m.process() == null) return m.needs();
        List<Need> out = new java.util.ArrayList<>(m.needs());
        out.add(m.process().input());
        return out;
    }
}
