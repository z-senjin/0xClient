// Shim of net.runelite.api.Player (BSD-2, RuneLite), an Actor with two backings:
//
//  - the LOCAL player reads oxclient.api.Local through a Supplier (oxclient.api.Game.refresh() REPLACES its
//    Local each frame, so a Player capturing one Local would be a frozen snapshot of the player before
//    the game ever refreshed -- every caller would see tile (0,0) forever);
//  - OTHER players read a oxclient.api.Entity snapshot that ActorTable re-points each frame, exactly like
//    NPC, so one Player object per handle survives across ticks for reference comparisons.
package net.runelite.api;

import java.util.function.Supplier;

import javax.annotation.Nullable;

import oxclient.api.Entity;
import oxclient.api.Local;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;

public class Player extends Actor
{
	/** Non-null for the local player; null for others. */
	private final Supplier<Local> local;
	/** Others only: this frame's snapshot (kept after despawn). Written by ActorTable. */
	volatile Entity last;
	volatile boolean present;
	private final int uid;

	Player(Local local)
	{
		this(() -> local);
	}

	Player(Supplier<Local> local)
	{
		this.local = local;
		this.last = null;
		this.uid = -1;
	}

	/** Another player, by handle; ActorTable is the only caller. */
	Player(int uid, Entity first)
	{
		this.local = null;
		this.uid = uid;
		this.last = first;
		this.present = true;
	}

	private Local now()
	{
		Local l = local.get();
		return l == null ? Local.ABSENT : l;
	}

	boolean isLocal()
	{
		return local != null;
	}

	/**
	 * Another player's level comes from the game (nEntities carries it in the id slot, read off the
	 * client's appearance decoder -- PLAYER_COMBAT_LEVEL in client/offsets.hpp, derived on client-241-3,
	 * not verified live); yours is computed from your stats. Anything outside 3..126 is not a level and
	 * reads 0, upstream's "no combat level".
	 */
	@Override
	public int getCombatLevel()
	{
		Entity e = last;
		int level = isLocal() ? oxclient.api.Skills.combatLevel() : (e == null ? -1 : e.id());
		return level >= 3 && level <= 126 ? level : 0;
	}

	// -- primitives ---------------------------------------------------------------------------------------

	@Override
	int uid()
	{
		return isLocal() ? now().uid() : uid;
	}

	@Override
	boolean present()
	{
		return isLocal() ? now().exists() : present;
	}

	@Override
	int sceneX()
	{
		return isLocal() ? now().sceneX() : last.sceneX();
	}

	@Override
	int sceneY()
	{
		return isLocal() ? now().sceneY() : last.sceneY();
	}

	@Override
	int fineX()
	{
		return isLocal() ? now().fineX() : last.fineX();
	}

	@Override
	int fineY()
	{
		return isLocal() ? now().fineY() : last.fineY();
	}

	@Override
	int height()
	{
		return isLocal() ? now().height() : last.height();
	}

	@Override
	int animation()
	{
		return isLocal() ? now().animation() : last.animation();
	}

	@Override
	int orientation()
	{
		return isLocal() ? now().orientation() : last.orientation();
	}

	@Override
	String rawName()
	{
		if (!isLocal())
		{
			// Other players: Natives.entityName(uid, true) via Entity.name(); NOT YET CONFIRMED LIVE,
			// which is what TestActors' player name tags are for.
			return ActorTable.nameOf(this, last);
		}
		// Live as of client-240-6: the local player's heap NxtString at entity+0x718. May contain
		// U+00A0 where the game pads; Text.removeNames comparisons should fold that to a space.
		// `true`: the local player's uid is a PLAYER handle and must resolve in the player table.
		// Same backoff as everyone else: the native walks the registry, and this is called per frame.
		Local l = now();
		if (!l.exists())
		{
			// The cached name is dropped by ActorTable.refreshFrom() on every frame the local player is
			// absent, not here: this only runs when someone asks, and a logout with nobody asking must
			// still forget the previous account's name before a relog.
			return "";
		}
		return ActorTable.nameOf(this, () -> oxclient.Natives.entityName(l.uid(), true));
	}

	// -- upstream surface ------------------------------------------------------------------------------

	/**
	 * The fine render position, as upstream (interpolated between tiles while walking); tile centre
	 * when the fine position is unread. Switched from the tile centre on 2026-09-05 so a line drawn
	 * from the player glides with the model -- revert by overriding here with
	 * {@code new LocalPoint((sceneX() << 7) + 64, (sceneY() << 7) + 64)} if that misbehaves live.
	 */
	@Override
	public LocalPoint getLocalLocation()
	{
		return super.getLocalLocation();
	}

	@Override
	public WorldPoint getWorldLocation()
	{
		if (isLocal())
		{
			Local l = now();
			// Plane via the WorldView, not Local.plane(): the view normalises the native's -1 (unknown)
			// to the ground floor (WorldView.getPlane says why), and a WorldPoint carrying -1 would be
			// packed as plane 3 by the ported plugin's WorldPointUtil.
			return new WorldPoint(l.worldX(), l.worldY(), getWorldView().getPlane());
		}
		return super.getWorldLocation();
	}

	/** The handle, standing in for upstream's player-array index (see NPC.getIndex). */
	public int getId()
	{
		return uid();
	}

	/** No appearance/equipment offsets yet. */
	@Nullable
	public Object getPlayerComposition()
	{
		gap("Player.getPlayerComposition", "reads null: no appearance or equipment offsets, so nothing"
			+ " can be told about what a player is wearing or which gender model they use");
		return null;
	}

	/** No skull-icon offset yet. */
	@Nullable
	public Object getSkullIcon()
	{
		gap("Player.getSkullIcon", "reads null (no skull): the skull-icon field has no offset, so a"
			+ " skulled player in the wilderness is indistinguishable from an unskulled one");
		return null;
	}

	/** No prayer-icon offset yet. */
	@Nullable
	public Object getOverheadIcon()
	{
		gap("Player.getOverheadIcon", "reads null (no overhead prayer): the prayer-icon field has no"
			+ " offset");
		return null;
	}

	/** No team-cape offset yet. */
	public int getTeam()
	{
		gap("Player.getTeam", "reads 0, upstream's \"no team cape\", for every player: the team field"
			+ " has no offset, so team-cape colouring can never fire");
		return 0;
	}

	/**
	 * Friends, friends-chat and clan membership all read FALSE. Player Indicators' per-category
	 * colours therefore collapse: everyone who is not you is drawn as "other".
	 */
	public boolean isFriend()
	{
		gap("Player.isFriend", "reads false for everyone: the friends list lives in a client structure"
			+ " with no derived offset. Player Indicators cannot colour friends differently, so they"
			+ " are drawn with the \"other players\" colour");
		return false;
	}

	public boolean isFriendsChatMember()
	{
		gap("Player.isFriendsChatMember", "reads false for everyone -- the friends-chat member list"
			+ " has no derived offset");
		return false;
	}

	public boolean isClanMember()
	{
		gap("Player.isClanMember", "reads false for everyone -- the clan member list has no derived"
			+ " offset");
		return false;
	}

	private static void gap(String accessor, String reason)
	{
		ShimSupport.note(accessor, ShimSupport.Kind.NEEDS_OFFSET, reason);
	}

	Local local()
	{
		return isLocal() ? now() : Local.ABSENT;
	}

	@Override
	public String toString()
	{
		return (isLocal() ? "player(me)" : "player") + " uid=" + uid() + " @" + getWorldLocation().getX()
			+ "," + getWorldLocation().getY() + (present() ? "" : " (despawned)");
	}

	static final Player ABSENT = new Player(Local.ABSENT)
	{
		@Override
		public LocalPoint getLocalLocation()
		{
			return new LocalPoint(0, 0);
		}

		@Override
		public WorldPoint getWorldLocation()
		{
			return new WorldPoint(0, 0, 0);
		}
	};
}
