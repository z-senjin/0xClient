package oxclient.framework;

import java.util.List;

import oxclient.api.Game;
import oxclient.api.Local;
import oxclient.api.Walker;

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

    /**
     * Combat level a character needs for the places marked with it (fromCombat), where aggressive monsters
     * attack low levels -- Draynor (dark wizards, jail guards) and Varrock east mine (user, 2026-10-11: "they
     * get attacked too much"; a run on 2026-10-10 died to a Dark wizard at Draynor bank). Under it the picker
     * skips those spots (and a method with no other spot), and every bank choice skips Draynor bank.
     */
    static final int DANGER_COMBAT = 29;

    // ---- banks (the bank booth or chest is searched for within the radius once there)
    public static final Place DRAYNOR_BANK = new Place("Draynor bank", 3092, 3243, 0, 6).fromCombat(DANGER_COMBAT);
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
    public static final Place DRAYNOR_OAKS = new Place("Draynor oaks", 3103, 3243, 0, 12).fromCombat(DANGER_COMBAT);
    public static final Place DRAYNOR_WILLOWS = new Place("Draynor willows", 3087, 3235, 0, 10).fromCombat(DANGER_COMBAT);
    public static final Place VARROCK_EAST_MINE = new Place("Varrock east mine", 3286, 3366, 0, 10).fromCombat(DANGER_COMBAT);
    public static final Place DRAYNOR_FISHING = new Place("Draynor fishing", 3087, 3228, 0, 10).fromCombat(DANGER_COMBAT);

    // ---- read in game with the Inspector by the user (2026-10-09/10). Every place lives in this one table;
    // Methods only says which are used for what. SpotCheck routes all of them offline.
    public static final Place LUMBRIDGE_OAKS = new Place("Lumbridge oaks", 3194, 3236, 0, 8);
    public static final Place LUMBRIDGE_WILLOWS = new Place("Lumbridge willows", 3164, 3266, 0, 8);
    public static final Place VARROCK_EAST_TREES = new Place("Varrock east trees", 3269, 3474, 0, 10);
    public static final Place LUMBRIDGE_SWAMP_MINE = new Place("Lumbridge Swamp mine", 3227, 3146, 0, 8);
    public static final Place LUMBRIDGE_SWAMP_FISHING = new Place("Lumbridge Swamp fishing", 3242, 3150, 0, 8);
    public static final Place BARBARIAN_VILLAGE_FISHING = new Place("Barbarian Village fishing", 3102, 3425, 0, 10);
    /** Needs Cook's Assistant (user, 2026-10-10): not used until the quest is done. */
    public static final Place LUMBRIDGE_KITCHEN = new Place("Lumbridge castle kitchen", 3209, 3213, 0, 4);
    /** Past the Al Kharid toll gate: a trip there takes coins (BankTrip). */
    public static final Place AL_KHARID_RANGE = new Place("Al Kharid range", 3272, 3180, 0, 4);
    public static final Place LUMBRIDGE_CHICKENS = new Place("Lumbridge chickens", 3188, 3278, 0, 6);
    public static final Place LUMBRIDGE_COWS = new Place("Lumbridge cows", 3173, 3326, 0, 8);
    public static final Place BARBARIAN_VILLAGE = new Place("Barbarian Village barbarians", 3082, 3424, 0, 8);
    /** Iron rocks by Citharede Abbey, past the Al Kharid toll gate (user, 2026-10-11): trips there take coins
     *  (BankTrip); nothing attacks there, so no combat minimum. */
    public static final Place CITHAREDE_ABBEY_MINE = new Place("Citharede Abbey mine", 3402, 3169, 0, 8);

    /** The banks the character may use now: those its combat level is high enough for (none left = all). */
    public static List<Place> banksNow() {
        List<Place> ok = BANKS.stream().filter(Place::safeNow).toList();
        return ok.isEmpty() ? BANKS : ok;
    }

    /**
     * The known bank nearest to you BY ROUTE: the four closest as the crow flies are each planned with the
     * walker, and the shortest walkable one wins. A bank behind something you cannot pass (the Al Kharid toll
     * gate) has no route and is skipped -- choosing by straight distance sent a character there, live
     * 2026-10-09. Falls back to straight distance while the walking map is still loading.
     */
    public static Place nearestBank() {
        Local me = Game.me();
        List<Place> byDistance = new java.util.ArrayList<>(banksNow());
        byDistance.sort(java.util.Comparator.comparingLong(b ->
                Math.max(Math.abs(b.x() - me.worldX()), Math.abs(b.y() - me.worldY())) + (b.plane() != me.plane() ? 40L : 0L)));
        if (!Walker.ready()) return byDistance.get(0);
        Place best = null;
        int bestLen = Integer.MAX_VALUE;
        for (int i = 0; i < 4 && i < byDistance.size(); i++) {
            Place b = byDistance.get(i);
            int len = Walker.routeLength(b.x(), b.y(), b.plane(), 3);
            if (len >= 0 && len < bestLen) { bestLen = len; best = b; }
        }
        return best != null ? best : byDistance.get(0);
    }

    /**
     * The bank that makes the shortest round from you, through the bank, to {@code spot} -- the bank to use for
     * a supply trip before training there. Seen live 2026-10-10: a Barbarian Village fishing stint picked from
     * the Lumbridge cows went to Draynor bank (nearest to the cows), far the wrong way. A null spot means the
     * nearest bank.
     */
    public static Place bankOnTheWay(Place spot) {
        if (spot == null || !Walker.ready()) return nearestBank();
        Local me = Game.me();
        java.util.LinkedHashSet<Place> pool = new java.util.LinkedHashSet<>();
        List<Place> l = new java.util.ArrayList<>(banksNow());
        l.sort(java.util.Comparator.comparingLong(b -> Math.max(Math.abs(b.x() - me.worldX()), Math.abs(b.y() - me.worldY()))));
        pool.addAll(l.subList(0, Math.min(3, l.size())));
        l.sort(java.util.Comparator.comparingLong(b -> Math.max(Math.abs(b.x() - spot.x()), Math.abs(b.y() - spot.y()))));
        pool.addAll(l.subList(0, Math.min(3, l.size())));
        Place best = null;
        long bestLen = Long.MAX_VALUE;
        for (Place b : pool) {
            int there = Walker.routeLength(b.x(), b.y(), b.plane(), 3);
            if (there < 0) continue;
            // from the bank on, the coins for the toll gate will be carried (the trip takes them: BankTrip)
            List<Walker.Step> on = Walker.findPath(b.x(), b.y(), b.plane(), spot.x(), spot.y(), spot.plane(), spot.radius(), true);
            if (on.isEmpty()) continue;
            long len = (long) there + on.size();
            if (len < bestLen) { bestLen = len; best = b; }
        }
        return best != null ? best : nearestBank();
    }
}
