// Shim of net.runelite.api.WorldView (BSD-2, RuneLite), cut to what the vendored coord classes and
// the ported plugin call.
//
// There is one world view here -- the loaded scene around the player, via oxclient.api.Game. Instance
// views (raids, boats) are not readable yet, so isInstance() is false and the template chunks are
// empty: WorldPointUtil.fromLocalInstance collapses to plain world coordinates.
package net.runelite.api;

import oxclient.api.Game;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;

public class WorldView
{
	public static final int TOPLEVEL = -1;

	private static final int[][][] NO_TEMPLATE_CHUNKS = new int[0][0][0];

	private final int id;

	WorldView(int id)
	{
		this.id = id;
	}

	static final WorldView TOP_LEVEL = new WorldView(TOPLEVEL);

	public int getId()
	{
		return id;
	}

	public int getBaseX()
	{
		return Game.sceneBaseX();
	}

	public int getBaseY()
	{
		return Game.sceneBaseY();
	}

	/** 0..3. The native reads the plane off a derived but not live-verified offset (ENTITY_PLANE_COORD,
	 *  see client/offsets.hpp) and range-guards it to -1 = unknown; this shim assumes the GROUND FLOOR for -1 rather than
	 *  passing it through. Why: the ported plugin packs the plane with {@code (plane & 0x3) << 30}
	 *  (WorldPointUtil.packWorldPoint), so a -1 silently became plane 3 for both the start and the
	 *  target tile -- a floor the collision map has no walkable data for -- while every overlay
	 *  plane-compare ({@code getPlane() != unpackWorldPlane(..)}) could never match -1: "Set target"
	 *  accepted, nothing computed, nothing drawn, no minimap marker. The earlier contract ("callers
	 *  must not treat -1 as a floor, so nothing draws at a wrong height") bought nothing here: with
	 *  no heightmap (Perspective.getTileHeight is 0) everything is drawn at datum height anyway.
	 *  Cost of the assumption: upstairs, with an unreadable plane, the path is computed from the
	 *  ground-floor tile under the player. {@code oxclient.rl.AutoWalk} reads {@code Game.me().plane()}
	 *  directly and keeps its own -1 handling (walks without the plane filter). NOT VERIFIED which
	 *  case fires live -- the strip's "you: x, y plane N" and the OXC_LOG [proj] line show it. */
	public int getPlane()
	{
		int p = Game.me().plane();
		return p < 0 ? 0 : p;
	}

	public int getSizeX()
	{
		return Constants.SCENE_SIZE;
	}

	public int getSizeY()
	{
		return Constants.SCENE_SIZE;
	}

	/**
	 * FALSE ALWAYS, and that is a LIE rather than a blank: nothing here can tell an instance from open
	 * world, so it answers "not an instance" in both cases.
	 *
	 * <p>Not derivable, and here is the reason rather than a shrug. An instance is signalled by two
	 * things in the client and neither is reachable: a flag on the world view, and a
	 * {@code [4][13][13]} array of TEMPLATE CHUNKS recording which real chunk each chunk of the scene
	 * was copied from. The flag alone would not help -- {@code WorldPointUtil.fromLocalInstance} needs
	 * the chunk table to translate WITH -- and neither can be inferred from what this client already
	 * reads: the scene base and the player position look exactly the same inside an instance as
	 * outside, which is precisely why the failure is silent.</p>
	 *
	 * <p>EXACT CONSEQUENCE. Inside a raid, a boss instance, the Inferno, a POH or a minigame arena,
	 * every WorldPoint the shim reports is the raw scene coordinate of the instance's TEMPLATE
	 * location, not where the player actually is. Shortest Path then pathfinds from a tile far from
	 * the player and drops the target marker there too. Outside instances -- the whole overworld --
	 * every coordinate is correct, which is why nothing has caught it.</p>
	 *
	 * <p>EXACT NEXT STEP. Two offsets, and the anchor for the deob is already in the tree: the native
	 * behind {@code oxclient.Natives.sceneBase()} reads this world view's base X/Y, and both the instance
	 * flag and the template-chunk array are fields of that SAME structure. Add them to
	 * {@code client/offsets.hpp}, expose the chunk array through a native next to {@code sceneBase},
	 * and this method plus {@link #getInstanceTemplateChunks()} become plain reads with no other
	 * change anywhere.</p>
	 */
	public boolean isInstance()
	{
		ShimSupport.note("WorldView.isInstance", ShimSupport.Kind.NEEDS_OFFSET,
			"reads false ALWAYS, inside an instance as well as out of one. In a raid, boss room,"
				+ " Inferno, POH or minigame arena that makes every world coordinate the shim reports"
				+ " the raw scene coordinate of the instance's TEMPLATE location -- wrong, not absent"
				+ " -- so Shortest Path pathfinds from a tile far from the player and drops the target"
				+ " marker there too. Outside instances every coordinate is correct, which is why it"
				+ " goes unnoticed. Two offsets close it and the anchor is already in the tree: the"
				+ " instance flag and the [4][13][13] template-chunk array are fields of the SAME world"
				+ " view structure oxclient.Natives.sceneBase() already reads base X/Y from. The flag alone"
				+ " is not enough -- WorldPointUtil.fromLocalInstance needs the chunk array to"
				+ " translate with");
		return false;
	}

	public int[][][] getInstanceTemplateChunks()
	{
		ShimSupport.note("WorldView.getInstanceTemplateChunks", ShimSupport.Kind.NEEDS_OFFSET,
			"reads an EMPTY chunk array, so there is nothing to translate instance coordinates WITH."
				+ " This is the half of the instance gap that actually matters: see"
				+ " WorldView.isInstance for the consequence and for where the offset lives");
		return NO_TEMPLATE_CHUNKS;
	}

	public boolean contains(WorldPoint point)
	{
		if (point == null)
		{
			return false;
		}
		return Game.toScene(point.getX(), point.getY()) != null;
	}

	/**
	 * Whether a local point is inside the loaded scene. This answered {@code point != null} until the
	 * 2026-09-06 audit, i.e. yes for a point a thousand tiles outside the scene -- the WorldPoint
	 * overload above has always been a real bounds check, and the two must agree. Nothing calls it
	 * yet, which is why the lie was harmless and also why it was worth fixing before something does.
	 */
	public boolean contains(LocalPoint point)
	{
		if (point == null)
		{
			return false;
		}
		int span = Constants.SCENE_SIZE << Perspective.LOCAL_COORD_BITS;
		return point.getX() >= 0 && point.getX() < span && point.getY() >= 0 && point.getY() < span;
	}

	/**
	 * The tile the mouse is over, computed rather than read: the game's selected-tile field sits
	 * behind the scene-click code that is not RE'd yet (the menu native, Phase D). The projection
	 * native only goes world->screen, so this walks the scene, projects every tile centre at ground
	 * height, and keeps the one nearest the cursor -- within a tile-sized radius, so a cursor over
	 * UI or the sky yields null rather than the closest tile anyway.
	 *
	 * <p>One bypass: while 0xClient's own right-click popup menu is open the cursor is over one of its
	 * rows, not over the scene, so the nearest-tile scan below would name the row's neighbour (rows
	 * sit 22 px apart) or nothing at all. For the frames {@code ClientState.isMenuOpen()} is true
	 * this returns the tile MenuPopup captured at the moment the right-click landed -- parked in the
	 * ClientState -- and the scan does not run.</p>
	 *
	 * <p>Honest degradations, both shared with the overlays: no heightmap (Perspective.getTileHeight
	 * is 0) so on slopes and upper floors the picked tile is the one whose ground projection is
	 * nearest, and the plane is the local player's, not the cursor's. Cached per frame because the
	 * plugin asks for it from both the menu-entry path and the click path.</p>
	 */
	public Tile getSelectedSceneTile()
	{
		ClientState state = Client.get().state();
		if (state.isMenuOpen())
		{
			return state.getMenuOpenedTile();
		}

		int frame = oxclient.OxClient.frame();
		if (cachedTileFrame == frame)
		{
			return cachedTile;
		}

		cachedTileFrame = frame;
		cachedTile = null;

		Point mouse = Client.get().state().getMouseCanvasPosition();
		if (mouse == null)
		{
			return null;
		}

		int plane = getPlane();
		int size = Constants.SCENE_SIZE;
		Tile best = null;
		long bestDist = Long.MAX_VALUE;
		for (int x = 0; x < size; x++)
		{
			for (int y = 0; y < size; y++)
			{
				java.awt.Point p = Game.projectTile(x, y);
				if (p == null)
				{
					continue;
				}
				long dx = p.x - mouse.getX();
				long dy = p.y - mouse.getY();
				long dist = dx * dx + dy * dy;
				if (dist < bestDist)
				{
					bestDist = dist;
					best = new Tile(x, y, plane);
				}
			}
		}

		// Tiles project 30-100 px apart depending on zoom; half a max tile on screen is generous
		// without letting a cursor parked on the minimap pick a scene tile.
		if (best != null && bestDist <= 100L * 100L)
		{
			cachedTile = best;
		}
		return cachedTile;
	}

	/** Frame token and result of the last tile-under-cursor scan; 0xClient's frame counter, not game state. */
	private int cachedTileFrame = -1;
	private Tile cachedTile;

	/** Waiting on: boat/passed-world entities. Empty means "no boat offset", paths walk from the shore.
	 *  The gap is registered by {@link WorldEntities#byIndex}, where a caller actually feels it. */
	public WorldEntities worldEntities()
	{
		return WorldEntities.EMPTY;
	}

	/**
	 * Collision flags per tile. Not readable from the game yet; zeros mean "no walls", which is the
	 * honest default: everything looks walkable to WorldArea's line-of-sight helpers, and real
	 * walkability comes from the plugin's own bundled collision map instead.
	 */
	public CollisionData[] getCollisionMaps()
	{
		ShimSupport.note("WorldView.getCollisionMaps", ShimSupport.Kind.NEEDS_OFFSET,
			"reads ALL-ZERO flags: the scene collision map has no offset, so every tile looks open to"
				+ " WorldArea's line-of-sight helpers. Real walkability comes from the ported"
				+ " plugin's own bundled collision map instead, which is why pathing still works");
		return CollisionData.EMPTY;
	}

	public Scene getScene()
	{
		return Scene.INSTANCE;
	}

	// -- actors: views over the ActorTable's per-frame lists (see Client.getNpcs) ---------------------

	public IndexedObjectSet<? extends NPC> npcs()
	{
		ActorTable.refresh(oxclient.OxClient.frame());
		return new IndexedObjectSet<>(ActorTable.npcs(), ActorTable::npcByUid);
	}

	/** Includes the local player, as upstream. */
	public IndexedObjectSet<? extends Player> players()
	{
		ActorTable.refresh(oxclient.OxClient.frame());
		return new IndexedObjectSet<>(ActorTable.players(), ActorTable::playerByUid);
	}
}
