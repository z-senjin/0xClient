// ActorTable's identity contract, off the list-taking core so no native is touched: the same uid
// across frames is the same NPC object (plugins hold it between NpcSpawned and NpcDespawned), a
// vanished uid is gone and reads present()==false, a reused uid with a new type id is a new object,
// a repeated refresh for one frame is a no-op, and the name lookup backs off while it reads "".
package net.runelite.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import oxclient.api.Entity;
import oxclient.api.EntityTestSupport;

public class ActorTableTest
{
	@Before
	public void reset()
	{
		ActorTable.reset();
		ActorTable.nameLookup = Entity::name;
	}

	@After
	public void restore()
	{
		ActorTable.reset();
		ActorTable.nameLookup = Entity::name;
	}

	@Test
	public void sameUidAcrossFramesIsTheSameObject()
	{
		ActorTable.refreshFrom(List.of(EntityTestSupport.npc(7, 100, 10, 10)), List.of(), false, 1);
		NPC first = ActorTable.npcs().get(0);
		ActorTable.refreshFrom(List.of(EntityTestSupport.npc(7, 100, 11, 10)), List.of(), false, 2);
		NPC second = ActorTable.npcs().get(0);

		assertSame(first, second);
		assertEquals(11, second.getWorldLocation().getX());   // re-pointed at this frame's snapshot
		assertTrue(second.present());
		assertSame(first, ActorTable.npcByUid(7));
	}

	@Test
	public void vanishedUidIsGoneAndReadsNotPresent()
	{
		ActorTable.refreshFrom(List.of(EntityTestSupport.npc(7, 100, 10, 10)), List.of(), false, 1);
		NPC n = ActorTable.npcs().get(0);
		ActorTable.refreshFrom(List.of(), List.of(), false, 2);

		assertFalse(n.present());
		assertTrue(ActorTable.npcs().isEmpty());
		assertNull(ActorTable.npcByUid(7));
		// The last snapshot stays readable for a plugin still holding the object.
		assertEquals(10, n.getWorldLocation().getX());
	}

	@Test
	public void reusedUidWithDifferentIdIsANewObject()
	{
		ActorTable.refreshFrom(List.of(EntityTestSupport.npc(7, 100, 10, 10)), List.of(), false, 1);
		NPC old = ActorTable.npcs().get(0);
		ActorTable.refreshFrom(List.of(EntityTestSupport.npc(7, 200, 10, 10)), List.of(), false, 2);
		NPC fresh = ActorTable.npcs().get(0);

		assertNotSame(old, fresh);
		assertFalse(old.present());
		assertTrue(fresh.present());
		assertEquals(200, fresh.getId());
	}

	@Test
	public void refreshIsIdempotentPerFrame()
	{
		ActorTable.refreshFrom(List.of(EntityTestSupport.npc(7, 100, 10, 10)), List.of(), false, 5);
		NPC n = ActorTable.npcs().get(0);
		// A second call for the same frame token must not rebuild -- even with a different list.
		ActorTable.refreshFrom(List.of(), List.of(), false, 5);
		assertSame(n, ActorTable.npcByUid(7));
		assertEquals(1, ActorTable.npcs().size());
	}

	@Test
	public void playersAndNpcsAreSeparateKeyspaces()
	{
		ActorTable.refreshFrom(List.of(EntityTestSupport.npc(7, 100, 10, 10)),
			List.of(EntityTestSupport.player(7, 12, 12)), false, 1);
		assertEquals(1, ActorTable.npcs().size());
		assertEquals(1, ActorTable.players().size());
		assertEquals(7, ActorTable.npcs().get(0).getIndex());
		assertEquals(7, ActorTable.players().get(0).getId());
	}

	@Test
	public void localPlayerHeadsThePlayerListWhenPresent()
	{
		ActorTable.refreshFrom(List.of(), List.of(EntityTestSupport.player(9, 12, 12)), true, 1);
		assertEquals(2, ActorTable.players().size());
		assertSame(Client.get().localPlayer(), ActorTable.players().get(0));
		assertEquals(9, ActorTable.players().get(1).getId());

		ActorTable.refreshFrom(List.of(), List.of(EntityTestSupport.player(9, 12, 12)), false, 2);
		assertEquals(1, ActorTable.players().size());
	}

	@Test
	public void localPlayerNameIsForgottenOnEveryFrameItIsAbsent()
	{
		Player local = Client.get().localPlayer();
		local.name = "Previous Account";
		local.nameRetryFrame = 7;
		// Present: the cache is left alone (a non-empty name sticks while on screen).
		ActorTable.refreshFrom(List.of(), List.of(), true, 1);
		assertEquals("Previous Account", local.name);
		// Absent (logout): forgotten without anyone calling rawName(), so a relog on another account
		// cannot be served the old name.
		ActorTable.refreshFrom(List.of(), List.of(), false, 2);
		assertEquals("", local.name);
		assertEquals(Integer.MIN_VALUE, local.nameRetryFrame);
	}

	@Test
	public void emptyNameIsRetriedOnlyAfterTheBackoff()
	{
		AtomicInteger calls = new AtomicInteger();
		ActorTable.nameLookup = e -> {
			calls.incrementAndGet();
			return "";
		};
		Entity e = EntityTestSupport.npc(7, 100, 10, 10);
		ActorTable.refreshFrom(List.of(e), List.of(), false, 0);
		NPC n = ActorTable.npcs().get(0);

		assertEquals("", n.getName());
		assertEquals("", n.getName());
		assertEquals("", n.getName());
		assertEquals("one lookup per backoff window", 1, calls.get());

		ActorTable.refreshFrom(List.of(e), List.of(), false, ActorTable.NAME_RETRY_FRAMES - 1);
		assertEquals("", n.getName());
		assertEquals(1, calls.get());

		ActorTable.refreshFrom(List.of(e), List.of(), false, ActorTable.NAME_RETRY_FRAMES);
		assertEquals("", n.getName());
		assertEquals("retried once the window elapsed", 2, calls.get());
	}

	@Test
	public void nonEmptyNameSticksWithoutFurtherLookups()
	{
		AtomicInteger calls = new AtomicInteger();
		ActorTable.nameLookup = e -> {
			calls.incrementAndGet();
			return "Guard";
		};
		Entity e = EntityTestSupport.npc(7, 100, 10, 10);
		ActorTable.refreshFrom(List.of(e), List.of(), false, 0);
		NPC n = ActorTable.npcs().get(0);
		assertEquals("Guard", n.getName());
		ActorTable.refreshFrom(List.of(e), List.of(), false, 500);
		assertEquals("Guard", n.getName());
		assertEquals("Guard", n.getComposition().getName());
		assertEquals(1, calls.get());
	}

	@Test
	public void localLocationIsTheFinePositionWithTileCentreFallback()
	{
		Entity walking = EntityTestSupport.of(7, 10, 10, false, 100, -1, 0, 1300, -312, 1350);
		Entity unread = EntityTestSupport.of(8, 20, 21, false, 100, -1, 0, 0, 0, 0);
		ActorTable.refreshFrom(List.of(walking, unread), List.of(), false, 1);
		NPC a = ActorTable.npcByUid(7);
		NPC b = ActorTable.npcByUid(8);
		assertEquals(1300, a.getLocalLocation().getX());
		assertEquals(1350, a.getLocalLocation().getY());
		assertEquals((20 << 7) + 64, b.getLocalLocation().getX());
		assertEquals((21 << 7) + 64, b.getLocalLocation().getY());
		assertEquals(0, a.getCombatLevel());
		assertEquals(1, a.getComposition().getSize());
	}

	@Test
	public void anotherPlayersCombatLevelIsTheIdSlotWhenItIsALevel()
	{
		Entity real = EntityTestSupport.of(11, 30, 30, true, 87, -1, 0, 0, 0, 0);
		Entity unread = EntityTestSupport.of(12, 31, 31, true, -1, -1, 0, 0, 0, 0);
		Entity garbage = EntityTestSupport.of(13, 32, 32, true, 4096, -1, 0, 0, 0, 0);
		ActorTable.refreshFrom(List.of(), List.of(real, unread, garbage), false, 1);
		assertEquals(87, ActorTable.playerByUid(11).getCombatLevel());
		assertEquals(0, ActorTable.playerByUid(12).getCombatLevel());
		assertEquals(0, ActorTable.playerByUid(13).getCombatLevel());
	}
}
