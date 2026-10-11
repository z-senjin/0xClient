package oxclient.framework;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * What the {@link Picker} shares with its goals while choosing and building tasks: the shopping list, the
 * settings, and what it has learned this session -- spots that just failed, ways of training whose items the
 * bank turned out not to have.
 *
 * <p>Both memories expire after {@link Picker#PARK_MS}: a failure is a reason to try something else for a
 * while, not forever.</p>
 */
public final class Context {

    /** What the account lacks and would buy. */
    public final ShoppingList wants = new ShoppingList();

    final Random rng = new Random();

    /** Bank what is gathered (true) or drop it. */
    boolean bank = true;

    /** Combat ran out of food and the bank has none: fights that need food are not offered until it does. */
    boolean needFood;

    /** Quests are offered (their data is not verified in game yet, so this is a setting). */
    boolean quests;

    /** The label of the task last handed out, so a "missing items" failure is charged to the right method. */
    String lastLabel = "";

    private final Map<String, Long> badSpotUntil = new LinkedHashMap<>();     // by place name
    private final Map<String, Long> lackingUntil = new LinkedHashMap<>();     // by method / quest label

    /** Avoid this spot for a while (something went wrong there). Banks are never avoided. */
    void avoidSpot(Place p, long now) {
        if (p != null && !Places.BANKS.contains(p)) badSpotUntil.put(p.name(), now + Picker.PARK_MS);
    }

    /** Set this way of training aside for a while: the bank did not have what it needs. */
    void setLacking(String label, long now) { if (!label.isEmpty()) lackingUntil.put(label, now + Picker.PARK_MS); }

    boolean lacking(String label) { expire(); return lackingUntil.containsKey(label); }

    /** What has been set aside for missing items, for the panel. */
    List<String> lackingLabels() { expire(); return new ArrayList<>(lackingUntil.keySet()); }

    /** Forget everything learned (the panel's "unpark"). */
    void forget() { badSpotUntil.clear(); lackingUntil.clear(); }

    /**
     * A random spot from {@code spots}: never one too dangerous for the combat level, and not one that just
     * failed unless every safe one did. Null when none is safe.
     */
    Place spot(List<Place> spots) {
        expire();
        List<Place> safe = spots.stream().filter(Place::safeNow).toList();
        if (safe.isEmpty()) return null;
        List<Place> ok = new ArrayList<>();
        for (Place p : safe) if (!badSpotUntil.containsKey(p.name())) ok.add(p);
        if (ok.isEmpty()) ok = safe;
        return ok.get(rng.nextInt(ok.size()));
    }

    private void expire() {
        long now = System.currentTimeMillis();
        badSpotUntil.values().removeIf(t -> t <= now);
        lackingUntil.values().removeIf(t -> t <= now);
    }
}
