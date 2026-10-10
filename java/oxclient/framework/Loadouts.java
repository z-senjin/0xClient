package oxclient.framework;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import oxclient.api.Equipment;
import oxclient.api.Skill;
import oxclient.api.Skills;

/**
 * The gear for each combat style, best first per slot, with each piece's level requirements (OSRS item names
 * and requirements, free-to-play only). For a stint the builder wears, per slot, the best piece it can use
 * and owns (carried, worn, or in the bank as last seen); the best piece it can use but does not own goes on
 * the shopping list for the Grand Exchange (plan, Phase 3).
 *
 * <p>Choices: scimitars for melee (the fastest free-to-play weapons to train with); a rune CHAINbody tops the
 * melee body slot because a rune platebody needs Dragon Slayer; shortbows for ranged, with the best arrows the
 * bow can fire; a staff of air for magic (it supplies the air runes of the strike and bolt spells).</p>
 */
public final class Loadouts {

    private Loadouts() {}

    /** The combat styles a stint can train with. */
    public enum Style { MELEE, RANGED, MAGIC }

    /** One wearable item and what it needs. */
    public record Piece(String name, int slot, Map<Skill, Integer> needs) {
        public boolean usable() {
            for (Map.Entry<Skill, Integer> e : needs.entrySet()) if (Skills.level(e.getKey()) < e.getValue()) return false;
            return true;
        }

        /** Worn, carried, or in the bank when it was last seen. */
        public boolean owned() {
            return Equipment.name(slot).equalsIgnoreCase(name) || Gear.carrying(name) || BankCache.count(name) > 0;
        }

        public boolean worn() { return Equipment.name(slot).equalsIgnoreCase(name); }
    }

    private static final int[] METAL_LEVELS = {40, 30, 20, 10, 5, 1, 1};
    private static final String[] METALS = {"Rune", "Adamant", "Mithril", "Black", "Steel", "Iron", "Bronze"};

    private static List<Piece> metal(String item, int slot, Skill skill) {
        List<Piece> out = new ArrayList<>();
        for (int i = 0; i < METALS.length; i++) out.add(new Piece(METALS[i] + " " + item, slot, Map.of(skill, METAL_LEVELS[i])));
        return out;
    }

    private static Piece p(String name, int slot, Object... reqs) {
        Map<Skill, Integer> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < reqs.length; i += 2) m.put((Skill) reqs[i], (Integer) reqs[i + 1]);
        return new Piece(name, slot, m);
    }

    static final Map<Style, Map<Integer, List<Piece>>> GEAR = new EnumMap<>(Style.class);

    static {
        Map<Integer, List<Piece>> melee = new LinkedHashMap<>();
        List<Piece> weapons = new ArrayList<>(metal("scimitar", Equipment.WEAPON, Skill.ATTACK));
        weapons.add(p("Bronze sword", Equipment.WEAPON));                // what Tutorial Island hands out
        weapons.add(p("Bronze dagger", Equipment.WEAPON));
        melee.put(Equipment.WEAPON, weapons);
        melee.put(Equipment.HEAD, metal("full helm", Equipment.HEAD, Skill.DEFENCE));
        List<Piece> body = new ArrayList<>();
        body.add(p("Rune chainbody", Equipment.BODY, Skill.DEFENCE, 40));
        body.addAll(metal("platebody", Equipment.BODY, Skill.DEFENCE).subList(1, METALS.length));   // no rune platebody
        melee.put(Equipment.BODY, body);
        melee.put(Equipment.LEGS, metal("platelegs", Equipment.LEGS, Skill.DEFENCE));
        List<Piece> shields = new ArrayList<>(metal("kiteshield", Equipment.SHIELD, Skill.DEFENCE));
        shields.add(p("Wooden shield", Equipment.SHIELD));
        melee.put(Equipment.SHIELD, shields);
        GEAR.put(Style.MELEE, melee);

        Map<Integer, List<Piece>> ranged = new LinkedHashMap<>();
        ranged.put(Equipment.WEAPON, List.of(p("Maple shortbow", Equipment.WEAPON, Skill.RANGED, 30),
                p("Willow shortbow", Equipment.WEAPON, Skill.RANGED, 20), p("Oak shortbow", Equipment.WEAPON, Skill.RANGED, 5),
                p("Shortbow", Equipment.WEAPON, Skill.RANGED, 1)));
        ranged.put(Equipment.HEAD, List.of(p("Coif", Equipment.HEAD, Skill.RANGED, 20), p("Leather cowl", Equipment.HEAD)));
        ranged.put(Equipment.BODY, List.of(p("Green d'hide body", Equipment.BODY, Skill.RANGED, 40, Skill.DEFENCE, 40),
                p("Studded body", Equipment.BODY, Skill.RANGED, 20, Skill.DEFENCE, 20), p("Hardleather body", Equipment.BODY, Skill.DEFENCE, 10),
                p("Leather body", Equipment.BODY)));
        ranged.put(Equipment.LEGS, List.of(p("Green d'hide chaps", Equipment.LEGS, Skill.RANGED, 40),
                p("Studded chaps", Equipment.LEGS, Skill.RANGED, 20), p("Leather chaps", Equipment.LEGS)));
        ranged.put(Equipment.HANDS, List.of(p("Green d'hide vambraces", Equipment.HANDS, Skill.RANGED, 40), p("Leather vambraces", Equipment.HANDS)));
        GEAR.put(Style.RANGED, ranged);

        Map<Integer, List<Piece>> magic = new LinkedHashMap<>();
        magic.put(Equipment.WEAPON, List.of(p("Staff of air", Equipment.WEAPON)));
        magic.put(Equipment.HEAD, List.of(p("Wizard hat", Equipment.HEAD)));
        magic.put(Equipment.BODY, List.of(p("Blue wizard robe", Equipment.BODY)));
        GEAR.put(Style.MAGIC, magic);
    }

    /** The arrows each bow can fire, best first (OSRS: a bow fires arrows up to its own metal). */
    static List<String> arrowsFor(String bow) {
        List<String> all = List.of("Adamant arrow", "Mithril arrow", "Steel arrow", "Iron arrow", "Bronze arrow");
        int from = switch (bow) { case "Maple shortbow" -> 0; case "Willow shortbow" -> 1; case "Oak shortbow" -> 2; default -> 3; };
        return all.subList(from, all.size());
    }

    /** Per slot, the best piece you can use AND own; slots with nothing owned are left out. */
    public static List<Piece> bestOwned(Style style) {
        List<Piece> out = new ArrayList<>();
        for (List<Piece> slot : GEAR.get(style).values())
            for (Piece p : slot) if (p.usable() && p.owned()) { out.add(p); break; }
        return out;
    }

    /** Per slot, the best piece you can use but do not own: what to buy. */
    public static List<Piece> upgrades(Style style) {
        List<Piece> out = new ArrayList<>();
        for (List<Piece> slot : GEAR.get(style).values())
            for (Piece p : slot) if (p.usable()) { if (!p.owned()) out.add(p); break; }
        return out;
    }

    /** True when the style has its weapon (the one piece it cannot train without; magic also needs runes). */
    public static boolean hasWeapon(Style style) {
        for (Piece p : bestOwned(style)) if (p.slot() == Equipment.WEAPON) return true;
        return false;
    }

    /** The best arrows for the bow in use that are owned (carried, worn, banked), or null. */
    public static String arrows() {
        String bow = Equipment.name(Equipment.WEAPON);
        for (String a : arrowsFor(bow)) if (Equipment.name(Equipment.AMMO).equalsIgnoreCase(a) || Gear.carrying(a) || BankCache.count(a) > 0) return a;
        return null;
    }
}
