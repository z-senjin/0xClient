package oxclient.api;

import oxclient.Natives;

/**
 * Who is fighting you, read from each actor's target (ENTITY_TARGET: the uid of whoever it is fighting,
 * verified live on client-241-3 2026-10-09 -- a chicken you attack holds your handle, a goblin attacking you
 * holds it too, and yours holds theirs).
 *
 * <p>Your own target is set by anything you interact with -- an NPC you attack, but also a fishing spot you
 * fish at or someone you talk to -- so "under attack" looks only at NPCs targeting you. An NPC that walks up
 * to talk to you (a random event) targets you as well; for a script that is a reason to move on too.</p>
 */
public final class Combat {

    private Combat() {}

    /** The uid of the NPC you are interacting with (attacking, fishing at, talking to), or -1. */
    public static int target() {
        int[] c = Natives.combat();
        return c.length > 0 ? c[0] : -1;
    }

    /** The uids of the NPCs whose target is you. */
    public static int[] attackers() {
        int[] c = Natives.combat();
        if (c.length <= 1) return new int[0];
        int[] out = new int[c.length - 1];
        System.arraycopy(c, 1, out, 0, out.length);
        return out;
    }

    /**
     * The attackers within {@code tiles} of you. The target field is a plain uid in both tables, so an NPC
     * targeting an NPC whose uid happens to equal your player handle reads as "targeting you". Seen live
     * 2026-10-10: a "Dark wizard" #512 "attacked" at Draynor and Lumbridge Swamp fishing with hitpoints full
     * every time. A real attacker is near you.
     */
    public static int[] attackersNear(int tiles) {
        int[] all = attackers();
        if (all.length == 0) return all;
        java.util.List<Entity> npcs = Npcs.all();
        return java.util.Arrays.stream(all).filter(u -> npcs.stream().anyMatch(e -> e.uid() == u && e.distance() <= tiles && canFight(e))).toArray();
    }

    /**
     * Can this NPC fight at all? One without an "Attack" option cannot -- it targets you because it is talking to
     * you: live 2026-10-10 the toll gate's Border Guard faced the player during the toll talk and a fishing trip
     * "fled" from it three times and gave up. When the options cannot be read at all, assume it can.
     */
    public static boolean canFight(Entity e) {
        String[] ops = e.options();
        boolean any = false;
        for (String o : ops) if (o != null && !o.isEmpty()) { any = true; if (o.equalsIgnoreCase("Attack")) return true; }
        return !any;
    }

    /** True when an NPC is targeting you. */
    public static boolean underAttack() { return Natives.combat().length > 1; }
}
