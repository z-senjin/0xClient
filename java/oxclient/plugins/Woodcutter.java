package oxclient.plugins;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Polygon;
import java.awt.Stroke;

import oxclient.Plugin;
import oxclient.api.Actions;
import oxclient.api.Game;
import oxclient.api.Local;
import oxclient.api.Skill;
import oxclient.api.Skills;
import oxclient.ui.Hud;
import oxclient.ui.Theme;

/**
 * Chops one tree, and draws what it is doing.
 *
 * <p><b>This is the worked example.</b> It is deliberately the most complete plugin in the repo and
 * deliberately still short, because it shows all four things a plugin can do and how they fit together:
 * settings you can change while it runs, a decision loop that acts on the game, an overlay that draws in
 * the world, and a panel of statistics. Copy this file when you write your own.</p>
 *
 * <h2>Using it</h2>
 *
 * <ol>
 *   <li>Turn on <b>NPC visuals</b> if you want to read ids off things.</li>
 *   <li>Stand next to your tree. The overlay tells you the tile you are on.</li>
 *   <li>Type that tile and the tree's id into this plugin's settings.</li>
 *   <li>Tick the box.</li>
 * </ol>
 *
 * <p>Typing coordinates in by hand is not elegant. This plugin predates scenery enumeration and is
 * kept as the simplest worked example; {@link MntnChopper} uses {@link oxclient.api.Scenery} to find
 * the nearest tree itself.</p>
 */
public final class Woodcutter extends Plugin {

    /** Willow. Any tree works -- this is just a default that is not zero. */
    private static final int DEFAULT_TREE = 1278;

    private long lastClick;
    private long startedAt;
    private int startXp;
    private int clicks;

    public Woodcutter() {
        config.text("tree", "Tree id", "The scenery id to chop. 1278 is an ordinary tree.",
                String.valueOf(DEFAULT_TREE));
        config.text("x", "Tile x", "World x of the tree. The overlay shows yours.", "");
        config.text("y", "Tile y", "World y of the tree.", "");
        config.number("delay", "Click delay", "Seconds to wait before clicking again", 4, 1, 15);
        config.colour("colour", "Highlight", "Colour of the tree marker", new Color(90, 220, 120));
        config.bool("panel", "Show statistics", "Draw the xp panel", true);
    }

    @Override public String name() { return "Woodcutter"; }

    @Override public String description() { return "Chops one tree. The worked example -- read this one."; }

    @Override public int hotkey() { return 4; }          // F5

    @Override
    protected void onEnable() {
        startedAt = System.currentTimeMillis();
        startXp = Skills.experience(Skill.WOODCUTTING);
        clicks = 0;
        lastClick = 0;
    }

    @Override
    public String status() {
        if (!hasTarget()) return "set a tile";
        return gainedXp() + " xp, " + clicks + " clicks";
    }

    // -----------------------------------------------------------------------------------------------
    // Deciding and acting
    // -----------------------------------------------------------------------------------------------

    @Override
    public void tick() {
        if (!Game.ready() || !hasTarget()) return;

        Local me = Game.me();

        // Two guards, and they are the whole bot.
        //
        // The animation check is what stops it clicking while it is already chopping. The timer is what
        // stops it clicking the instant the animation ends -- the game takes a moment to start the next
        // one, so a bot that only checked the animation would fire two or three clicks into that gap
        // and look exactly like a bot.
        if (!me.isIdle()) return;
        long now = System.currentTimeMillis();
        if (now - lastClick < config.number("delay") * 1000L) return;

        if (Actions.object(treeId(), targetX(), targetY())) {
            lastClick = now;
            clicks++;
        }
    }

    // -----------------------------------------------------------------------------------------------
    // Drawing
    // -----------------------------------------------------------------------------------------------

    @Override
    public void render(Graphics2D g) {
        if (!Game.ready()) return;

        Local me = Game.me();
        Color colour = config.colour("colour");
        g.setFont(Theme.UI);

        if (!hasTarget()) {
            // Nothing to chop yet, so be useful: tell them where they are, which is the number they
            // need to type in. A plugin that just does nothing when misconfigured is a bad plugin.
            Point here = Game.projectTile(me.sceneX(), me.sceneY());
            Hud.tile(g, Game.tileOutline(me.sceneX(), me.sceneY()), Theme.WARN);
            if (here != null) {
                Hud.textCentred(g, "you are at " + me.worldX() + ", " + me.worldY(),
                        here.x, here.y - 14, Theme.WARN);
            }
            Hud.Lines lines = new Hud.Lines()
                    .add("tile x", me.worldX())
                    .add("tile y", me.worldY())
                    .add("Type these into the plugin");
            Hud.panel(g, 12, 12, "Woodcutter: not set up", lines);
            return;
        }

        // The target tile, outlined on the ground. tileOutlineWorld returns null when the tile is not
        // in the loaded chunk or is off screen, and null simply means "do not draw" -- there is nothing
        // to handle.
        Polygon outline = Game.tileOutlineWorld(targetX(), targetY());
        Hud.tile(g, outline, colour);

        Point target = Game.projectWorld(targetX(), targetY());
        if (target != null) {
            Hud.textCentred(g, "tree " + treeId(), target.x, target.y - 12, colour);

            // A line from you to the tree, so it is obvious at a glance what it is aiming at.
            Point mine = Game.projectTile(me.sceneX(), me.sceneY());
            if (mine != null) {
                Stroke old = g.getStroke();
                g.setStroke(new BasicStroke(1.5f));
                g.setColor(Theme.alpha(colour, 120));
                g.drawLine(mine.x, mine.y, target.x, target.y);
                g.setStroke(old);
            }
        }

        if (config.bool("panel")) {
            boolean chopping = !me.isIdle();
            Hud.Lines lines = new Hud.Lines()
                    .add("state", chopping ? "chopping" : "waiting", chopping ? Theme.ON : Theme.TEXT_DIM)
                    .add("level", Skills.level(Skill.WOODCUTTING))
                    .add("xp gained", gainedXp())
                    .add("xp / hour", xpPerHour())
                    .add("clicks", clicks)
                    .add("running", elapsed());
            Hud.panel(g, 12, 12, "Woodcutter", lines);
        }
    }

    // -----------------------------------------------------------------------------------------------
    // Settings, read defensively -- the text boxes contain whatever somebody typed
    // -----------------------------------------------------------------------------------------------

    private boolean hasTarget() {
        return treeId() > 0 && targetX() > 0 && targetY() > 0;
    }

    private int treeId() { return parse(config.text("tree")); }

    private int targetX() { return parse(config.text("x")); }

    private int targetY() { return parse(config.text("y")); }

    /** Zero for anything that is not a number, which {@link #hasTarget()} then treats as "not set". */
    private static int parse(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private int gainedXp() {
        return Math.max(0, Skills.experience(Skill.WOODCUTTING) - startXp);
    }

    private int xpPerHour() {
        long ms = System.currentTimeMillis() - startedAt;
        if (ms < 10_000) return 0;                       // too early to mean anything
        return (int) (gainedXp() * 3_600_000L / ms);
    }

    private String elapsed() {
        long s = (System.currentTimeMillis() - startedAt) / 1000;
        return String.format("%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60);
    }
}
