package oxclient.plugins;

import java.awt.Graphics2D;
import java.util.List;

import oxclient.Plugin;
import oxclient.api.Bank;
import oxclient.api.Game;
import oxclient.api.Walker;
import oxclient.framework.BankCache;
import oxclient.framework.Goal;
import oxclient.framework.Picker;
import oxclient.framework.Profile;
import oxclient.framework.Task;
import oxclient.ui.Hud;
import oxclient.ui.Theme;

/**
 * Builds an account from a profile: picks a goal at random, trains it for a stint, picks again.
 *
 * <p>Choose a {@link Profile} from the list (for now: every free-to-play skill to 99). A {@link Picker} then
 * chooses among the goals not yet reached at random, trains one for a random number of minutes between the
 * stint settings, at a random spot that suits the skill's current level, and moves on -- banking (or
 * dropping) as it goes and fetching a tool from the bank when it has none. See the account builder plan.</p>
 *
 * <p>Skills with training methods: Woodcutting, Mining, Fishing (gathering), Cooking, Firemaking
 * (processing), Attack, Strength, Defence, Ranged, Prayer (combat; Magic once its spells are mapped). The rest
 * show as "not built yet"; members mode is a placeholder. Combat stints gear up first; what the account lacks
 * is listed as "to buy". <b>Picker seen working: no; gathering with banking: yes (2026-10-09).</b></p>
 */
public final class AccountBuilder extends Plugin {

    /** Free-to-play or members. Only F2P has any logic (plan, Phase 7). */
    public enum Mode {
        F2P("Free-to-play"), P2P("Members (not built yet)");

        private final String label;

        Mode(String label) { this.label = label; }

        @Override public String toString() { return label; }
    }

    private Picker picker;
    private String builtFor = "";

    public AccountBuilder() {
        config.enumeration("mode", "Mode", "Free-to-play or members (only free-to-play is built)", Mode.F2P);
        config.enumeration("profile", "Profile", "The goals to work towards", Profile.F2P_ALL_99);
        config.number("stintMin", "Stint min (minutes)", "Shortest time on one goal before picking another", 20, 1, 240);
        config.number("stintMax", "Stint max (minutes)", "Longest time on one goal before picking another", 45, 1, 240);
        config.bool("bank", "Bank resources", "Bank what you gather (off: drop it)", true);
        config.bool("ge", "Use the Grand Exchange", "Sell what is gathered and buy what is missing, between stints", true);
        config.number("depositOp", "Deposit-All option", "The bank's option number for Deposit-All", 8, 1, 10);
        config.number("withdrawOp", "Withdraw-1 option", "The bank's option number for Withdraw-1 (1 = a left click, with the bank quantity set to 1)", 1, 1, 10);
        config.bool("skip", "Next goal", "Tick to end this stint and pick another goal", false);
        config.bool("unpark", "Unpark goals", "Tick to give parked goals another go", false);
        config.bool("panel", "Show panel", "Draw the status panel", true);
    }

    @Override public String name() { return "Account Builder"; }

    @Override public String description() { return "Trains a profile's goals in random order, banking and walking on its own."; }

    @Override
    protected void onEnable() {
        Walker.ready();                                   // start loading the walking map now
        picker = null;
    }

    private Mode mode() { return choice("mode", Mode.class, Mode.F2P); }

    private Profile profile() { return choice("profile", Profile.class, Profile.F2P_ALL_99); }

    /** An enum setting's value, whether it holds the constant or (loaded from a profile) its name. */
    private <E extends Enum<E>> E choice(String key, Class<E> type, E def) {
        Object v = config.get(key).value();
        if (type.isInstance(v)) return type.cast(v);
        try { return Enum.valueOf(type, String.valueOf(v)); } catch (IllegalArgumentException e) { return def; }
    }

    private boolean membersOnly() { return mode() == Mode.P2P || profile().members(); }

    @Override
    public String status() {
        if (membersOnly()) return "members: not built";
        if (picker == null) return "";
        Goal g = picker.current();
        return g == null ? "idle" : g.toString();
    }

    @Override
    public void tick() {
        Bank.opDepositAll = config.number("depositOp");
        Bank.opWithdraw1 = config.number("withdrawOp");
        if (membersOnly()) return;                        // placeholder: nothing runs in members mode yet
        if (Game.ready()) BankCache.observe();            // remember the bank whenever it is open, even by hand
        String who = oxclient.framework.Account.key();
        if (who.isEmpty()) return;                        // wait for the character: progress is saved per character
        String key = profile().name() + "|" + config.bool("bank") + "|" + who;
        if (picker == null || !key.equals(builtFor)) {
            picker = new Picker(key, profile().goals(), config.bool("bank"));
            builtFor = key;
        }
        picker.stintMinutes(config.number("stintMin"), config.number("stintMax"));
        picker.trading(config.bool("ge"));
        if (config.bool("skip")) { config.get("skip").set(false); picker.skip(); }
        if (config.bool("unpark")) { config.get("unpark").set(false); picker.unpark(); }
        if (Game.ready()) picker.tick();
    }

    @Override
    public void render(Graphics2D g) {
        if (!config.bool("panel")) return;
        g.setFont(Theme.UI);
        Hud.Lines lines = new Hud.Lines();
        if (membersOnly()) {
            lines.add("Members mode is not built yet", "", Theme.WARN);
            lines.add("choose Free-to-play and a free-to-play profile");
            Hud.panel(g, 12, 12, "Account Builder", lines);
            return;
        }
        if (picker == null) return;
        Goal goal = picker.current();
        lines.add("goal", goal == null ? "none" : goal + " (now " + oxclient.api.Skills.level(goal.skill()) + ")");
        if (goal != null) lines.add("stint left", minutes(picker.stintLeft()));
        long xph = picker.history().xpPerHour();
        if (xph >= 0) lines.add("xp/hour", String.format("%,d", xph));
        Task t = picker.task();
        if (t != null) {
            lines.add(t.name());
            if (!t.state().isEmpty()) lines.add("doing", t.state());
        }
        if (!picker.note().isEmpty()) lines.add("last", picker.note());
        for (String p : picker.parked()) lines.add("parked: " + p, "", Theme.WARN);
        List<String> wants = picker.wants();
        for (int i = 0; i < wants.size() && i < 5; i++) lines.add("to buy: " + wants.get(i));
        if (wants.size() > 5) lines.add("to buy", "+" + (wants.size() - 5) + " more");
        List<String> unbuilt = picker.unbuilt();
        if (!unbuilt.isEmpty()) lines.add("not built yet", unbuilt.size() + " skills");
        if (!Walker.loadError().isEmpty()) lines.add("walking map: " + Walker.loadError(), "", Theme.WARN);
        for (String h : picker.history().recent(3)) lines.add("past: " + h);
        Hud.panel(g, 12, 12, "Account Builder", lines);
    }

    private static String minutes(long ms) {
        long s = ms / 1000;
        return s / 60 + "m " + String.format("%02d", s % 60) + "s";
    }
}
