// Shim of net.runelite.api.Actor (BSD-2, RuneLite) -- an abstract base over the per-frame primitives
// oxclient.api.Entity / oxclient.api.Local expose, so NPC and Player share every projection helper.
//
// What is real here: position (scene tile, fine render position, ground height under the actor),
// animation, orientation, name. What is approximated, and says so in its javadoc: the convex hull
// (no model access on this build), the logical height (a tuning constant), the plane (the local
// player's -- no per-entity plane is exported by nEntities). What is an honest default: combat level
// 0, no interacting target, no health bar, no overhead text, no graphic.
package net.runelite.api;

import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.Shape;
import java.awt.image.BufferedImage;

import javax.annotation.Nullable;

import oxclient.api.Game;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldArea;
import net.runelite.api.coords.WorldPoint;

public abstract class Actor
{
	/**
	 * How tall an actor is assumed to be, in fine units (128 per tile) along the client's height axis.
	 * Plugins add +40 to this for name tags, so only the visual depends on the value, not
	 * compatibility. Upstream's default is 1000 in the deob's units; on this build a ground height of
	 * -312 projected ~290 px while a tile is ~130-230 px wide, i.e. vertical units project roughly 1:1
	 * with horizontal ones, which makes a humanoid ~150-250 units (a floor is ~240). 200 is the guess;
	 * the [actors] probe line in oxclient.rl.TestActors (feet vs head pixel) settles it, and the
	 * `oxclient.actor.height` system property or {@link #setLogicalHeight} moves it live.
	 */
	private static volatile int logicalHeight = Integer.getInteger("oxclient.actor.height", 200);

	public static int logicalHeight()
	{
		return logicalHeight;
	}

	/** Live tuning knob for the prism height; TestActors' config feeds it. */
	public static void setLogicalHeight(int height)
	{
		logicalHeight = Math.max(1, height);
	}

	// -- per-frame primitives, implemented by NPC and Player ----------------------------------------

	/** The game's own handle. Stable while the actor is on screen; the identity key of the ActorTable. */
	abstract int uid();

	/** False once the actor has left this frame's snapshot (despawned); its last position stays readable. */
	abstract boolean present();

	abstract int sceneX();

	abstract int sceneY();

	/** Rendered position in fine units (128 per tile), 0 when unread. */
	abstract int fineX();

	abstract int fineY();

	/** Ground height under the actor, client height axis (negative = up); 0 when unread. */
	abstract int height();

	abstract int animation();

	abstract int orientation();

	/** The name as read from the client, "" when it could not be read (never null). */
	abstract String rawName();

	/**
	 * Footprint in tiles. 1 here, and that is CORRECT for a Player -- every player in OSRS is 1x1.
	 * NPC overrides it with its composition, where the 1 IS a placeholder (no DEF_SIZE offset), and
	 * {@link NPCComposition#getSize} is the one that says so.
	 */
	int size()
	{
		return 1;
	}

	/** Every stub below hands back a placeholder; this is the one place that has to say so. */
	private static void gap(String accessor, String reason)
	{
		ShimSupport.note(accessor, ShimSupport.Kind.NEEDS_OFFSET, reason);
	}

	/** Cached-name state, shared by NPC and both Player shapes; ActorTable.nameOf owns the policy. */
	String name = "";
	int nameRetryFrame = Integer.MIN_VALUE;
	/** The last table frame this actor's uid appeared in; ActorTable's liveness mark. */
	int seenFrame = -1;

	// -- upstream surface ------------------------------------------------------------------------------

	/** Never null; "" when the client has not yielded a name yet (mid-spawn, or DEF_NAME still unread). */
	@Nullable
	public String getName()
	{
		return rawName();
	}

	/**
	 * 0 = "no combat level", upstream's own convention. Player overrides this (PLAYER_COMBAT_LEVEL,
	 * client/offsets.hpp); for an NPC the level lives on its definition, and no offset for that has
	 * been derived.
	 */
	public int getCombatLevel()
	{
		gap("Actor.getCombatLevel", "reads 0, upstream's \"no combat level\" sentinel, for every NPC:"
			+ " the level lives on the NPC definition and no offset for it has been derived. Never read a"
			+ " 0 here as a level 0 monster");
		return 0;
	}

	public WorldView getWorldView()
	{
		return WorldView.TOP_LEVEL;
	}

	/**
	 * The rendered position in fine units -- between tiles while walking, matching upstream's
	 * interpolated LocalPoint. Falls back to the tile centre when the fine position is unread (the
	 * same guard oxclient.api.Entity.screen uses).
	 */
	public LocalPoint getLocalLocation()
	{
		int fx = fineX(), fy = fineY();
		if (fx != 0 || fy != 0)
		{
			return new LocalPoint(fx, fy, WorldView.TOP_LEVEL);
		}
		return new LocalPoint((sceneX() << 7) + 64, (sceneY() << 7) + 64, WorldView.TOP_LEVEL);
	}

	/**
	 * The plane is the LOCAL PLAYER's: nEntities exports no per-entity plane (the native reads one,
	 * ENTITY_PLANE_COORD in client/offsets.hpp, but does not send it), so an NPC on the floor above
	 * reports your floor. Documented
	 * limitation of the first cut, not a bug to paper over here.
	 */
	public WorldPoint getWorldLocation()
	{
		return WorldPoint.fromScene(WorldView.TOP_LEVEL, sceneX(), sceneY(), getWorldView().getPlane());
	}

	public WorldArea getWorldArea()
	{
		return new WorldArea(getWorldLocation(), size(), size());
	}

	public int getAnimation()
	{
		return animation();
	}

	/** No pose/idle animation offset yet. */
	public int getPoseAnimation()
	{
		gap("Actor.getPoseAnimation", "reads -1 (no animation): only the ACTIVE animation is exported"
			+ " by nEntities; the pose/idle sequence field has no offset. getAnimation() is live");
		return -1;
	}

	/** No spot-animation offset yet. */
	public int getGraphic()
	{
		gap("Actor.getGraphic", "reads -1 (no graphic): the spot-animation field has no offset, so an"
			+ " actor with a visible spell or effect graphic looks unadorned to a plugin");
		return -1;
	}

	/** 0..2047, 0 = south, clockwise (the client's own unit, same as upstream). */
	public int getOrientation()
	{
		return orientation();
	}

	public int getCurrentOrientation()
	{
		return orientation();
	}

	/** No interacting-target offset yet. */
	@Nullable
	public Actor getInteracting()
	{
		gap("Actor.getInteracting", "reads null (nobody): the interacting-index field has no offset, so"
			+ " no actor ever appears to be fighting or talking to anything");
		return null;
	}

	public boolean isInteracting()
	{
		gap("Actor.isInteracting", "reads false for every actor -- the same missing offset as"
			+ " getInteracting");
		return false;
	}

	/** -1 = no health bar shown, upstream's convention; no hitsplat/health-bar offsets yet. */
	public int getHealthRatio()
	{
		gap("Actor.getHealthRatio", "reads -1, upstream's \"no health bar showing\": the health-bar"
			+ " block has no offset, so a plugin can never tell a hurt actor from a full-health one");
		return -1;
	}

	public int getHealthScale()
	{
		gap("Actor.getHealthScale", "reads -1 -- the same missing health-bar block as getHealthRatio");
		return -1;
	}

	public boolean isDead()
	{
		gap("Actor.isDead", "reads false for every actor: death is inferred upstream from the health"
			+ " bar, which is not readable. NPC Indicators' \"ignore dead NPCs\" option therefore"
			+ " ignores nothing");
		return false;
	}

	@Nullable
	public String getOverheadText()
	{
		gap("Actor.getOverheadText", "reads null (nothing said): the overhead chat-text field has no"
			+ " offset");
		return null;
	}

	/** See {@link #logicalHeight()}: an approximation, not the model's height. */
	public int getLogicalHeight()
	{
		return logicalHeight;
	}

	/**
	 * The tile (or size x size footprint) outline under the actor, at its OWN ground height; null when
	 * off screen.
	 *
	 * <p>Centred on {@link #getLocalLocation()}, the RENDERED position -- upstream's shape, and what
	 * makes "highlight tile" and "highlight true tile" two different pictures: this square slides with
	 * the walk animation, the true tile snaps to the server tile. It used to project sceneX/sceneY,
	 * i.e. the server tile, which drew the identical square for both styles and left the tile lagging
	 * a step behind the hull of anything that was moving.</p>
	 */
	@Nullable
	public Polygon getCanvasTilePoly()
	{
		LocalPoint lp = getLocalLocation();
		return Perspective.centredTileAreaPoly(lp.getX(), lp.getY(), size(), height());
	}

	/**
	 * Upstream contract: x centred on the text, y at the projected point (baseline). The point is the
	 * feet position raised by {@code zOffset} fine units -- callers pass getLogicalHeight()+40 for a
	 * name tag -- projected at the actor's own ground height, not Game.groundHeightGuess().
	 */
	@Nullable
	public Point getCanvasTextLocation(Graphics2D graphics, String text, int zOffset)
	{
		java.awt.Point p = projectRaised(zOffset);
		if (p == null)
		{
			return null;
		}
		int width = text == null ? 0 : graphics.getFontMetrics().stringWidth(text);
		return new Point(p.x - width / 2, p.y);
	}

	/** Upstream contract: top-left of the image centred on the raised feet point. */
	@Nullable
	public Point getCanvasImageLocation(BufferedImage image, int zOffset)
	{
		java.awt.Point p = projectRaised(zOffset);
		if (p == null)
		{
			return null;
		}
		return new Point(p.x - image.getWidth() / 2, p.y - image.getHeight() / 2);
	}

	@Nullable
	public Point getMinimapLocation()
	{
		return Perspective.localToMinimap(Client.get(), getLocalLocation());
	}

	/**
	 * AN APPROXIMATION. There is no model or hull access on this build (no model offsets are
	 * derived), so this is the 2D convex hull of a prism standing on the actor's footprint: size x
	 * size tiles wide at its ground height, {@link #getLogicalHeight()} tall. Every corner goes
	 * through the game's own projection, so the shape rotates and foreshortens with the camera by
	 * construction. Null when fewer than three corners are on screen.
	 */
	@Nullable
	public Shape getConvexHull()
	{
		LocalPoint lp = getLocalLocation();
		return Perspective.approximateHull(lp.getX(), lp.getY(), height(), size(), getLogicalHeight());
	}

	/** The feet point lifted by {@code zOffset} along the height axis (negative = up), projected. */
	private java.awt.Point projectRaised(int zOffset)
	{
		LocalPoint lp = getLocalLocation();
		return Game.projectFine(lp.getX(), height() - zOffset, lp.getY());
	}
}
