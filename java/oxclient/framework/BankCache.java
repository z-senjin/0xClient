package oxclient.framework;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import oxclient.api.Bank;
import oxclient.api.Items;
import oxclient.persist.JsonStore;

/**
 * What the bank held the last time it was open, by item name, so a method whose items are not there can be
 * skipped without walking to the bank to find out. Saved per character to
 * {@code ~/.0xclient/scripts/<character>/bank.json} ({@link Account}); nothing is known before you log in.
 *
 * <p>Only names the client knows are kept (an item it has not drawn has no cached definition), so an item
 * can be missing here and still be in the bank; "unknown" therefore never rules a method out -- only a
 * bank seen recently without it does. {@link #observe} is called every frame the bank is open.</p>
 */
public final class BankCache {

    private BankCache() {}

    private static final Map<String, Integer> counts = new LinkedHashMap<>();
    private static long seenAt;
    private static boolean loaded;
    private static String loadedFor = "";
    private static int lastHash;

    /** Snapshot the bank if it is open. Cheap when nothing changed. */
    public static void observe() {
        load();
        if (!Bank.isOpen()) return;
        int[] f = Bank.contents();
        if (f.length == 0) return;
        int h = java.util.Arrays.hashCode(f);
        if (h == lastHash) return;
        Map<String, Integer> now = new LinkedHashMap<>();
        boolean unnamed = false;
        for (int i = 0; i + 1 < f.length; i += 2) {
            if (f[i] < 0 || f[i + 1] <= 0 || BankTrip.DUDS.contains(f[i])) continue;
            String n = Items.name(f[i]);
            if (n.isEmpty()) { unnamed = true; continue; }    // not drawn yet: kept out, looked at again next frame
            now.merge(n.toLowerCase(), f[i + 1], Integer::sum);
        }
        if (!unnamed) lastHash = h;
        if (now.equals(counts) && seenAt > 0) return;
        counts.clear();
        counts.putAll(now);
        seenAt = System.currentTimeMillis();
        save();
    }

    /** Drop an item that turned out to be a placeholder (it withdraws nothing), and do not count it again. */
    public static void forget(String name, int id) {
        load();
        if (counts.remove(name.toLowerCase()) != null) save();
        lastHash = 0;
    }

    /** True once the bank has been seen (this session or a saved one). */
    public static boolean known() { load(); return seenAt > 0; }

    /** How many of an item (by exact name, ignoring case) the bank held when last seen. */
    public static int count(String name) {
        load();
        Integer n = counts.get(name.toLowerCase());
        return n == null ? 0 : n;
    }

    /**
     * The bank was seen open and EMPTY (a new account): record that, so "known" is true -- {@link #observe}
     * skips an empty container, since a closed bank can read empty too. Called by a bank trip that has
     * just confirmed the bank is open.
     */
    public static void sawEmpty() {
        load();
        if (Bank.contents().length > 0 || seenAt > 0) return;
        counts.clear();
        seenAt = System.currentTimeMillis();
        save();
    }

    /** Could the bank supply this need? True when the bank has not been seen. */
    public static boolean couldHave(Need need) {
        if (!known()) return true;
        for (int i = 0; i < need.anyOf().size(); i++) if (need.usable(i) && count(need.anyOf().get(i)) > 0) return true;
        return false;
    }

    /** Every need met (carried) or possibly in the bank. */
    public static boolean couldSupply(List<Need> needs) {
        for (Need n : needs) if (!n.met() && !couldHave(n)) return false;
        return true;
    }

    private static void load() {
        String who = Account.key();
        if (!who.equals(loadedFor)) { loaded = false; counts.clear(); seenAt = 0; lastHash = 0; loadedFor = who; }   // a new character
        if (loaded) return;
        Path f = file();
        if (f == null) return;                                // not logged in yet: nothing known
        loaded = true;
        Map<String, Object> m = JsonStore.read(f);
        if (m == null) return;
        if (m.get("seenAt") instanceof Number n) seenAt = n.longValue();
        if (m.get("items") instanceof Map<?, ?> items)
            for (Map.Entry<?, ?> e : items.entrySet()) if (e.getValue() instanceof Number c) counts.put(String.valueOf(e.getKey()), c.intValue());
    }

    private static void save() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("seenAt", seenAt);
        m.put("items", new LinkedHashMap<>(counts));
        Path f = file();
        if (f != null) JsonStore.write(f, m);
    }

    private static Path file() { return Account.file("bank"); }
}
