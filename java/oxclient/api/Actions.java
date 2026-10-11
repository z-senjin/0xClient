package oxclient.api;

import java.awt.Point;

import oxclient.Natives;

/**
 * Walking, and the older ways of clicking things.
 *
 * <p><b>To click a thing, call {@code interact} on it</b>: {@code npc.interact("Attack")},
 * {@code tree.interact("Chop down")}, {@code item.interact("Eat")} -- see {@link Interactable}. The NPC and
 * scenery methods here predate that and stay only so existing scripts keep compiling.</p>
 *
 * <p>Every action goes through the client's own menu-action senders (the {@code ACT_*} entries in
 * {@code client/offsets.hpp}) -- the same code that runs when you pick an option from a right-click menu. We
 * never build a network packet.</p>
 *
 * <p><b>Walking</b> ({@link #walk}, {@link #walkTo}) tries the client's walk sender first and falls back to a
 * posted left click on the tile, projected through the game's own camera. For anything further than the
 * loaded scene use {@link Walker}, which plans a route and walks it a step at a time.</p>
 *
 * <p><b>Actions take world coordinates.</b> A tile that is not in the loaded 104x104 scene cannot be clicked,
 * and the call says so (false, or {@link WalkResult#NOT_LOADED}) rather than guessing.</p>
 *
 * <p><b>Do not spam them.</b> Every plugin in this repo rate limits itself, and confirms an action's effect
 * with {@link Await} before acting again; yours should too.</p>
 */
public final class Actions {

    private Actions() {}

    // The client's internal menu action numbers, which client/actions.hpp maps onto its ACT_* senders
    // (offsets.hpp's MENU OPCODES block).
    private static final int OPLOC1  = 3;
    private static final int OP_WALK = 31;

    /** The client's game-state value that means "in the world". Anything else must not be clicked at. */
    private static final int STATE_LOGGED_IN = 30;

    /**
     * Keep clicks this many pixels clear of the canvas edge. A tile projected exactly onto the border
     * is a tile the camera has half-clipped; clicking it lands on the edge of the viewport where the
     * game's own hit test is least likely to agree with ours.
     */
    private static final int EDGE_MARGIN = 4;

    /** Fine units per tile, and half of one: the centre of a tile is {@code (scene << 7) + 64}. */
    private static final int HALF_TILE = 64;

    /**
     * How far from the tile centre a humanised click may land, in FINE units (a tile is 128). 44 of
     * 128 is about a third of a tile: visibly not the same pixel twice, and still far enough from the
     * edge that the game's own hit test cannot disagree about WHICH tile was clicked.
     *
     * <p>The jitter is applied in fine space and projected, never added to the projected pixel. A
     * pixel jitter is a different number of tiles at every camera angle and zoom -- at a low camera a
     * few pixels up the screen is several tiles away -- so it would silently walk somewhere else. In
     * fine space the offset is a fraction of the tile by construction, at any camera.</p>
     */
    static final int MAX_TILE_JITTER = 44;

    /** Gaussian spread of the jitter, in fine units. ~2 sigma reaches {@link #MAX_TILE_JITTER}. */
    private static final int JITTER_SIGMA = 22;

    /** How far from the target the humanised approach move lands, in pixels. */
    private static final int APPROACH_PX = 12;

    private static final java.util.Random RNG = new java.util.Random();

    /**
     * Whether clicks are humanised: a per-click offset inside the destination tile, and a mouse move
     * to an approach point before the one that lands on it. On by default; a caller debugging a
     * projection wants the exact tile centre and no extra moves, and turns it off.
     */
    private static volatile boolean humanise = true;

    /** Where {@link #press} put the button down, so {@link #release} can let it up in the same place. */
    private static int pressX = -1, pressY = -1;

    /** Turn the per-click humanisation on or off. See {@link #MAX_TILE_JITTER}. */
    public static void setHumanise(boolean on) { humanise = on; }

    /** Whether clicks are currently humanised. */
    public static boolean isHumanise() { return humanise; }

    /**
     * How a walk attempt ended. The distinction matters to a status line: "off screen" is fixed by
     * turning the camera, "not loaded" by walking closer, and neither is a failure of this API.
     */
    public enum WalkResult {
        /** The client's own walk sender (ACT_WALK) walked us: the usual path wherever that is derived. */
        ACTION,
        /** A left click was posted on the tile: the fallback where the walk sender is not derived. */
        CLICKED,
        /** That tile is not in the loaded 104x104 scene. Aim at a nearer one. */
        NOT_LOADED,
        /** The tile projects behind the camera or past the horizon: rotating the camera would help. */
        OFF_SCREEN,
        /** It projects outside the game canvas (or the canvas size is unknown), so the click is refused. */
        OFF_CANVAS,
        /**
         * The click was aimed correctly and PostMessageW would not take it: the render view is gone
         * (the game closed under us) or its queue refused the message. Nothing reached the game, and
         * a driver that reported "walking" here would sit there forever saying so -- review
         * 2026-09-06, the one path in this method that used to succeed without posting anything.
         */
        NOT_POSTED,
        /** Not in the world -- title screen, loading, or logging in. Nothing is ever clicked there. */
        NOT_LOGGED_IN;

        /** True when something was actually issued to the game. */
        public boolean moved() { return this == ACTION || this == CLICKED; }
    }

    /**
     * Walk to a world tile, saying how it went.
     *
     * <p>Order is deliberate. The action function first: it is the exact way and it costs one native
     * call that returns false for free on a build where the walk sender is not derived. Then the click: project a point
     * INSIDE the tile at the best ground height we have ({@link Game#heightNear} -- entity heights,
     * since there is no terrain heightmap) and post a left click there. The point is the exact centre
     * only with humanisation off ({@link #setHumanise}); otherwise it is offset by a fraction of a
     * tile in FINE units, which is a fraction of a tile at every camera angle -- see
     * {@link #MAX_TILE_JITTER} for why that matters and a pixel jitter would not do.</p>
     *
     * <p>The click is refused rather than sent when it would be nonsense: not logged in (state != 30,
     * so no click ever reaches the title screen), the projection failed (behind the camera), or the
     * point is off the canvas. Off-canvas also covers the control panel, which docks to the RIGHT of
     * the game window rather than over it (see the {@code SetWindowPos} in {@code client/dllmain.cpp})
     * -- a canvas coordinate cannot reach it, and clamping into the canvas would silently walk
     * somewhere the caller did not ask for, so we refuse instead.</p>
     *
     * <p>Not refused, and worth knowing: in FIXED display mode the inventory and minimap are part of
     * the same canvas, so a tile that projects under them clicks the interface instead of the ground.
     * The game's own hit test owns that, we cannot see it from here, and the cost is a wasted click
     * rather than a wrong walk.</p>
     *
     * <p><b>Nothing here is remembered between calls, and that is load-bearing.</b> A caller passes a
     * WORLD tile; the scene conversion, the ground height and the projection all happen inside this
     * call, against the frame it is called on. No screen point survives a call -- the one exception is
     * {@link #press}, which necessarily parks the point it put the button down on so {@link #release}
     * can let it up in the same place, and which is a deliberate pair rather than a cache. So a tile
     * chosen seconds ago (a menu row picked after the menu opened, an auto-walk step chosen
     * before the camera turned) is still clicked where it is NOW, not where it was. If the camera has
     * since swung it behind us, the projection fails and this returns {@link WalkResult#OFF_SCREEN}
     * instead of clicking the stale point -- which is the whole reason the caller gets a result enum
     * rather than a boolean. Review 2026-09-06: the user reported click-to-walk landing in the wrong
     * place; a stale screen point would do exactly that, and this documents that there is not one.</p>
     */
    public static WalkResult walk(int worldX, int worldY) {
        Aim a = aim(worldX, worldY);
        if (a.action()) return WalkResult.ACTION;
        if (!a.ok()) return a.refusal();
        Point p = a.point();

        // Input.click's boolean is not decoration: false means at least one of the messages was never
        // queued (no render view, or PostMessageW refused), so nothing walked. Reporting CLICKED
        // there would have the driver announce "walking to X,Y" against a game that got nothing.
        boolean posted = humanise
                ? Input.click(p.x, p.y, p.x + approach(), p.y + approach())
                : Input.click(p.x, p.y);
        return posted ? WalkResult.CLICKED : WalkResult.NOT_POSTED;
    }

    /**
     * The first half of a click: move and put the button DOWN on a tile, leaving it down.
     *
     * <p>Exists so a caller can hold the button for a human dwell (60-140 ms) across frames instead of
     * releasing in the same burst, the way {@code oxclient.plugins.autologin.LoginSequence} does on the
     * title screen. <b>Every successful press MUST be followed by {@link #release}</b>, including on
     * the path where the caller is shut down mid-walk -- a button left down is a button the game
     * thinks the user is still holding. Only the single-call {@link #walk} shape has been seen working
     * in-game on client-240-6, so a driver using this pair should keep it behind a flag until a live
     * run confirms it (see {@code client/jvm.hpp}'s open question about GetAsyncKeyState).</p>
     *
     * @return {@link WalkResult#CLICKED} when the button is down and awaiting its release
     */
    public static WalkResult press(int worldX, int worldY) {
        Aim a = aim(worldX, worldY);
        if (a.action()) return WalkResult.ACTION;
        if (!a.ok()) return a.refusal();
        Point p = a.point();
        boolean posted = humanise
                ? Input.press(p.x, p.y, p.x + approach(), p.y + approach())
                : Input.press(p.x, p.y, p.x, p.y);
        if (!posted) return WalkResult.NOT_POSTED;
        pressX = p.x;
        pressY = p.y;
        return WalkResult.CLICKED;
    }

    /**
     * The second half: let the button up where {@link #press} put it down. A no-op returning true when
     * nothing is pressed, so a caller may call it unconditionally on shutdown.
     *
     * <p>A FAILED RELEASE KEEPS THE DEBT. This used to forget the press point whether or not the
     * mouse-up was queued, so a PostMessageW the game's queue refused left the button down for the
     * rest of the session with nothing left that knew where to let it up -- and a button the game
     * believes is held turns every later posted mouse-move into a DRAG. False now means "still down,
     * call me again"; {@code oxclient.rl.AutoWalk} retries it every frame and posts nothing meanwhile.</p>
     */
    public static boolean release() {
        if (pressX < 0) return true;
        if (!Input.mouseUp(pressX, pressY)) return false;
        pressX = pressY = -1;
        return true;
    }

    /** Where a click on a tile would land, or why it would not land anywhere. */
    public record Aim(Point point, WalkResult refusal) {
        /** True when {@link #point} is a canvas point we are willing to click. */
        public boolean ok() { return point != null && refusal == null; }

        /** True when the game's own action function already did the walking; nothing to click. */
        public boolean action() { return refusal == WalkResult.ACTION; }
    }

    /**
     * Aim at a world tile without posting anything: the projection, the canvas guard and the reason
     * for a refusal, in one value. {@link #walk} is this plus the click.
     *
     * <p>Split out so a driver can ask "would a click on that tile land?" for several candidate tiles
     * and only click the one that would -- which is how {@code oxclient.rl.AutoWalk} avoids burning a
     * whole frame on a step that has gone behind the camera. Nothing is remembered between calls
     * here either: the answer belongs to the frame it was asked on, so a caller that keeps an Aim and
     * clicks it later is clicking a stale pixel. Re-aim on the frame you post.</p>
     */
    public static Aim aim(int worldX, int worldY) {
        int jx = 0, jy = 0;
        if (humanise) {
            jx = rollJitter();
            jy = rollJitter();
        }
        return aim(worldX, worldY, jx, jy);
    }

    /**
     * {@link #aim(int, int)} with an explicit offset inside the tile, in FINE units (128 to a tile,
     * 0 = the exact centre). Clamped to {@link #MAX_TILE_JITTER} so the point cannot leave the tile.
     */
    public static Aim aim(int worldX, int worldY, int fineJitterX, int fineJitterY) {
        // "Are we in the world" comes FIRST, before the scene lookup and before either way of moving.
        // It used to sit after toScene, so a call made at the title screen or mid-load reported
        // NOT_LOADED -- true (no scene is loaded there) but the wrong reason, and a caller retrying
        // "until the scene loads" would retry forever at a login screen. It also used to sit between
        // the action path and the click path, which was harmless only because DO_ACTION is 0 and the
        // action call is a no-op: the day that offset lands, a driver ticking through a loading
        // screen would have issued a real walk action at the title screen. Both ways of moving are
        // gated by this or neither is.
        if (Natives.gameState() != STATE_LOGGED_IN) return new Aim(null, WalkResult.NOT_LOGGED_IN);

        Point s = Game.toScene(worldX, worldY);
        if (s == null) return new Aim(null, WalkResult.NOT_LOADED);

        // Preferred path: the client's walk sender (ACT_WALK). Free to attempt: false at once on a build
        // where it is not derived (client/offsets.hpp).
        // It walks by itself, so there is no point to hand back -- ACTION as the "refusal" is how the
        // caller is told the walk already happened without a click.
        if (Natives.doAction(s.x, s.y, OP_WALK, 0)) return new Aim(null, WalkResult.ACTION);

        // Projected HERE, on this call, from the tile -- never from a point a caller kept. null means
        // the client's own projection refused it (behind the camera, or past the horizon): report it
        // rather than guessing a pixel, because a guessed pixel is a walk to somewhere else.
        Point p = Game.projectFine((s.x << 7) + HALF_TILE + clampJitter(fineJitterX),
                Game.heightNear(s.x, s.y),
                (s.y << 7) + HALF_TILE + clampJitter(fineJitterY));
        if (p == null) return new Aim(null, WalkResult.OFF_SCREEN);

        Input.Target t = Input.target(false);
        if (!clickable(p.x, p.y, t.w(), t.h())) return new Aim(null, WalkResult.OFF_CANVAS);
        return new Aim(p, null);
    }

    /** A gaussian offset inside the destination tile, in fine units. */
    private static int rollJitter() {
        return clampJitter((int) Math.round(RNG.nextGaussian() * JITTER_SIGMA));
    }

    /** A few pixels either way, for the approach move that precedes a humanised click. */
    private static int approach() {
        return RNG.nextInt(2 * APPROACH_PX + 1) - APPROACH_PX;
    }

    /**
     * Keep a tile offset inside the tile. Pure, so the one number that decides whether a humanised
     * click can land on the WRONG tile is testable without a game.
     */
    static int clampJitter(int fine) {
        if (fine > MAX_TILE_JITTER) return MAX_TILE_JITTER;
        return Math.max(fine, -MAX_TILE_JITTER);
    }

    /**
     * Is this canvas point somewhere we are willing to click? Pure, so the refusal is testable without
     * a game: inside a canvas we actually know the size of, and {@link #EDGE_MARGIN} clear of its edge.
     */
    static boolean clickable(int x, int y, int canvasW, int canvasH) {
        if (canvasW <= 2 * EDGE_MARGIN || canvasH <= 2 * EDGE_MARGIN) return false;
        return x >= EDGE_MARGIN && y >= EDGE_MARGIN
                && x < canvasW - EDGE_MARGIN && y < canvasH - EDGE_MARGIN;
    }

    /**
     * Walk to a world tile. The game pathfinds; we only say where.
     *
     * @return true when a walk was issued -- by the action function, or by a posted ground click.
     *     False when the tile is not loaded, is off screen, the click could not be posted, or the
     *     client is not in the world; use {@link #walk} when you need to say which.
     */
    public static boolean walkTo(int worldX, int worldY) {
        return walk(worldX, worldY).moved();
    }

    /** Walk to a world tile. */
    public static boolean walkTo(WorldPoint p) {
        return p != null && walkTo(p.x(), p.y());
    }

    /**
     * Click a piece of scenery -- a tree, a rock, a door -- taking its first option.
     *
     * @deprecated find it with {@link Scenery#query()} and call {@code interact("Chop down")} on it: by name,
     *     so it takes the option you mean rather than whichever is first
     *
     * <p>"First option" is Chop down on a tree and Mine on a rock, so this one method covers most
     * gathering. You need the object's id and the tile it stands on; the README's contribution list has
     * finding those automatically as its top item.</p>
     *
     * @return false when that tile is not in the loaded scene, or the action was dropped
     */
    @Deprecated
    public static boolean object(int objectId, int worldX, int worldY) {
        Point s = Game.toScene(worldX, worldY);
        if (s == null) return false;
        return Natives.doAction(s.x, s.y, OPLOC1, objectId);
    }

    /**
     * Click a piece of scenery at a world point.
     *
     * @deprecated use {@code interact} on a {@link Scenery.SceneObject}
     */
    @Deprecated
    public static boolean object(int objectId, WorldPoint p) {
        return p != null && object(objectId, p.x(), p.y());
    }

    /**
     * Take an NPC's first option -- Attack on anything hostile, but Talk-to on a shopkeeper, so a bot
     * that assumes this always attacks will cheerfully strike up a conversation with a cow.
     *
     * @return false when the NPC is null, has despawned, or the action was dropped
     * @deprecated call {@code npc.interact("Attack")}: by name, so it never talks to the cow
     */
    @Deprecated
    public static boolean npc(Entity npc) {
        return npc != null && npc.interact(1);
    }

    /**
     * Take one of an NPC's five options, numbered 1..5 as they appear in the right-click menu.
     *
     * @param option 1..5; anything else is ignored rather than sent, because an out-of-range opcode
     *               lands on some unrelated action rather than failing
     * @return false when the option was out of range, the NPC is null or has despawned, or the action
     *     was dropped
     * @deprecated call {@link Entity#interact(int)}
     */
    @Deprecated
    public static boolean npc(Entity npc, int option) { return npc != null && npc.interact(option); }

    /**
     * Take the NPC's option named {@code name} ("Attack", "Net", "Lure"). False when it has none.
     *
     * @deprecated call {@link Entity#interact(String)}
     */
    @Deprecated
    public static boolean npc(Entity npc, String name) { return npc != null && npc.interact(name); }
}
