package oxclient.framework;

import oxclient.api.Skill;
import oxclient.api.Skills;

/**
 * Something the account builder works towards: a skill to a level. (Quests join in Phase 6.)
 *
 * @param skill   the skill
 * @param target  the level it is done at
 */
public record Goal(Skill skill, int target) {

    /** Identifies the goal in the save file and the panel. */
    public String key() { return skill.name(); }

    public boolean reached() { return Skills.level(skill) >= target; }

    @Override public String toString() { return skill.displayName() + " " + target; }
}
