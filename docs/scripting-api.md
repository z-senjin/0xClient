# Writing scripts: the API in one page

Everything a script needs lives in `oxclient.api`. This page is the map; each class's javadoc (in the wiki)
has the detail and says how sure we are of the offsets underneath it.

## The model

A script is a `Plugin` whose `tick()` runs every frame. Three rules make it work:

1. **The world is a snapshot.** NPCs, players and you are read once at the top of the frame
   (`Game.refresh()`), so everything you look at in one tick agrees with everything else.
2. **A tick never blocks.** Decide one thing, do it, return. No `Thread.sleep`, no loops waiting for the game.
3. **An action is a request.** `interact(...)` returns true when the client accepted the click, not when it
   has happened. Confirm the effect on a later tick with `Await`.

## Reading the game

| You want | Call |
|---|---|
| Am I in the world? | `Game.ready()` |
| Me: where, animating, facing, health, run | `Game.me()` → `location()`, `isIdle()`, `animation()`, `health()`, `healthPercent()`, `runEnergy()`, `plane()` |
| Levels and xp | `Skills.level(Skill.MINING)`, `Skills.effective(...)`, `Skills.experience(...)`, `Skills.combatLevel()` |
| What I carry | `Inventory.items()`, `Inventory.isFull()`, `Inventory.free()`, `Inventory.contains("Logs")`, `Inventory.amount("Coins")` |
| What I wear | `Equipment.worn(Equipment.WEAPON)`, `Equipment.name(slot)` |
| Is something attacking me? | `Combat.underAttack()`, `Npcs.query().interactingWithMe().exists()` |
| Bank | `Bank.isOpen()`, `Bank.contains("Lobster")`, `Bank.count("Lobster")` |
| Dialogue | `Dialog.canContinue()`, `Dialog.options()` |
| Game messages | `Chat.poll()` (new lines since last call), `Chat.contains("You can't")` |
| Varps / varbits | `Varbits.varp(id)`, `Varbits.get(id)` |

## Finding things: queries

NPCs, players, scenery, ground items and inventory items are all found the same way: start a query, narrow
it, finish it.

```java
Entity goblin   = Npcs.query().named("Goblin").withOption("Attack").free().within(10).nearest();
SceneObject oak = Scenery.query().named("Oak tree").nearest();
SceneObject bank = Scenery.query().withOption("Bank").nearest();
GroundItem bones = GroundItems.query().named("Bones").within(6).nearest();
Item food        = Inventory.query().withOption("Eat").first();
int logs         = Inventory.query().named("Logs").count();
```

| Filter | On |
|---|---|
| `named("A", "B")`, `nameContains("ore")`, `withId(...)`, `withOption("Attack")`, `where(x -> ...)` | everything |
| `within(tiles)`, `within(centre, tiles)`, `at(tile)` | things in the world |
| `idle()`, `free()` (not fighting someone else), `interactingWithMe()` | NPCs and players |
| `walls()`, `objects()` | scenery |
| `minQuantity(n)` | ground items, inventory items |

| Finish with | Gives |
|---|---|
| `nearest()` | the closest, or null (things in the world) |
| `first()` | the first in the game's order, or null |
| `list()`, `sortedByDistance()` | all of them |
| `count()`, `exists()` | how many / any |
| `amount()` | total stack size (inventory) |

Shortcuts for the common case: `Npcs.nearest("Cow")`, `Scenery.nearest("Tree")`, `Inventory.first("Logs")`.

## Doing things: `interact`

Anything with a right-click menu is an `Interactable` and takes options **by name**:

```java
goblin.interact("Attack");
oak.interact("Chop down");
bank.interact("Bank");
bones.interact("Take");
food.interact("Eat");
logs.drop();
tinderbox.useOn(logs);      // item on item
fish.useOn(range);          // item on scenery
```

A name the thing does not have returns false; nothing else is clicked. Numbered options exist too
(`interact(2)`), always as the position in the menu, 1..5.

| Doing | Call |
|---|---|
| Walk somewhere on screen | `Actions.walkTo(point)` |
| Walk anywhere (route planning, doors, tolls) | `new Walker.Route(x, y, arrive)` then `route.step()` each tick |
| Bank | `Bank.depositInventory()`, `Bank.depositAll("Logs")`, `Bank.withdrawAll("Lobster")`, `Bank.close()` |
| Dialogue | `Dialog.continueDialog()`, `Dialog.choose("Yes")` |
| Run | `Run.keepOn()` |

## Confirming it happened: `Await`

```java
private final Await await = new Await();

@Override
public void tick() {
    switch (await.poll()) {
        case WAITING:   return;                                 // the last action is still landing
        case TIMED_OUT: status = "the click did nothing"; break; // decide again
        default:        break;
    }
    // ... decide, act, then:
    if (oak.interact("Chop down")) await.until(Await.animating(), 5000);
}
```

Ready-made conditions: `Await.animating()`, `Await.idle()`, `Await.inventoryChanged()`, `Await.gone(object)`,
`Await.near(point, tiles)`, `Await.bankOpen()`, `Await.dialog()`. Any `() -> boolean` works.

## A complete script

```java
public final class OakChopper extends Plugin {
    private final Await await = new Await();
    private String status = "";

    @Override public String name() { return "Oak chopper"; }
    @Override public String status() { return status; }

    @Override
    public void tick() {
        if (!Game.ready()) return;
        switch (await.poll()) {
            case WAITING: return;
            case TIMED_OUT: status = "nothing happened; trying again"; break;
            default: break;
        }
        if (!Game.me().isIdle()) { status = "chopping"; return; }

        if (Inventory.isFull()) {
            Item log = Inventory.first("Oak logs");
            if (log != null && log.drop()) await.until(Await.inventoryChanged(), 1500);
            status = "dropping";
            return;
        }

        SceneObject oak = Scenery.query().named("Oak tree").withOption("Chop down").nearest();
        if (oak == null) { status = "no oak in sight"; return; }
        if (oak.interact("Chop down")) await.until(Await.animating(), 6000);
        status = "walking to an oak";
    }
}
```

For longer jobs ("train to 30, bank, walk elsewhere"), the task framework in `oxclient.framework` (`Script`,
`Task`, `BaseTask`) runs a list of these decisions in order and remembers where it got to.

## How sure is each part?

The offsets underneath every call are listed in `client/offsets.hpp` and `offsets/client-<build>.json` with
how each was found. As of client-241-3: chopping scenery, dropping items, reading the inventory, skills, NPC
targets and names were seen working in game; bank options were captured from real clicks; ground items, walls,
use-item-on and most inventory options are derived but not yet seen working. When a call returns false
unexpectedly, the DLL's log (`OXC_LOG`) has an `[actions]` line for everything it sent or dropped.
