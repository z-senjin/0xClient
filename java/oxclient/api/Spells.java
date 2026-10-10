package oxclient.api;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Casting a combat spell on an NPC, the way a click does it: select the spell in the spellbook (interface 218)
 * and "use" it on the NPC -- the same selection + ACT_ON_NPC path as using an item, with the spell's
 * component as the selected widget and no slot or item.
 *
 * <p><b>NOT MAPPED YET.</b> Spell buttons are pictures with no text, so their components cannot be found by
 * name; {@link #WIDGETS} is empty until the probe run (cast Wind Strike by hand under gradlew debugRun -Pge)
 * logs the spell's widget and the selection a real cast leaves. Until then {@link #mapped} is false and the
 * account builder does not pick Magic.</p>
 */
public final class Spells {

    private Spells() {}

    /** A free-to-play combat spell for an air staff: the level it needs and the one rune it uses besides air. */
    public record Spell(String name, int level, String rune) {}

    /** Best first. With a staff of air each needs only its own rune. */
    public static final List<Spell> AIR = List.of(new Spell("Wind Blast", 41, "Death rune"),
            new Spell("Wind Bolt", 17, "Chaos rune"), new Spell("Wind Strike", 1, "Mind rune"));

    /** Spell name -> packed widget id of its spellbook button. Empty until mapped (see above). */
    public static final Map<String, Integer> WIDGETS = new LinkedHashMap<>();

    public static boolean mapped() { return !WIDGETS.isEmpty(); }

    /** The best air spell usable at your Magic level, or null. */
    public static Spell best() {
        for (Spell s : AIR) if (Skills.level(Skill.MAGIC) >= s.level() && WIDGETS.containsKey(s.name())) return s;
        return null;
    }

    /** Cast {@code spell} on {@code npc}. False when the spell is not mapped or the action was dropped. */
    public static boolean cast(Spell spell, Entity npc) {
        Integer w = spell == null ? null : WIDGETS.get(spell.name());
        if (w == null || npc == null) return false;
        return oxclient.Natives.useItemOn(8, w, -1, -1, 0, 0, npc.uid());   // 8 = actions.hpp OnNpc
    }
}
