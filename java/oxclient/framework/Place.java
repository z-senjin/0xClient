package oxclient.framework;

import oxclient.api.Skills;

/**
 * A named spot in the world: a tile, its floor, how close counts as "there", and the combat level a character
 * needs before it trains there ({@code minCombat}, 0 = anyone).
 */
public record Place(String name, int x, int y, int plane, int radius, int minCombat) {

    public Place(String name, int x, int y, int plane, int radius) { this(name, x, y, plane, radius, 0); }

    /** The same place, used only from this combat level up (aggressive monsters nearby). */
    public Place fromCombat(int level) { return new Place(name, x, y, plane, radius, level); }

    /** Can the character train here now (its combat level is high enough)? */
    public boolean safeNow() { return minCombat <= 0 || Skills.combatLevel() >= minCombat; }

    /** Tile distance from (x, y) on the same floor; MAX_VALUE on another floor. */
    public int distance(int wx, int wy, int wplane) {
        return wplane != plane ? Integer.MAX_VALUE : Math.max(Math.abs(wx - x), Math.abs(wy - y));
    }
}
