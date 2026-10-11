package oxclient.api;

/** Something standing somewhere in the world: an NPC, a player, a piece of scenery, a ground item. */
public interface Located {

    /** Where it is, in world coordinates (what your minimap shows). */
    WorldPoint location();

    /** Tiles from you, the larger of the x and y gaps -- the way the game measures its own ranges. */
    int distance();
}
