package oxclient.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * A filter over things you can interact with, built by chaining and finished with one terminal call:
 *
 * <pre>{@code
 * Item food = Inventory.query().withOption("Eat").first();
 * int logs  = Inventory.query().named("Logs").count();
 * }</pre>
 *
 * <p>Filters only narrow; nothing is read from the game until a terminal call ({@link #list}, {@link #first},
 * {@link #count}, {@link #exists}) runs, and each terminal call reads afresh -- so a query kept in a field
 * answers for the frame it is asked on, never a stale one. Things in the world have a richer version,
 * {@link WorldQuery}, that adds distance.</p>
 *
 * @param <T> what is being found
 * @param <Q> the query's own type, so chained calls keep the specific query's extra filters
 */
public abstract class Query<T extends Interactable, Q extends Query<T, Q>> {

    private final Supplier<List<T>> source;
    private final List<Predicate<? super T>> filters = new ArrayList<>();

    protected Query(Supplier<List<T>> source) { this.source = source; }

    @SuppressWarnings("unchecked")
    protected final Q self() { return (Q) this; }

    /** Keep only those {@code test} accepts. */
    public Q where(Predicate<? super T> test) {
        if (test != null) filters.add(test);
        return self();
    }

    /** Keep only those named exactly one of {@code names}, ignoring case. */
    public Q named(String... names) {
        return where(t -> {
            String n = t.name();
            for (String want : names) if (want != null && want.equalsIgnoreCase(n)) return true;
            return false;
        });
    }

    /** Keep only those whose name contains {@code part}, ignoring case ("ore" finds "Copper ore" and "Tin ore"). */
    public Q nameContains(String part) {
        String p = part == null ? "" : part.toLowerCase();
        return where(t -> t.name().toLowerCase().contains(p));
    }

    /** Keep only those whose id is one of {@code ids}. */
    public Q withId(int... ids) {
        return where(t -> {
            for (int id : ids) if (t.id() == id) return true;
            return false;
        });
    }

    /** Keep only those with an option named {@code option} ("Attack", "Chop down", "Eat"), ignoring case. */
    public Q withOption(String option) { return where(t -> t.hasOption(option)); }

    /** Everything that passes every filter, in the game's order. */
    public List<T> list() {
        List<T> all = source.get();
        if (all == null || all.isEmpty()) return Collections.emptyList();
        List<T> out = new ArrayList<>();
        for (T t : all) if (accepts(t)) out.add(t);
        return out;
    }

    /** The first match in the game's order, or null. */
    public T first() {
        List<T> all = source.get();
        if (all != null) for (T t : all) if (accepts(t)) return t;
        return null;
    }

    /** How many match. */
    public int count() { return list().size(); }

    /** True when at least one matches. */
    public boolean exists() { return first() != null; }

    protected final boolean accepts(T t) {
        if (t == null) return false;
        for (Predicate<? super T> f : filters) if (!f.test(t)) return false;
        return true;
    }
}
