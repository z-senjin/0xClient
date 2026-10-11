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

    /**
     * A monster to train on: its names, its ids (read in game with the Inspector by the user, 2026-10-10), and
     * whether a fight with it needs food (chickens and cows cannot really hurt you).
     */
    public record Monster(List<String> names, List<Integer> ids, boolean needsFood) {}

    static final Monster CHICKENS = new Monster(List.of("Chicken"), List.of(1173, 1174), false);
    static final Monster COWS = new Monster(List.of("Cow", "Cow calf"), List.of(2790, 2792, 2793, 2794), false);
    static final Monster BARBARIANS = new Monster(List.of("Barbarian"), List.of(3058, 3059, 3060, 3066, 3067, 3070, 3072), true);

    /**
     * One way to train, from a level, at any of these spots, with all of these items; exactly one of resource,
     * process and fight is set. Built with {@link #gather}, {@link #process} or {@link #fight}.
     */
    public record Method(int fromLevel, String label, List<Place> spots, GatherTask.Resource resource, List<Need> needs,
                         Process process, CombatTask.Fight fight) {

        /** The task that trains {@code skill} to {@code target} this way at {@code spot}. */
        public Task task(Skill skill, int target, Place spot, boolean bank) {
            if (fight != null) return new CombatTask(label, skill, target, spot, fight);
            if (process != null) return new ProcessTask(label, skill, target, spot, process.mode(), needs, process.input(), process.objects(), process.objectIds());
            return new GatherTask(label, skill, target, spot, resource, needs, bank);
        }
    }

    /** Gathering: click a resource (an object or an NPC) at one of the spots. */
    static Method gather(int from, String label, List<Place> spots, GatherTask.Resource resource, Need... needs) {
        return new Method(from, label, spots, resource, List.of(needs), null, null);
    }

    /** Processing: use the input on an object, light it, or feed a campfire. No spots: at the nearest bank. */
    static Method process(int from, String label, List<Place> spots, List<Need> tools, ProcessTask.Mode mode, Need input, List<String> objects, List<Integer> ids) {
        return new Method(from, label, spots, null, tools, new Process(mode, input, objects, ids), null);
    }

    /** Fighting a monster at a spot (bury: bury the bones, for Prayer). */
    static Method fight(int from, String label, Place spot, Loadouts.Style style, boolean bury, Monster m) {
        return new Method(from, label, List.of(spot), null, List.of(), null, new CombatTask.Fight(style, m.names(), m.ids(), bury, m.needsFood()));
    }

    static final Need AXE = Need.tool(Skill.WOODCUTTING, Need.AXES, Need.TOOL_LEVELS);
    static final Need PICKAXE = Need.tool(Skill.MINING, Need.PICKAXES, Need.TOOL_LEVELS);

    static final Map<Skill, List<Method>> F2P = new EnumMap<>(Skill.class);

    /** Chickens to 10, cows to 20, barbarians after: for one combat style. */
    private static List<Method> fights(String what, Loadouts.Style style) {
        return List.of(fight(1, what + " (chickens)", Places.LUMBRIDGE_CHICKENS, style, false, CHICKENS),
                fight(10, what + " (cows)", Places.LUMBRIDGE_COWS, style, false, COWS),
                fight(20, what + " (barbarians)", Places.BARBARIAN_VILLAGE, style, false, BARBARIANS));
    }

    static {
        F2P.put(Skill.WOODCUTTING, List.of(
                gather(1, "Woodcutting (trees)", List.of(Places.LUMBRIDGE_TREES, Places.VARROCK_EAST_TREES), GatherTask.Resource.object("Tree"), AXE),
                gather(15, "Woodcutting (oaks)", List.of(Places.DRAYNOR_OAKS, Places.LUMBRIDGE_OAKS, Places.VARROCK_EAST_TREES),
                        GatherTask.Resource.object("Oak tree", "Oak"), AXE),
                gather(30, "Woodcutting (willows)", List.of(Places.DRAYNOR_WILLOWS, Places.LUMBRIDGE_WILLOWS),
                        GatherTask.Resource.object("Willow tree", "Willow"), AXE)));
        F2P.put(Skill.MINING, List.of(
                gather(1, "Mining (copper & tin)", List.of(Places.VARROCK_EAST_MINE, Places.LUMBRIDGE_SWAMP_MINE),
                        GatherTask.Resource.object("Copper rocks", "Tin rocks"), PICKAXE),
                gather(15, "Mining (iron)", List.of(Places.VARROCK_EAST_MINE, Places.CITHAREDE_ABBEY_MINE),
                        GatherTask.Resource.object("Iron rocks"), PICKAXE)));
        // Fishing spots are NPCs; the option is taken by name (NPCDEF_OPS), with the number as a fallback.
        // Net = 1 on a "Fishing spot" was seen working (shrimps at Draynor). "Rod Fishing spot" with Lure is
        // the OSRS naming -- NOT SEEN WORKING YET (the user checks the name in game).
        F2P.put(Skill.FISHING, List.of(
                gather(1, "Fishing (shrimps)", List.of(Places.DRAYNOR_FISHING, Places.LUMBRIDGE_SWAMP_FISHING),
                        GatherTask.Resource.npc("Fishing spot", "Net", 1), Need.tool("Small fishing net")),
                gather(20, "Fishing (trout & salmon)", List.of(Places.BARBARIAN_VILLAGE_FISHING),
                        GatherTask.Resource.npc("Rod Fishing spot", "Lure", 1).withIds(1526),     // id: user, 2026-10-10
                        Need.tool("Fly fishing rod"), Need.supply("Feather"))));
        // the range is found by name (the Lumbridge one was id 114, and needs Cook's Assistant)
        F2P.put(Skill.COOKING, List.of(process(1, "Cooking (fish)", List.of(Places.AL_KHARID_RANGE), List.of(), ProcessTask.Mode.USE_ON_OBJECT,
                Need.supply(Skill.COOKING, List.of("Raw salmon", "Raw trout", "Raw anchovies", "Raw shrimps"), List.of(25, 15, 1, 1)),
                List.of("Range", "Cooking range"), List.of())));
        // Forester's Campfire (the user's choice): just outside whichever bank is nearest -- no fixed spot.
        F2P.put(Skill.FIREMAKING, List.of(process(1, "Firemaking (campfire)", List.of(), List.of(Need.tool("Tinderbox")), ProcessTask.Mode.CAMPFIRE,
                Need.supply(Skill.FIREMAKING, List.of("Willow logs", "Oak logs", "Logs"), List.of(30, 15, 1)), List.of(), List.of())));
        F2P.put(Skill.ATTACK, fights("Attack", Loadouts.Style.MELEE));
        F2P.put(Skill.STRENGTH, fights("Strength", Loadouts.Style.MELEE));
        F2P.put(Skill.DEFENCE, fights("Defence", Loadouts.Style.MELEE));
        F2P.put(Skill.RANGED, fights("Ranged", Loadouts.Style.RANGED));
        F2P.put(Skill.MAGIC, fights("Magic", Loadouts.Style.MAGIC));
        F2P.put(Skill.PRAYER, List.of(fight(1, "Prayer (cow bones)", Places.LUMBRIDGE_COWS, Loadouts.Style.MELEE, true, COWS)));
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
