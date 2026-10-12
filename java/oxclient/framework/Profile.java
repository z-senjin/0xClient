package oxclient.framework;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import oxclient.api.Skill;

/**
 * The goal lists the account builder can be given, chosen from a drop-down. For now every profile sets every
 * skill it covers to 99 (account builder plan, decisions); the picker chooses among them at random.
 */
public enum Profile {

    F2P_ALL_99("Free-to-play: every skill to 99", false),
    P2P_ALL_99("Members: every skill to 99 (not built yet)", true);

    /** The free-to-play skills. Hitpoints is left out: it rises with combat and is never trained on its own. */
    public static final Set<Skill> F2P_SKILLS = EnumSet.of(Skill.ATTACK, Skill.STRENGTH, Skill.DEFENCE, Skill.RANGED,
            Skill.MAGIC, Skill.PRAYER, Skill.COOKING, Skill.WOODCUTTING, Skill.FISHING, Skill.FIREMAKING, Skill.CRAFTING,
            Skill.SMITHING, Skill.MINING, Skill.RUNECRAFT);

    /** Members-only skills: placeholders with no logic (account builder plan, Phase 7). */
    public static final Set<Skill> MEMBERS_SKILLS = EnumSet.of(Skill.FLETCHING, Skill.HERBLORE, Skill.AGILITY,
            Skill.THIEVING, Skill.SLAYER, Skill.FARMING, Skill.HUNTER, Skill.CONSTRUCTION, Skill.SAILING);

    private final String label;
    private final boolean members;

    Profile(String label, boolean members) { this.label = label; this.members = members; }

    public boolean members() { return members; }

    public List<Goal> goals() {
        List<Goal> out = new ArrayList<>();
        for (Skill s : F2P_SKILLS) out.add(new SkillGoal(s, 99));
        if (members) for (Skill s : MEMBERS_SKILLS) out.add(new SkillGoal(s, 99));
        return out;
    }

    @Override public String toString() { return label; }
}
