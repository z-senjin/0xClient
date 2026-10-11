package oxclient.framework;

import java.util.List;

import oxclient.api.Equipment;
import oxclient.api.Skill;
import oxclient.api.Skills;

/**
 * A skill to a level. Trained by the best {@link Methods} entry for the skill's level <i>now</i> whose items the
 * account has (or might have: an unseen bank rules nothing out), at a random one of its spots.
 *
 * <p>What a method lacks goes on the shopping list as the method is considered, so the Grand Exchange trip knows
 * what would unlock it -- the bank had no feathers, so trout is out and "Feather" is wanted.</p>
 */
public record SkillGoal(Skill skill, int target) implements Goal {

    /** The skill's name: the key earlier releases saved under, so saved progress still loads. */
    @Override public String key() { return skill.name(); }

    @Override public boolean reached() { return Skills.level(skill) >= target; }

    @Override public String progress() { return "level " + Skills.level(skill); }

    @Override public long experience() { return Skills.experience(skill); }

    @Override
    public String blocker(Context ctx) {
        if (!Methods.has(skill)) return "not built yet";
        if (method(ctx) != null) return "";
        List<String> aside = ctx.lackingLabels();
        return "no usable method at level " + Skills.level(skill) + (aside.isEmpty() ? "" : " (set aside for missing items: " + String.join(", ", aside) + ")");
    }

    @Override
    public Task task(Context ctx) {
        Methods.Method m = method(ctx);
        if (m == null) return null;
        Place spot = m.spots().isEmpty() ? Places.nearestBank() : ctx.spot(m.spots());   // no spots: done at a bank
        if (spot == null) return null;
        ctx.lastLabel = m.label();
        if (m.fight() != null) {
            // weapon upgrades only, for now: armour for every slot would spend the coins before tools and supplies
            for (Loadouts.Piece p : Loadouts.upgrades(m.fight().style()))
                if (p.slot() == Equipment.WEAPON) ctx.wants.addIfAbsent(Need.tool(p.name()), "weapon upgrade");
        }
        int level = Skills.level(skill);
        return m.task(skill, Math.min(target, Methods.nextBand(skill, level)), spot, ctx.bank);
    }

    /** The best method usable now, or null. Puts what the others lack on the shopping list. */
    private Methods.Method method(Context ctx) {
        return Methods.forLevel(skill, Skills.level(skill), m -> usable(m, ctx));
    }

    private static boolean usable(Methods.Method m, Context ctx) {
        if (ctx.lacking(m.label())) return false;
        if (!m.spots().isEmpty() && m.spots().stream().noneMatch(Place::safeNow)) return false;      // too dangerous yet
        if (m.fight() != null && m.fight().needsFood() && ctx.needFood) return false;                // no food: not this fight
        if (m.fight() != null && BankCache.known() && !Loadouts.hasWeapon(m.fight().style())) {      // no weapon for the style
            for (Loadouts.Piece p : Loadouts.upgrades(m.fight().style()))
                if (p.slot() == Equipment.WEAPON) ctx.wants.add(Need.tool(p.name()), m.label());
            return false;
        }
        List<Need> all = Methods.allNeeds(m);
        if (BankCache.couldSupply(all)) return true;
        for (Need n : all) if (!n.met() && !BankCache.couldHave(n)) ctx.wants.add(n, m.label());
        return false;
    }

    @Override public String toString() { return skill.displayName() + " " + target; }
}
