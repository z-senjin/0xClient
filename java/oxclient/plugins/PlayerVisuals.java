package oxclient.plugins;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Point;

import oxclient.Plugin;
import oxclient.api.Entity;
import oxclient.api.Game;
import oxclient.api.Players;
import oxclient.ui.Hud;
import oxclient.ui.Theme;

/**
 * Draws a box over every other player.
 *
 * <p>THE WORKED EXAMPLE, not the client's player visuals. The simplest possible overlay plugin: no
 * state, no actions, nothing in {@code tick()} -- read this one first if you want to draw something,
 * and copy it to start your own.</p>
 *
 * <p>What the client actually draws over players is the RuneLite port, {@code Player Indicators}
 * ({@code net.runelite.client.plugins.playerindicators}): a convex hull at the player's own ground
 * height instead of a fixed 16x34 box, the name instead of the id slot, an optional tile and minimap
 * name, and RuneLite's own config keys. This one is registered in the Developer section and stays
 * OFF, so the two never draw over each other (2026-09-06). Nothing in the port reads anything from
 * here -- it is source to learn from, and deleting it would cost the README its example and cost the
 * client nothing.</p>
 */
public final class PlayerVisuals extends Plugin {

    public PlayerVisuals() {
        config.colour("colour", "Colour", "Box colour", new Color(90, 200, 255));
        config.bool("tile", "Mark tiles", "Outline the tile each player is standing on", false);
        config.bool("combat", "Show combat level", "Draw their combat level above the box", true);
        config.number("range", "Range", "Only draw players within this many tiles", 30, 1, 60);
    }

    @Override public String name() { return "Player visuals"; }

    @Override public String description() { return "Boxes and combat levels over other players."; }

    @Override public int hotkey() { return 0; }          // F1

    @Override public String status() { return Players.all().size() + " visible"; }

    @Override
    public void render(Graphics2D g) {
        if (!Game.ready()) return;

        Color colour = config.colour("colour");
        int range = config.number("range");
        boolean tiles = config.bool("tile");
        boolean combat = config.bool("combat");

        g.setFont(Theme.UI);
        for (Entity p : Players.all()) {
            if (p.distance() > range) continue;

            if (tiles) Hud.tile(g, p.tileOutline(), colour);        // at the player's own ground height

            Point at = p.screen();
            if (at == null) continue;
            Hud.entityBox(g, at, 16, 34, colour);

            // For a player the id slot carries their combat level -- see Entity.id(). The offset is
            // derived but not verified live, and a garbage label over a player is worse than none, so
            // anything outside the levels a real account can hold is not drawn.
            if (combat && p.id() >= 3 && p.id() <= 126) {
                Hud.textCentred(g, "lvl " + p.id(), at.x, at.y - 38, colour);
            }
        }
    }
}
