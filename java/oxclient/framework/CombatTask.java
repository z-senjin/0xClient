package oxclient.framework;

import java.util.ArrayList;
import java.util.List;

import oxclient.api.Actions;
import oxclient.api.Combat;
import oxclient.api.CombatStyle;
import oxclient.api.Dialog;
import oxclient.api.Entity;
import oxclient.api.Equipment;
import oxclient.api.Game;
import oxclient.api.GroundItems;
import oxclient.api.Inventory;
import oxclient.api.Local;
import oxclient.api.Npcs;
import oxclient.api.Skill;
import oxclient.api.Skills;
import oxclient.api.Spells;
import oxclient.api.Walker;

/**
 * Train a combat skill (or Prayer, by burying bones) to a level on NPCs at a spot.
 *
 * <p>The loop: put on the best owned gear for the style ({@link EquipTask}); fetch food (topped up to
 * {@link #FOOD}) and, for magic, the spell's runes; walk to the spot; select the attack style that trains the
 * skill; then fight -- attack an NPC by name that has an "Attack" option and is not fighting someone else
 * (one already attacking you first), eat below half health, pick up bones and anything on the loot list
 * when not fighting, bury bones. Out of food and below a third of health it goes back to the bank. Gear that
 * vanished from your hands (a death) sends it back to the equip step.</p>
 *
 * <p><b>NOT SEEN WORKING YET.</b> Attacking (NPC option by name), looting (ground option "Take") and the
 * target choice stand on parts that were seen working; eating, burying, wielding (inventory options by
 * number), the attack-style buttons and spells are not mapped live yet -- see the plan's test list.</p>
 */
public final class CombatTask extends BaseTask implements Spotted {

    /** Food carried into a fight, topped up at every bank visit. */
    static final int FOOD = 10;

    /** Cooked F2P food, best first. */
    static final List<String> FOODS = List.of("Swordfish", "Lobster", "Tuna", "Bass", "Salmon", "Trout", "Pike", "Cod",
            "Herring", "Mackerel", "Sardine", "Bread", "Anchovies", "Shrimps");

    /** Picked up when nothing is fighting you: bones (buried) and things that sell. */
    static final List<String> LOOT = List.of("Bones", "Big bones", "Feather", "Cowhide", "Coins");

    /**
     * What a fight method is: the style, the NPCs (by name and by id, read in game), whether to bury bones, and
     * whether food is required (chickens and cows hit too softly to need it; food is still eaten if carried).
     */
    public record Fight(Loadouts.Style style, List<String> npcs, List<Integer> ids, boolean bury, boolean needsFood) {
        boolean matches(Entity e) { return ids.contains(e.id()) || npcs.contains(e.name()); }
    }

    private enum Phase { GEAR, SUPPLY, WALK, STYLE, FIGHT, APPROACH }

    private final String label;
    private final Skill skill;
    private final int target;
    private final Place spot;
    private final Fight fight;
    private final Need food = Eater.FOOD;
    private final Eater eater = new Eater();

    private Phase phase = Phase.GEAR;
    private EquipTask equip;
    private BankTrip trip;
    private Walker.Route route, approach;
    private long approachUntil;
    private int approachUid = -1;
    /** Stalled attacks per NPC uid, and NPCs left alone until a time (unreachable: behind a fence or gate). */
    private final java.util.Map<Integer, Integer> stalls = new java.util.HashMap<>();
    private final java.util.Map<Integer, Long> unreachable = new java.util.HashMap<>();
    private long attackedAt, fightSince, lastFightLog;
    private int fightXp = -1;
    private int retreats, tollTrips;
    private boolean missingSupplies;
    private Need missingItem;
    private String weaponName = "";
    private final java.util.Map<String, Integer> lootTries = new java.util.HashMap<>();

    public CombatTask(String label, Skill skill, int target, Place spot, Fight fight) {
        this.label = label;
        this.skill = skill;
        this.target = target;
        this.spot = spot;
        this.fight = fight;
    }

    @Override public String name() { return label + " to " + target + " at " + spot.name(); }

    @Override public void start() { phase = Phase.GEAR; equip = null; trip = null; route = null; retreats = 0; }

    @Override public Place spot() { return spot; }

    @Override public boolean missingSupplies() { return missingSupplies; }

    @Override public Need missingItem() { return missingItem; }

    /** The supplies besides food: the spell's rune for magic. */
    private List<Need> supplies() {
        List<Need> out = new ArrayList<>();
        out.add(food);
        if (fight.style() == Loadouts.Style.MAGIC) {
            Spells.Spell s = Spells.best();
            if (s != null) out.add(Need.supply(s.rune()));
        }
        return out;
    }

    private static boolean bankHasFood() {
        for (String f : FOODS) if (BankCache.count(f) > 0) return true;
        return false;
    }

    /** Is this the combat food need (so the picker can make food when it is missing)? */
    public static boolean isFood(Need n) { return n != null && n.anyOf().equals(FOODS); }

    /** Cooked food the bank holds, all kinds together. */
    public static int bankFood() {
        int n = 0;
        for (String f : FOODS) n += BankCache.count(f);
        return n;
    }

    /** For a fight that does not need food: only the spell's rune (food is taken only if the bank has some). */
    private List<Need> optionalSupplies() {
        List<Need> out = new ArrayList<>(supplies());
        out.remove(food);
        if (BankCache.couldHave(food) && BankCache.known()) {
            for (String f : FOODS) if (BankCache.count(f) > 0) { out.add(food); break; }
        }
        return out;
    }

    /** Gear names and supply names: what a bank trip from here keeps in the inventory. */
    private List<String> keep() {
        List<String> k = new ArrayList<>();
        for (Loadouts.Piece p : Loadouts.bestOwned(fight.style())) k.add(p.name());
        k.addAll(BankTrip.names(supplies()));
        return k;
    }

    private int hpPercent(Local me) { return Eater.hpPercent(me); }

    @Override
    protected Result step(long now) {
        Local me = Game.me();
        if (Skills.level(skill) >= target) return Result.DONE;
        if (Dialog.canContinue()) { state("continuing a message"); Dialog.continueDialog(); sleep(now, 600, 1000); return Result.CONTINUE; }

        // eat whenever below half, whatever the phase (Eater: not with the bank open, gives up on an Eat that does nothing)
        int hp = hpPercent(me);
        if (eater.eat(me)) { state("eating"); sleep(now, 1200, 1700); return Result.CONTINUE; }
        boolean noFood = !eater.hasFood();
        if (hp < 33 && noFood && phase == Phase.FIGHT) {
            if (eater.broken()) return fail("eating does nothing (check the inventory Eat option)");
            // the bank has no food either: say so, and the picker makes food (fishing, then cooking) before
            // fighting again -- the user's choice, 2026-10-10. Live that day it walked to Draynor bank, found no
            // shrimps, then did the same at the chickens and parked Attack.
            if (BankCache.known() && !bankHasFood()) {
                missingSupplies = true;
                missingItem = food;
                return fail("out of food at " + spot.name() + " (hp " + me.health() + "/" + me.maxHealth() + ")");
            }
            if (++retreats > 3) return fail("ran out of food " + retreats + " times at " + spot.name());
            trip = new BankTrip(keep(), supplies()).toward(spot);
            phase = Phase.SUPPLY;
        }

        switch (phase) {
            case GEAR -> {
                if (!Loadouts.hasWeapon(fight.style()) && !BankCache.known()) {
                    // never seen the bank: look in it once (keeping everything carried) before deciding
                    if (trip == null) {
                        List<String> carried = new ArrayList<>();
                        for (int id : Inventory.ids()) if (id >= 0) carried.add(oxclient.api.Items.name(id));
                        trip = new BankTrip(carried, List.of());
                    }
                    state("looking in the bank: " + trip.state());
                    Result r = trip.tick(now);
                    if (r == Result.FAILED) return fail("bank: " + trip.failure());
                    if (r == Result.DONE) trip = null;
                    return Result.CONTINUE;
                }
                if (fight.style() == Loadouts.Style.RANGED && Loadouts.hasWeapon(fight.style()) && Loadouts.arrows() == null) {
                    missingSupplies = true;                       // a bow without arrows cannot fight (audit 2026-10-10)
                    missingItem = Need.supply("Bronze arrow");
                    return fail("no arrows owned");
                }
                if (!Loadouts.hasWeapon(fight.style())) {
                    List<Loadouts.Piece> up = Loadouts.upgrades(fight.style());
                    for (Loadouts.Piece p : up) if (p.slot() == Equipment.WEAPON) { missingSupplies = true; missingItem = Need.tool(p.name()); }
                    return fail("no " + fight.style().name().toLowerCase() + " weapon owned");
                }
                if (equip == null) { equip = new EquipTask(fight.style()); equip.start(); }
                state(equip.state());
                Result r = equip.tick(now);
                if (r == Result.FAILED) return fail(equip.failure());
                if (r == Result.DONE) { weaponName = Equipment.name(Equipment.WEAPON); equip = null; phase = Phase.SUPPLY; }
            }
            case SUPPLY -> {
                boolean ok = !fight.needsFood() || food.carried() >= 3;
                for (Need n : supplies()) if (n != food && !n.met()) ok = false;
                if (ok && trip == null) { phase = Phase.WALK; route = null; return Result.CONTINUE; }
                if (trip == null) trip = new BankTrip(keep(), fight.needsFood() ? supplies() : optionalSupplies()).toward(spot);
                state("supplies: " + trip.state());
                Result r = trip.tick(now);
                if (r == Result.FAILED) {
                    missingSupplies = trip.missingNeed() != null;
                    missingItem = trip.missingNeed();
                    return fail("supplies: " + trip.failure());
                }
                if (r == Result.DONE) { trip = null; phase = Phase.WALK; route = null; }
            }
            case WALK -> {
                // to the spot's own tile, not just near it: the spots are inside pens (the chicken coop, the cow
                // field) and "near" can be the wrong side of the fence -- live 2026-10-10 it stopped at 3189,3281,
                // outside the coop, and every chicken it clicked was unreachable. The walker opens the gate.
                if (spot.distance(me.worldX(), me.worldY(), me.plane()) <= 1) { phase = Phase.STYLE; return Result.CONTINUE; }
                if (route == null) route = new Walker.Route(spot.x(), spot.y(), spot.plane(), 1);
                Walker.Status st = route.step();
                if (st == Walker.Status.ARRIVED) { phase = Phase.STYLE; return Result.CONTINUE; }
                state("walking to " + spot.name());
                if (st == Walker.Status.NEED_TOLL) {                      // coins for the toll gate: a bank trip takes them
                    if (++tollTrips > 2) return fail("no coins for the toll gate on the way to " + spot.name());
                    trip = new BankTrip(keep(), fight.needsFood() ? supplies() : optionalSupplies()).toward(spot);
                    phase = Phase.SUPPLY;
                    route = null;
                    return Result.CONTINUE;
                }
                if (st == Walker.Status.NO_ROUTE) return fail("no route to " + spot.name());
                if (st == Walker.Status.STUCK) return fail("stuck on the way to " + spot.name());
            }
            case STYLE -> {
                int style = switch (fight.style()) {
                    case RANGED -> skill == Skill.DEFENCE ? 2 : 1;          // longrange trains defence, rapid ranged
                    case MAGIC -> 0;
                    default -> CombatStyle.forSkill(skill);
                };
                if (fight.style() != Loadouts.Style.MAGIC) CombatStyle.select(style);
                state("attack style " + style);
                phase = Phase.FIGHT;
                sleep(now, 600, 900);
            }
            case FIGHT -> { return fight(me, now); }
            case APPROACH -> {
                // walk up to an NPC the attack could not reach (the walker opens gates and doors on the way), then
                // attack it again
                Entity t = null;
                for (Entity e : Npcs.all()) if (e.uid() == approachUid) t = e;
                Walker.Status st = t == null ? Walker.Status.ARRIVED : approach.step();
                state("walking up to " + (t == null ? "the target" : t.name()));
                if (st == Walker.Status.WALKING || st == Walker.Status.LOADING) { if (now < approachUntil) return Result.CONTINUE; }
                phase = Phase.FIGHT;
                approach = null;
                if (t != null && st != Walker.Status.NO_ROUTE && st != Walker.Status.STUCK) {
                    state("attacking " + t.name());
                    if (!Actions.npc(t, "Attack")) Actions.npc(t, 2);
                    attackedAt = now;
                    sleep(now, 1200, 1800);
                } else if (t != null) {
                    unreachable.put(t.uid(), now + 60_000);
                    System.out.println("[builder] cannot get to " + t.name() + " uid " + t.uid() + " at " + t.worldX() + "," + t.worldY() + " (" + st + "): leaving it 60 s");
                }
            }
        }
        return Result.CONTINUE;
    }

    @Override
    public void onEvent(ChatEvent event, String line) {
        super.onEvent(event, line);
        long now = System.currentTimeMillis();
        if (event == ChatEvent.CANT_REACH) {
            int t = Combat.target();
            if (t >= 0) { unreachable.put(t, now + 60_000); System.out.println("[builder] the game says it cannot reach " + describe(t) + ": leaving it 60 s"); }
            fightSince = 0;                               // attack another at the next tick
            if (phase == Phase.APPROACH) phase = Phase.FIGHT;
        } else if (event == ChatEvent.NO_AMMO && phase == Phase.FIGHT) {
            phase = Phase.GEAR;                           // out of arrows: gear up again
        }
    }

    private static String describe(int uid) {
        if (uid < 0) return "none";
        for (Entity e : Npcs.all()) if (e.uid() == uid) return e.name() + " #" + e.id() + " uid " + uid + " at " + e.worldX() + "," + e.worldY() + " (target " + e.target() + ")";
        return "uid " + uid + " (not an NPC in sight)";
    }

    private static String describeAll(int[] uids) {
        if (uids.length == 0) return "none";
        StringBuilder b = new StringBuilder();
        for (int u : uids) b.append(b.length() == 0 ? "" : "; ").append(describe(u));
        return b.toString();
    }

    private Result fight(Local me, long now) {
        // gear gone from your hands: a death (or a swap) -- gear up again
        if (!weaponName.isEmpty() && Equipment.worn(Equipment.WEAPON) < 0) { state("weapon gone: gearing up again"); phase = Phase.GEAR; return Result.CONTINUE; }
        if (fight.style() == Loadouts.Style.RANGED && Equipment.amount(Equipment.AMMO) <= 0) { state("out of arrows: gearing up again"); phase = Phase.GEAR; return Result.CONTINUE; }
        if (Inventory.isFull()) { trip = new BankTrip(keep(), supplies()).toward(spot); phase = Phase.SUPPLY; return Result.CONTINUE; }

        int myTarget = Combat.target();
        int[] attackers = Combat.attackersNear(10);   // a far "attacker" is a uid coincidence (Combat)
        boolean fighting = myTarget >= 0 && Npcs.all().stream().anyMatch(n -> n.uid() == myTarget && fight.matches(n));
        if (fighting || (attackers.length > 0 && now - attackedAt < 2500)) {
            // a fight that gives no Hitpoints experience for 20 s is not a fight (a target behind a fence, or one
            // the game has dropped): say what it looked like, then pick a target again below
            int xp = Skills.experience(Skill.HITPOINTS);
            if (fightXp < 0 || xp != fightXp) { fightXp = xp; fightSince = now; }
            if (now - lastFightLog > 15000) {
                lastFightLog = now;
                System.out.println("[builder] fighting: my target " + describe(myTarget) + ", attackers " + describeAll(attackers)
                        + ", hp " + me.health() + "/" + me.maxHealth() + ", no xp for " + (now - fightSince) / 1000 + " s");
            }
            // not reached yet: the target is not fighting back. A real fight gives xp within seconds; a target
            // the game cannot path to (behind a fence or a shut gate) never does
            Entity tgt = null;
            for (Entity e : Npcs.all()) if (e.uid() == myTarget) tgt = e;
            boolean engaged = tgt != null && tgt.target() == me.uid();
            long limit = engaged ? 20000 : 6000;
            if (now - fightSince < limit) { state("fighting"); return Result.CONTINUE; }
            fightSince = now;
            if (tgt != null && !engaged) {
                int n = stalls.merge(tgt.uid(), 1, Integer::sum);
                if (n <= 2) {
                    System.out.println("[builder] cannot reach " + describe(myTarget) + " from " + me.worldX() + "," + me.worldY() + ": walking up to it (try " + n + ")"
                            + "; wielding " + Equipment.name(Equipment.WEAPON) + ", ammo " + Equipment.name(Equipment.AMMO) + " x" + Equipment.amount(Equipment.AMMO)
                            + ", animation " + me.animation() + ", " + tgt.distance() + " tiles");
                    approach = new Walker.Route(tgt.worldX(), tgt.worldY(), me.plane(), 1);
                    approachUid = tgt.uid();
                    approachUntil = now + 20000;
                    phase = Phase.APPROACH;
                    return Result.CONTINUE;
                }
                unreachable.put(tgt.uid(), now + 60_000);
                stalls.remove(tgt.uid());
                System.out.println("[builder] still cannot reach " + describe(myTarget) + ": leaving it 60 s");
            } else System.out.println("[builder] fight not progressing for 20 s (target " + describe(myTarget) + "): attacking again");
        } else fightXp = -1;

        // bury, then loot, while nothing is fighting you
        if (fight.bury() && attackers.length == 0) {
            int b = Inventory.firstSlot("Bones");
            if (b < 0) b = Inventory.firstSlot("Big bones");
            if (b >= 0) { state("burying bones"); Inventory.interact(b, "Bury"); sleep(now, 1100, 1500); return Result.CONTINUE; }
        }
        if (attackers.length == 0) {
            for (GroundItems.GroundItem g : GroundItems.all(4)) {
                String n = oxclient.api.Items.name(g.id());
                if (!LOOT.contains(n)) continue;
                String key = n + "@" + g.sceneX() + "," + g.sceneY();
                if (lootTries.merge(key, 1, Integer::sum) > 2) continue;   // across a fence or not takeable: leave it
                int op = g.option("Take");
                state("taking " + n);
                g.interact(op > 0 ? op : 3);
                sleep(now, 1200, 1800);
                return Result.CONTINUE;
            }
        }

        // a target: one attacking you first, else the nearest free one
        Entity best = null;
        int bestD = Integer.MAX_VALUE;
        for (Entity e : Npcs.all()) {
            if (!fight.matches(e)) continue;
            boolean mine = false;
            for (int a : attackers) mine |= a == e.uid();
            if (!mine && e.busyWithOther()) continue;
            Long until = unreachable.get(e.uid());
            if (!mine && until != null && until > now) continue;
            if (spot.distance(e.worldX(), e.worldY(), me.plane()) > spot.radius() + 4) continue;
            int d = e.distance() - (mine ? 1000 : 0);
            if (d < bestD) { bestD = d; best = e; }
        }
        if (best == null) { state("waiting for a " + fight.npcs().get(0)); sleep(now, 1000, 1600); return Result.CONTINUE; }
        if (fight.style() == Loadouts.Style.MAGIC) {
            Spells.Spell s = Spells.best();
            if (s == null) return fail("no spell mapped for Magic " + Skills.level(Skill.MAGIC));
            state("casting " + s.name() + " on " + best.name());
            Spells.cast(s, best);
        } else {
            state("attacking " + best.name());
            if (!Actions.npc(best, "Attack")) Actions.npc(best, 2);    // "Attack" is option 2 on most monsters
        }
        attackedAt = now;
        sleep(now, 1500, 2200);
        return Result.CONTINUE;
    }
}
