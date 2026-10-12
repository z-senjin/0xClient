package oxclient.framework;

import oxclient.api.Skill;

/**
 * Something the account builder works towards: a skill to a level ({@link SkillGoal}), a quest
 * ({@link QuestGoal}) -- and whatever comes next (a minigame, an achievement), by implementing this.
 *
 * <p>The {@link Picker} only ever asks three things of a goal: is it {@link #reached}; if not, can it be
 * worked on right now ({@link #blocker} is "" when it can, else the reason, for the panel); and then the
 * {@link #task} that works on it. A goal that cannot run now is simply not picked -- no stint is spent on
 * finding that out -- and comes back the moment its blocker clears (a level gained, an item banked).</p>
 */
public interface Goal {

    /** Identifies the goal in the save file; stable across releases. */
    String key();

    /** True once there is nothing left to do. */
    boolean reached();

    /**
     * "" when it can be worked on now; otherwise why not ("needs Egg", "Mining 15 first"). Cheap: it may look at
     * levels, the inventory and the remembered bank, never walk. It may put what is missing on the shopping list.
     */
    String blocker(Context ctx);

    /** The task that works on it now; only asked when {@link #blocker} said "". Null means "not after all". */
    Task task(Context ctx);

    /** Where it stands, for the panel: "level 23", "started". */
    String progress();

    /** Total experience it counts, for experience per hour in the history; -1 when it is not a skill. */
    default long experience() { return -1; }

    /** The skill being trained, or null when this goal is not a skill goal. */
    default Skill skill() { return null; }

    /** The target level, or 0 when this goal is not a skill goal. */
    default int target() { return 0; }
}
