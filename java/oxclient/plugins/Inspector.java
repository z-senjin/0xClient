package oxclient.plugins;

import java.awt.Color;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import oxclient.Natives;
import oxclient.Plugin;
import oxclient.api.Entity;
import oxclient.api.Game;
import oxclient.api.Inventory;
import oxclient.api.Local;
import oxclient.api.Npcs;
import oxclient.api.Players;
import oxclient.api.Scenery;
import oxclient.api.Scenery.SceneObject;
import oxclient.ui.Hud;
import oxclient.ui.Theme;

/**
 * Shows the numbers you need when writing a plugin: where you are, and the ids of the NPCs, players,
 * scenery and items around you.
 *
 * <p>Every part is a toggle in the settings:</p>
 * <ul>
 *   <li><b>Location</b>: your world tile, floor, scene tile, region id, animation and facing, with
 *       your tile outlined.</li>
 *   <li><b>NPC ids</b> and <b>player info</b>: name, type id (or uid) and animation over each one.</li>
 *   <li><b>Scenery ids</b>: the name and id of every object within a few tiles, at its origin tile;
 *       walls (doors, gates, fences) optionally too.</li>
 *   <li><b>Inventory</b> and <b>equipment</b>: the item id and amount in each slot. Item names are
 *       not read from the client yet, so these are ids only.</li>
 *   <li><b>Hover</b>: whatever is under the mouse -- an NPC, a player, an object with its options,
 *       or just the tile -- in a box beside the cursor.</li>
 *   <li><b>Open interfaces</b>: the ids of the interface groups that are loaded.</li>
 * </ul>
 *
 * <p>Read-only: it never clicks or sends anything.</p>
 */
public final class Inspector extends Plugin {

    private static final String[] EQUIPMENT_SLOTS = {
        "head", "cape", "neck", "weapon", "body", "shield", "", "legs", "", "hands", "feet", "", "ring", "ammo"
    };

    private List<SceneObject> objects = Collections.emptyList();
    private long nextScan;

    public Inspector() {
        config.bool("location", "Location", "Your tile, floor, region, animation and facing", true);
        config.bool("npcs", "NPC ids", "Name and type id over every NPC", true);
        config.bool("players", "Player info", "Name, uid and animation over other players", false);
        config.bool("objects", "Scenery ids", "Name and id of the objects around you", true);
        config.bool("walls", "Include walls", "Also label walls, doors, gates and fences", false);
        config.number("radius", "Scenery radius", "How far to label scenery, in tiles", 6, 1, 25);
        config.bool("inventory", "Inventory", "Item id and amount in each inventory slot", true);
        config.bool("equipment", "Equipment", "Item ids of what you are wearing", false);
        config.bool("hover", "Hover", "Show what is under the mouse", true);
        config.bool("interfaces", "Open interfaces", "Ids of the loaded interface groups", false);
        config.colour("colour", "Label colour", "Colour of the labels in the world", new Color(255, 210, 90));
    }

    @Override public String name() { return "Inspector"; }

    @Override public String description() { return "Shows your location and the ids of NPCs, scenery and items."; }

    @Override
    public String status() {
        Local me = Game.me();
        return me.exists() ? me.worldX() + ", " + me.worldY() + ", " + me.plane() : "";
    }

    @Override
    public void tick() {
        if (!Game.ready()) return;
        long now = System.currentTimeMillis();
        if (now < nextScan) return;
        nextScan = now + 300;                             // the scenery walk is not a per-frame job
        boolean need = config.bool("objects") || config.bool("hover");
        objects = need ? Scenery.all(Math.max(config.number("radius"), config.bool("hover") ? 12 : 0)) : Collections.emptyList();
    }

    // -----------------------------------------------------------------------------------------------
    // Drawing
    // -----------------------------------------------------------------------------------------------

    @Override
    public void render(Graphics2D g) {
        if (!Game.ready()) return;
        Color colour = config.colour("colour");
        g.setFont(Theme.UI);
        Local me = Game.me();

        if (config.bool("location")) Hud.tile(g, Game.tileOutline(me.sceneX(), me.sceneY(), me.height()), Theme.ON);
        if (config.bool("objects")) drawObjects(g, colour);
        if (config.bool("npcs")) {
            for (Entity n : Npcs.all()) label(g, n, n.name().isEmpty() ? "#" + n.id() : n.name() + "  #" + n.id(), colour);
        }
        if (config.bool("players")) {
            for (Entity p : Players.all()) label(g, p, p.name() + "  uid " + p.uid() + (p.isIdle() ? "" : "  anim " + p.animation()), Theme.TEXT);
        }

        // panels down the right-hand side, so they do not sit on top of a bot's own panel on the left
        int[] vp = Natives.viewport();
        int right = vp.length >= 4 && vp[2] > 0 ? vp[2] - 12 : 600;
        int y = 12;
        if (config.bool("location")) y += rightPanel(g, right, y, "Location", locationLines(me)) + 8;
        if (config.bool("inventory")) y += rightPanel(g, right, y, "Inventory (id x amount)", inventoryLines()) + 8;
        if (config.bool("equipment")) y += rightPanel(g, right, y, "Equipment", equipmentLines()) + 8;
        if (config.bool("interfaces")) rightPanel(g, right, y, "Open interface groups", interfaceLines());

        if (config.bool("hover")) drawHover(g, me);
    }

    private void label(Graphics2D g, Entity e, String text, Color colour) {
        Point at = e.screen();
        if (at == null) return;
        Hud.textCentred(g, text, at.x, at.y - 34, colour);
        if (!e.isIdle() && e.isNpc()) Hud.textCentred(g, "anim " + e.animation(), at.x, at.y - 20, Theme.TEXT_DIM);
    }

    private void drawObjects(Graphics2D g, Color colour) {
        int radius = config.number("radius");
        boolean walls = config.bool("walls");
        for (SceneObject o : objects) {
            if (o.distance() > radius || (o.isWall() && !walls)) continue;
            Point p = Game.projectTile(o.sceneX(), o.sceneY());
            if (p == null) continue;
            String n = o.name();
            Hud.textCentred(g, (n.isEmpty() ? "" : n + "  ") + "#" + o.id(), p.x, p.y, o.isWall() ? Theme.TEXT_DIM : colour);
        }
    }

    private Hud.Lines locationLines(Local me) {
        int x = me.worldX(), y = me.worldY();
        return new Hud.Lines()
                .add("world", x + ", " + y + ", " + me.plane())
                .add("scene", me.sceneX() + ", " + me.sceneY())
                .add("region", ((x >> 6) << 8 | (y >> 6)) + "  (" + (x & 63) + ", " + (y & 63) + ")")
                .add("animation", me.animation())
                .add("facing", me.orientation())
                .add("run energy", me.runEnergy() + "%");
    }

    private Hud.Lines inventoryLines() {
        int[] flat = Natives.container(Inventory.CONTAINER);
        Hud.Lines lines = new Hud.Lines();
        if (flat.length == 0) return lines.add("not readable right now");
        int slots = flat.length / 2;
        for (int row = 0; row * 4 < Inventory.SIZE; row++) {
            StringBuilder b = new StringBuilder();
            for (int c = 0; c < 4; c++) {
                int s = row * 4 + c;
                if (c > 0) b.append("   ");
                b.append(item(flat, s, slots));
            }
            lines.add((row * 4) + "-" + (row * 4 + 3), b.toString());
        }
        return lines;
    }

    private Hud.Lines equipmentLines() {
        int[] flat = Natives.container(94);
        Hud.Lines lines = new Hud.Lines();
        int slots = flat.length / 2;
        for (int s = 0; s < slots && s < EQUIPMENT_SLOTS.length; s++) {
            if (flat[2 * s] < 0 || flat[2 * s + 1] <= 0) continue;
            lines.add(EQUIPMENT_SLOTS[s].isEmpty() ? "slot " + s : EQUIPMENT_SLOTS[s], item(flat, s, slots));
        }
        return lines.isEmpty() ? lines.add("nothing worn") : lines;
    }

    private static String item(int[] flat, int slot, int slots) {
        if (slot >= slots || flat[2 * slot] < 0 || flat[2 * slot + 1] <= 0) return "-";
        int qty = flat[2 * slot + 1];
        return flat[2 * slot] + (qty > 1 ? " x" + qty : "");
    }

    private Hud.Lines interfaceLines() {
        int[] groups = Natives.loadedGroups();
        Hud.Lines lines = new Hud.Lines();
        StringBuilder b = new StringBuilder();
        int n = 0;
        for (int gid : groups) {
            if (n > 0 && n % 10 == 0) { lines.add(b.toString(), ""); b.setLength(0); }
            if (b.length() > 0) b.append(", ");
            b.append(gid);
            n++;
        }
        if (b.length() > 0) lines.add(b.toString(), "");
        return lines.isEmpty() ? lines.add("none") : lines;
    }

    /** Hud.panel, right-aligned at {@code right}. Returns its height. */
    private static int rightPanel(Graphics2D g, int right, int y, String title, Hud.Lines lines) {
        return Hud.panel(g, Math.max(0, right - Hud.panelWidth(g, title, lines)), y, title, lines);
    }

    // -----------------------------------------------------------------------------------------------
    // What is under the mouse
    // -----------------------------------------------------------------------------------------------

    private void drawHover(Graphics2D g, Local me) {
        int[] m = Natives.mouse();
        if (m.length < 2) return;
        int mx = m[0], my = m[1];
        List<String> text = new ArrayList<>();

        Entity best = null;
        int bestD = Integer.MAX_VALUE;
        for (Entity e : Game.entities()) {
            Point at = e.screen();
            if (at == null || Math.abs(mx - at.x) > 14 || my < at.y - 38 || my > at.y + 6) continue;
            int d = Math.abs(mx - at.x) + Math.abs(my - (at.y - 16));
            if (d < bestD) { bestD = d; best = e; }
        }
        if (best != null) {
            text.add((best.isPlayer() ? "Player " : "NPC ") + (best.name().isEmpty() ? "?" : best.name()));
            text.add(best.isPlayer() ? "uid " + best.uid() : "id " + best.id() + "   uid " + best.uid());
            text.add("tile " + best.worldX() + ", " + best.worldY() + "   anim " + best.animation());
        } else {
            SceneObject obj = null;
            int objD = 20 * 20;
            for (SceneObject o : objects) {
                Point p = Game.projectTile(o.sceneX(), o.sceneY());
                if (p == null) continue;
                int d = (p.x - mx) * (p.x - mx) + (p.y - my) * (p.y - my);
                if (d < objD) { objD = d; obj = o; }
            }
            if (obj != null) {
                text.add((obj.isWall() ? "Wall " : "Object ") + (obj.name().isEmpty() ? "?" : obj.name()));
                text.add("id " + obj.id() + "   tile " + obj.worldX() + ", " + obj.worldY());
                StringBuilder ops = new StringBuilder();
                String[] o = obj.options();
                for (int i = 0; i < o.length; i++) {
                    if (o[i].isEmpty()) continue;
                    if (ops.length() > 0) ops.append("  ");
                    ops.append(i + 1).append(':').append(o[i]);
                }
                if (ops.length() > 0) text.add(ops.toString());
            }
        }

        // the tile under the mouse, always
        int[] tile = tileUnder(me, mx, my);
        if (tile != null) {
            text.add("tile under mouse " + (Game.sceneBaseX() + tile[0]) + ", " + (Game.sceneBaseY() + tile[1]));
            Hud.tile(g, Game.tileOutline(tile[0], tile[1], me.height()), Theme.alpha(Theme.TEXT, 140));
        }
        if (text.isEmpty()) return;
        drawTooltip(g, mx + 16, my + 16, text);
    }

    /** The scene tile whose centre projects nearest the mouse, within 12 tiles of you, or null. */
    private static int[] tileUnder(Local me, int mx, int my) {
        int[] best = null;
        int bestD = 40 * 40;
        int h = me.height();
        for (int dx = -12; dx <= 12; dx++) {
            for (int dy = -12; dy <= 12; dy++) {
                int sx = me.sceneX() + dx, sy = me.sceneY() + dy;
                if (sx < 0 || sy < 0 || sx > 103 || sy > 103) continue;
                Point p = Game.projectTile(sx, sy, h);
                if (p == null) continue;
                int d = (p.x - mx) * (p.x - mx) + (p.y - my) * (p.y - my);
                if (d < bestD) { bestD = d; best = new int[] {sx, sy}; }
            }
        }
        return best;
    }

    private static void drawTooltip(Graphics2D g, int x, int y, List<String> text) {
        g.setFont(Theme.UI);
        FontMetrics fm = g.getFontMetrics();
        int w = 0;
        for (String s : text) w = Math.max(w, fm.stringWidth(s));
        int h = text.size() * 15 + 8;
        g.setColor(Theme.HUD_BACK);
        g.fillRoundRect(x, y, w + 14, h, 6, 6);
        g.setColor(Theme.HUD_BORDER);
        g.drawRoundRect(x, y, w + 14, h, 6, 6);
        int ty = y + 16;
        for (int i = 0; i < text.size(); i++) {
            Hud.text(g, text.get(i), x + 7, ty, i == 0 ? Theme.ACCENT : Theme.TEXT);
            ty += 15;
        }
    }
}
