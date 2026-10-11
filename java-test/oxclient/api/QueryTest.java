// Query and WorldQuery are pure filtering over a supplier, so every filter and terminal can be pinned without
// a game: a fake Interactable stands in for NPCs, scenery and items. Interactable's by-name option lookup is
// tested here too, since every interact("...") in the API goes through it.
package oxclient.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import org.junit.Test;

public class QueryTest
{
	/** A thing with a name, options, a place and a distance -- and a record of what was clicked on it. */
	static final class Fake implements Interactable, Located
	{
		final int id, distance;
		final String name;
		final String[] options;
		int clicked = -1;

		Fake(int id, String name, int distance, String... options)
		{
			this.id = id;
			this.name = name;
			this.distance = distance;
			String[] five = {"", "", "", "", ""};
			System.arraycopy(options, 0, five, 0, Math.min(5, options.length));
			this.options = five;
		}

		@Override public int id() { return id; }
		@Override public String name() { return name; }
		@Override public String[] options() { return options.clone(); }
		@Override public boolean interact(int option) { clicked = option; return true; }
		@Override public WorldPoint location() { return new WorldPoint(3200 + distance, 3200); }
		@Override public int distance() { return distance; }
	}

	static final class FakeQuery extends WorldQuery<Fake, FakeQuery>
	{
		FakeQuery(Supplier<List<Fake>> source) { super(source); }
	}

	private final Fake goblin = new Fake(3029, "Goblin", 6, "", "Attack");
	private final Fake cow = new Fake(2790, "Cow", 2, "", "Attack");
	private final Fake banker = new Fake(1613, "Banker", 4, "Talk-to", "Bank");
	private final Fake farGoblin = new Fake(3029, "Goblin", 15, "", "Attack");

	private FakeQuery query(Fake... things)
	{
		List<Fake> l = List.of(things);
		return new FakeQuery(() -> l);
	}

	@Test
	public void namedMatchesAnyOfTheNamesIgnoringCase()
	{
		List<Fake> found = query(goblin, cow, banker).named("goblin", "COW").list();
		assertEquals(List.of(goblin, cow), found);
	}

	@Test
	public void nameContainsIsCaseInsensitive()
	{
		assertEquals(List.of(banker), query(goblin, banker).nameContains("BANK").list());
	}

	@Test
	public void withIdAndWithOptionFilter()
	{
		assertEquals(List.of(goblin, farGoblin), query(goblin, cow, farGoblin).withId(3029).list());
		assertEquals(List.of(banker), query(goblin, cow, banker).withOption("bank").list());
	}

	@Test
	public void filtersCombineAsAnd()
	{
		assertEquals(List.of(goblin), query(goblin, cow, farGoblin).named("Goblin").within(10).list());
	}

	@Test
	public void nearestIsTheSmallestDistanceAndNullWhenNothingMatches()
	{
		assertSame(cow, query(goblin, cow, banker).withOption("Attack").nearest());
		assertNull(query(goblin, cow).named("Dragon").nearest());
		assertNull(query().nearest());
	}

	@Test
	public void sortedByDistanceIsClosestFirst()
	{
		assertEquals(List.of(cow, banker, goblin, farGoblin), query(farGoblin, goblin, cow, banker).sortedByDistance());
	}

	@Test
	public void firstKeepsTheGamesOrder()
	{
		assertSame(goblin, query(goblin, cow).withOption("Attack").first());
	}

	@Test
	public void countAndExists()
	{
		assertEquals(2, query(goblin, cow, banker).withOption("Attack").count());
		assertTrue(query(goblin).exists());
		assertFalse(query(goblin).named("Cow").exists());
	}

	@Test
	public void everyTerminalReadsTheSourceAfresh()
	{
		List<Fake> live = new ArrayList<>();
		FakeQuery q = new FakeQuery(() -> live).named("Cow");
		assertFalse(q.exists());
		live.add(cow);
		assertSame(cow, q.first());
	}

	@Test
	public void interactByNameTakesThatOptionsPosition()
	{
		Fake g = new Fake(1, "Goblin", 1, "", "Attack");
		assertTrue(g.interact("attack"));
		assertEquals(2, g.clicked);
	}

	@Test
	public void interactByAnUnknownNameClicksNothing()
	{
		Fake g = new Fake(1, "Goblin", 1, "", "Attack");
		assertFalse(g.interact("Pickpocket"));
		assertFalse(g.interact((String) null));
		assertEquals(-1, g.clicked);
	}

	@Test
	public void hasOptionAndIsNamed()
	{
		assertTrue(banker.hasOption("talk-to"));
		assertFalse(banker.hasOption("Attack"));
		assertTrue(banker.isNamed("banker"));
	}
}
