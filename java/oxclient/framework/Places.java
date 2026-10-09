package oxclient.framework;

import java.util.List;

import oxclient.api.Game;
import oxclient.api.Local;

/**
 * The places the account builder knows: banks and training spots.
 *
 * <p><b>These coordinates are approximate</b> -- written from general knowledge of the map, not read
 * from the game, and not yet walked to. Each is the middle of an area, with a radius the task searches
 * within, so a few tiles off does not matter; a spot that is wrong shows up as "no tree within ..." on
 * the panel. Stand there with the Inspector plugin on to read the real tile, and correct it here.</p>
 */
public final class Places {

    private Places() {}

    // ---- banks (the bank booth or chest is searched for within the radius once there)
    public static final Place DRAYNOR_BANK = new Place("Draynor bank", 3092, 3243, 0, 6);
    public static final Place LUMBRIDGE_BANK = new Place("Lumbridge bank (top floor)", 3208, 3220, 2, 6);
    public static final Place VARROCK_WEST_BANK = new Place("Varrock west bank", 3185, 3436, 0, 6);
    public static final Place VARROCK_EAST_BANK = new Place("Varrock east bank", 3253, 3420, 0, 6);
    public static final Place FALADOR_EAST_BANK = new Place("Falador east bank", 3013, 3355, 0, 6);
    public static final Place FALADOR_WEST_BANK = new Place("Falador west bank", 2946, 3368, 0, 6);
    public static final Place AL_KHARID_BANK = new Place("Al Kharid bank", 3269, 3167, 0, 6);
    public static final Place EDGEVILLE_BANK = new Place("Edgeville bank", 3094, 3492, 0, 6);
    public static final Place GRAND_EXCHANGE = new Place("Grand Exchange", 3164, 3487, 0, 8);

    public static final List<Place> BANKS = List.of(DRAYNOR_BANK, LUMBRIDGE_BANK, VARROCK_WEST_BANK, VARROCK_EAST_BANK,
            FALADOR_EAST_BANK, FALADOR_WEST_BANK, AL_KHARID_BANK, EDGEVILLE_BANK, GRAND_EXCHANGE);

    // ---- training spots
    public static final Place LUMBRIDGE_TREES = new Place("Lumbridge trees", 3192, 3224, 0, 12);
    public static final Place DRAYNOR_OAKS = new Place("Draynor oaks", 3103, 3243, 0, 12);
    public static final Place DRAYNOR_WILLOWS = new Place("Draynor willows", 3087, 3235, 0, 10);
    public static final Place VARROCK_EAST_MINE = new Place("Varrock east mine", 3286, 3366, 0, 10);
    public static final Place DRAYNOR_FISHING = new Place("Draynor fishing", 3087, 3228, 0, 10);

    /** The known bank nearest to you (by tile distance; another floor counts as farther than any). */
    public static Place nearestBank() {
        Local me = Game.me();
        Place best = DRAYNOR_BANK;
        long bestD = Long.MAX_VALUE;
        for (Place b : BANKS) {
            long d = Math.max(Math.abs(b.x() - me.worldX()), Math.abs(b.y() - me.worldY())) + (b.plane() != me.plane() ? 40L : 0L);
            if (d < bestD) { bestD = d; best = b; }
        }
        return best;
    }
}
