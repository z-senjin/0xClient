package oxclient.api;

/**
 * The combat tab's attack-style buttons (interface 593). Style {@code n} (0..3) is the n-th button, top-left
 * first: for a scimitar Chop (Attack), Slash (Strength), Lunge (shared), Block (Defence); for a shortbow
 * Accurate, Rapid, Longrange (Ranged + Defence).
 *
 * <p><b>NOT VERIFIED:</b> the button components (4, 8, 12, 16) are the numbering other OSRS clients use for
 * this interface, not read from this one; the probe run (gradlew debugRun -Pge, clicking each style) logs the
 * real widget of each click. Clicking an already-selected style does nothing, so selecting every stint is
 * harmless.</p>
 */
public final class CombatStyle {

    private CombatStyle() {}

    public static final int GROUP = 593;

    /** The button components, style 0..3 (unverified, see above). */
    public static volatile int[] BUTTONS = {4, 8, 12, 16};

    /** Style 0 trains Attack, 1 Strength, 3 Defence (melee); 1 is Rapid, 2 Longrange (ranged). */
    public static boolean select(int style) {
        if (style < 0 || style >= BUTTONS.length) return false;
        return Interfaces.click((GROUP << 16) | BUTTONS[style], -1, 1);
    }

    /** The style that trains a melee skill: Attack 0, Strength 1, Defence 3. */
    public static int forSkill(Skill s) {
        return switch (s) { case STRENGTH -> 1; case DEFENCE -> 3; default -> 0; };
    }
}
