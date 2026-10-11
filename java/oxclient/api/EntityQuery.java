package oxclient.api;

import java.util.List;
import java.util.function.Supplier;

/**
 * Finding NPCs or players: {@link Npcs#query()}, {@link Players#query()}. Everything a {@link WorldQuery}
 * does, plus what only an actor can answer.
 *
 * <pre>{@code
 * Entity target = Npcs.query().named("Goblin").withOption("Attack").free().within(10).nearest();
 * boolean attacked = Npcs.query().interactingWithMe().exists();
 * }</pre>
 */
public final class EntityQuery extends WorldQuery<Entity, EntityQuery> {

    EntityQuery(Supplier<List<Entity>> source) { super(source); }

    /** Keep only those standing still (not animating). */
    public EntityQuery idle() { return where(Entity::isIdle); }

    /** Keep only those not fighting or talking to someone else -- the ones you can take on without stealing. */
    public EntityQuery free() { return where(e -> !e.busyWithOther()); }

    /** Keep only those whose target is you: attacking you, or talking to you (a random event does). */
    public EntityQuery interactingWithMe() {
        return where(e -> { int me = Game.me().uid(); return me >= 0 && e.target() == me; });
    }
}
