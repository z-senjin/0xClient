package oxclient.api;

import oxclient.Natives;

/**
 * What you are wearing. Slots are numbered as the game numbers them (an item definition's slot,
 * {@link Items#slot}, and the index into the worn-items container, 94).
 */
public final class Equipment {

    private Equipment() {}

    public static final int CONTAINER = 94;

    public static final int HEAD = 0, CAPE = 1, NECK = 2, WEAPON = 3, BODY = 4, SHIELD = 5, LEGS = 7, HANDS = 9,
            FEET = 10, RING = 12, AMMO = 13;

    /** The item id worn in a slot, or -1. */
    public static int worn(int slot) {
        int[] f = Natives.container(CONTAINER);
        int i = slot * 2;
        return i + 1 < f.length && f[i] >= 0 && f[i + 1] > 0 ? f[i] : -1;
    }

    /** How many are worn in a slot (arrows), 0 when empty. */
    public static int amount(int slot) {
        int[] f = Natives.container(CONTAINER);
        int i = slot * 2;
        return i + 1 < f.length && f[i] >= 0 ? Math.max(0, f[i + 1]) : 0;
    }

    /** The name of what is worn in a slot, or "". */
    public static String name(int slot) { int id = worn(slot); return id < 0 ? "" : Items.name(id); }
}
