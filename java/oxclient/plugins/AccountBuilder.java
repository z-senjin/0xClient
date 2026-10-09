package oxclient.plugins;

import java.awt.Graphics2D;
import java.util.ArrayList;
import java.util.List;

import oxclient.Plugin;
import oxclient.api.Bank;
import oxclient.api.Game;
import oxclient.api.Walker;
import oxclient.framework.Plans;
import oxclient.framework.Script;
import oxclient.framework.Task;
import oxclient.ui.Hud;
import oxclient.ui.Theme;

/**
 * Works through a plan of goals, one after another: {@code "woodcutting 30, fishing 20, mining 15"}.
 *
 * <p>The first plugin built on {@code oxclient.framework}: the plan becomes {@link Task}s (see
 * {@link Plans}), a {@link Script} runs them in order and saves its place, so a restart carries on where
 * it stopped. Each skill trains at a spot for its level and banks (or drops) when full, fetching a tool
 * from the bank when it has none.</p>
 *
 * <p><b>Not seen working yet.</b> It stands on parts that were (scenery, chopping, dropping, the inventory)
 * and parts that were not (the route walker with stairs and doors, banking's deposit and withdraw option
 * numbers, the training-spot coordinates in {@code Places}). The panel says what each task is doing and,
 * when one fails, why -- that line is what to send back.</p>
 */
public final class AccountBuilder extends Plugin {

    private Script script;
    private String builtFor = "";
    private final List<String> problems = new ArrayList<>();

    public AccountBuilder() {
        config.text("plan", "Plan", "Goals in order, e.g. woodcutting 30, fishing 20, mining 15", "woodcutting 15");
        config.bool("bank", "Bank resources", "Bank what you gather (off: drop it)", true);
        config.number("depositOp", "Deposit-All option", "The bank's option number for Deposit-All", 8, 1, 10);
        config.number("withdrawOp", "Withdraw-1 option", "The bank's option number for Withdraw-1 (1 = a left click, with the bank quantity set to 1)", 1, 1, 10);
        config.bool("restart", "Restart plan", "Tick to start the plan from the top", false);
        config.bool("skip", "Skip task", "Tick to skip the current task", false);
        config.bool("panel", "Show panel", "Draw the status panel", true);
    }

    @Override public String name() { return "Account Builder"; }

    @Override public String description() { return "Trains skills from a plan, banking and walking on its own."; }

    @Override
    protected void onEnable() {
        Walker.ready();                                   // start loading the walking map now
        script = null;
    }

    @Override
    public String status() {
        if (script == null) return "";
        if (!script.stopped().isEmpty()) return "stopped";
        if (script.isFinished()) return "plan finished";
        return (script.index() + 1) + "/" + script.tasks().size();
    }

    @Override
    public void tick() {
        Bank.opDepositAll = config.number("depositOp");
        Bank.opWithdraw1 = config.number("withdrawOp");
        String key = config.text("plan").trim() + "|" + config.bool("bank");
        if (script == null || !key.equals(builtFor)) {
            problems.clear();
            List<Task> tasks = Plans.parse(config.text("plan"), config.bool("bank"), problems);
            script = new Script("account-builder", key, tasks);
            builtFor = key;
        }
        if (config.bool("restart")) { config.get("restart").set(false); script.reset(); }
        if (config.bool("skip")) { config.get("skip").set(false); script.skip(); }
        if (Game.ready()) script.tick();
    }

    @Override
    public void render(Graphics2D g) {
        if (!config.bool("panel") || script == null) return;
        g.setFont(Theme.UI);
        Hud.Lines lines = new Hud.Lines();
        Task t = script.current();
        lines.add("task", script.isFinished() ? "done" : (script.index() + 1) + " of " + script.tasks().size());
        if (t != null) {
            lines.add(t.name());
            if (!t.state().isEmpty()) lines.add("doing", t.state());
        }
        if (!script.stopped().isEmpty()) lines.add("STOPPED: " + script.stopped(), "", Theme.WARN);
        for (String p : problems) lines.add(p, "", Theme.WARN);
        if (!Walker.loadError().isEmpty()) lines.add("walking map: " + Walker.loadError(), "", Theme.WARN);
        Hud.panel(g, 12, 12, "Account Builder", lines);
    }
}
