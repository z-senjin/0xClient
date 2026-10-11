package oxclient.api;

import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;

/**
 * A {@link Query} over things in the world, which adds where they are:
 *
 * <pre>{@code
 * Entity cow  = Npcs.query().named("Cow").withOption("Attack").free().within(12).nearest();
 * SceneObject tree = Scenery.query().named("Oak tree").nearest();
 * GroundItem bones = GroundItems.query().named("Bones").within(5).nearest();
 * }</pre>
 *
 * <p>"Nearest" is by tile distance from you, not by screen distance -- the thing behind you on screen may be
 * the one you can reach first.</p>
 */
public class WorldQuery<T extends Interactable & Located, Q extends WorldQuery<T, Q>> extends Query<T, Q> {

    protected WorldQuery(Supplier<List<T>> source) { super(source); }

    /** Keep only those within {@code tiles} of you. */
    public Q within(int tiles) { return where(t -> t.distance() <= tiles); }

    /** Keep only those within {@code tiles} of {@code centre} -- an area you train in, not where you stand. */
    public Q within(WorldPoint centre, int tiles) { return where(t -> centre != null && t.location().within(centre, tiles)); }

    /** Keep only those standing exactly on {@code tile}. */
    public Q at(WorldPoint tile) { return where(t -> t.location().equals(tile)); }

    /** The closest match to you, or null when there is none. */
    public T nearest() {
        T best = null;
        int bestD = Integer.MAX_VALUE;
        for (T t : list()) {
            int d = t.distance();
            if (d < bestD) { bestD = d; best = t; }
        }
        return best;
    }

    /** Every match, closest first. */
    public List<T> sortedByDistance() {
        List<T> l = new java.util.ArrayList<>(list());
        l.sort(Comparator.comparingInt(Located::distance));
        return l;
    }
}
