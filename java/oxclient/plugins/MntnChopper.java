package oxclient.plugins;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Polygon;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import oxclient.Plugin;
import oxclient.api.Actions;
import oxclient.api.Bank;
import oxclient.api.Game;
import oxclient.api.Inventory;
import oxclient.api.Local;
import oxclient.api.Scenery;
import oxclient.api.Scenery.SceneObject;
import oxclient.api.Skill;
import oxclient.api.Skills;
import oxclient.api.Walker;
import oxclient.ui.Hud;
import oxclient.ui.Theme;

/**
 * Chops the nearest tree; when the inventory is full, banks the logs (or drops them), comes back, and
 * repeats.
 *
 * <p>The loop, one decision per tick:</p>
 * <ol>
 *   <li><b>Find</b> the nearest object named "Tree" (the setting) within the search radius, and take
 *       its first option, Chop down.</li>
 *   <li><b>Walk</b>: until you start animating. If nothing happens within a few seconds, or the tree
 *       is gone, go back to 1.</li>
 *   <li><b>Chop</b>: while you are animating. When the animation has stopped for a moment -- the tree
 *       fell, or the inventory filled -- go on.</li>
 *   <li><b>Full</b>: with "Bank logs" on, walk to the nearest bank booth or chest in sight (or the
 *       "Bank tile" setting when none is), open it, deposit every log with the Deposit-All option,
 *       walk back to where the plugin was switched on, and go back to 1. With it off, drop every log
 *       one slot at a time instead.</li>
 * </ol>
 *
 * <p>Walking uses {@link Walker}: a route over the world's collision map on one floor, so the bank has to
 * be on the same floor as the trees (Draynor's is, from the Lumbridge or Draynor trees; Lumbridge's
 * own bank, upstairs, is not).</p>
 *
 * <p>Trees are matched by NAME, read from the client's own definitions, so "Tree" means exactly the
 * ordinary tree and not "Oak tree" or "Dead tree". If a name cannot be read yet (the client had not
 * looked that tree up), the optional id list is used instead.</p>
 *
 * <p>Chopping and dropping were seen working on client-241-3 (2026-10-09). Banking is new and not yet
 * seen: the walk, the bank's options, "is the bank open" and the Deposit-All option number (8, a
 * setting) are each unconfirmed. The DLL log (OXC_LOG) has an {@code [actions]} line for everything
 * sent, and the panel names what it is waiting for.</p>
 */
public final class MntnChopper extends Plugin {

    private enum State { FIND, WALK, CHOP, DROP, TO_BANK, BANKING, TO_HOME }

    /** How long without an animation, after chopping, before the tree counts as done. */
    private static final long IDLE_GRACE_MS = 1800;

    /** How long to wait for the chopping animation to start after clicking a tree. */
    private static final long WALK_TIMEOUT_MS = 12_000;

    /** A slot that was just dropped is not dropped again until the inventory has had time to update. */
    private static final long REDROP_MS = 1500;

    private final Random rng = new Random();

    private State state = State.FIND;
    private SceneObject target;
    private long stateSince, lastAnim, nextActionAt;
    private long[] droppedAt = new long[Inventory.SIZE];
    private long startedAt;
    private int startXp, treesClicked, logsDropped;
    private String lastProblem = "";
    private int homeX, homeY, bankTries, logsBanked, logsAtLastDeposit = -1;
    private long nextThink, lastDepositChange;
    private Walker.Route route;
    private String bankLabel = "";

    public MntnChopper() {
        config.text("name", "Tree name", "Chop objects with exactly this name", "Tree");
        config.text("ids", "Tree ids (fallback)",
                "Comma-separated loc ids, used only while a tree's name cannot be read", "");
        config.text("logs", "Log item ids", "Comma-separated item ids to drop. 1511 is Logs.", "1511");
        config.number("radius", "Search radius", "How far to look for a tree, in tiles", 15, 2, 40);
        config.bool("bank", "Bank logs", "Bank the logs when full instead of dropping them", true);
        config.text("bankTile", "Bank tile (optional)",
                "x,y of a bank to walk to when none is in sight, e.g. 3092,3245 -- read yours off the panel", "");
        config.number("depositOp", "Deposit option",
                "The bank's option number for Deposit-All on an inventory item. Change it if deposits do nothing", 8, 1, 10);
        config.number("dropOp", "Drop option",
                "The item option number for Drop. 7 is the usual one; change it if drops do nothing", 7, 1, 10);
        config.number("dropDelay", "Drop delay (ms)", "Pause between drops", 350, 100, 2000);
        config.colour("colour", "Highlight", "Colour of the target tree", new Color(90, 220, 120));
        config.bool("panel", "Show statistics", "Draw the status panel", true);
    }

    @Override public String name() { return "MntnChopper"; }

    @Override public String description() { return "Chops the nearest tree, drops logs when full, repeats."; }

    @Override
    protected void onEnable() {
        startedAt = System.currentTimeMillis();
        startXp = Skills.experience(Skill.WOODCUTTING);
        treesClicked = 0;
        logsDropped = 0;
        target = null;
        lastProblem = "";
        droppedAt = new long[Inventory.SIZE];
        logsBanked = 0;
        bankTries = 0;
        route = null;
        Local me = Game.me();
        homeX = me.exists() ? me.worldX() : 0;
        homeY = me.exists() ? me.worldY() : 0;
        Walker.ready();                                   // start loading the collision map now
        enter(State.FIND);
    }

    @Override
    public String status() {
        return state.name().toLowerCase().replace('_', ' ') + ", " + gainedXp() + " xp, "
                + (config.bool("bank") ? logsBanked + " banked" : logsDropped + " dropped");
    }

    // -----------------------------------------------------------------------------------------------
    // Deciding and acting
    // -----------------------------------------------------------------------------------------------

    @Override
    public void tick() {
        if (!Game.ready()) return;
        long now = System.currentTimeMillis();
        Local me = Game.me();
        if (!me.isIdle()) lastAnim = now;
        if (homeX == 0 && me.exists()) { homeX = me.worldX(); homeY = me.worldY(); }
        if (now < nextActionAt || now < nextThink) return;
        nextThink = now + 150;                           // a few decisions a second is plenty

        switch (state) {
            case FIND -> find(now);
            case WALK -> {
                if (!me.isIdle()) { enter(State.CHOP); return; }
                if (Inventory.isFull()) { enter(full()); return; }
                if (now - stateSince > WALK_TIMEOUT_MS) { problem("never started chopping"); enter(State.FIND); return; }
                if (target != null && !Scenery.exists(target.id(), target.sceneX(), target.sceneY())) enter(State.FIND);
            }
            case CHOP -> {
                if (!me.isIdle()) return;
                if (now - lastAnim < IDLE_GRACE_MS) return;   // a pause between swings, not the end
                enter(Inventory.isFull() ? full() : State.FIND);
            }
            case DROP -> drop(now);
            case TO_BANK -> toBank(now);
            case BANKING -> banking(now);
            case TO_HOME -> {
                if (homeX == 0) { enter(State.FIND); return; }
                if (route == null || route.destX() != homeX || route.destY() != homeY) route = new Walker.Route(homeX, homeY, 3);
                Walker.Status st = route.step();
                if (st == Walker.Status.ARRIVED) { route = null; enter(State.FIND); }
                else walkProblem(st, "back to the trees");
            }
        }
    }

    /** What to do with a full inventory. */
    private State full() { return config.bool("bank") ? State.TO_BANK : State.DROP; }

    private void toBank(long now) {
        if (Inventory.count(parseIds(config.text("logs"))) == 0) { enter(State.TO_HOME); return; }
        SceneObject bank = Scenery.nearest(50, o -> Bank.bankOption(o) > 0);
        int dx, dy;
        if (bank != null) {
            bankLabel = bank.name() + " at " + bank.worldX() + "," + bank.worldY();
            if (bank.distance() <= 8) {
                if (bank.interact(Bank.bankOption(bank))) {
                    route = null;
                    enter(State.BANKING);
                    nextActionAt = now + 1200;
                } else {
                    problem("the bank click was not sent (see the [actions] log)");
                    nextActionAt = now + 2000;
                }
                return;
            }
            dx = bank.worldX();
            dy = bank.worldY();
        } else {
            int[] tile = parseIds(config.text("bankTile"));
            if (tile.length < 2) { problem("no bank in sight -- set \"Bank tile\""); nextActionAt = now + 2000; return; }
            dx = tile[0];
            dy = tile[1];
            bankLabel = "bank tile " + dx + "," + dy;
        }
        if (route == null || route.destX() != dx || route.destY() != dy) route = new Walker.Route(dx, dy, 1);
        Walker.Status st = route.step();
        if (st == Walker.Status.ARRIVED && bank == null) { problem("no bank booth or chest at the bank tile"); nextActionAt = now + 3000; }
        else walkProblem(st, "to the bank");
    }

    private void banking(long now) {
        int[] logs = parseIds(config.text("logs"));
        int have = Inventory.count(logs);
        if (have == 0) { route = null; bankTries = 0; enter(State.TO_HOME); return; }
        if (!Bank.isOpen()) {
            if (now - stateSince > 12_000) {             // never opened: click it again
                if (++bankTries >= 3) problem("the bank never opened");
                enter(State.TO_BANK);
            }
            return;
        }
        if (have != logsAtLastDeposit) {
            if (logsAtLastDeposit > have) logsBanked += logsAtLastDeposit - have;
            logsAtLastDeposit = have;
            lastDepositChange = now;
        } else if (now - lastDepositChange > 6000) {
            problem("deposits are doing nothing -- check the Deposit option");
        }
        int slot = Inventory.firstSlot(logs);
        if (slot >= 0 && !Bank.deposit(slot, config.number("depositOp"))) problem("the deposit was not sent (see the [actions] log)");
        nextActionAt = now + 700 + rng.nextInt(500);
    }

    private void walkProblem(Walker.Status st, String where) {
        switch (st) {
            case LOADING -> problem(Walker.loadError().isEmpty() ? "loading the walking map" : "walking map failed: " + Walker.loadError());
            case NO_ROUTE -> problem("no route " + where);
            case STUCK -> { problem("stuck on the way " + where); route = null; }
            default -> { if (lastProblem.startsWith("loading")) lastProblem = ""; }
        }
    }

    private void find(long now) {
        if (Inventory.isFull()) { enter(full()); return; }
        String want = config.text("name").trim();
        int[] ids = parseIds(config.text("ids"));
        SceneObject tree = Scenery.nearest(config.number("radius"), o -> isTree(o, want, ids));
        if (tree == null) {
            problem("no " + (want.isEmpty() ? "tree" : want) + " within " + config.number("radius") + " tiles");
            nextActionAt = now + 1200;
            return;
        }
        if (Actions.object(tree.id(), tree.worldX(), tree.worldY())) {
            target = tree;
            treesClicked++;
            lastProblem = "";
            enter(State.WALK);
            nextActionAt = now + 600 + rng.nextInt(400);
        } else {
            problem("the chop was not sent (see the [actions] log)");
            nextActionAt = now + 2000;
        }
    }

    private void drop(long now) {
        int[] logs = parseIds(config.text("logs"));
        int[] inv = Inventory.ids();
        int slot = -1;
        for (int i = 0; i < inv.length && i < droppedAt.length; i++) {
            if (inv[i] >= 0 && contains(logs, inv[i]) && now - droppedAt[i] > REDROP_MS) { slot = i; break; }
        }
        if (slot < 0) {
            // nothing left to drop -- or only slots still waiting for the inventory to catch up
            if (Inventory.count(logs) == 0) {
                if (Inventory.isFull()) problem("inventory is full of things that are not logs");
                enter(State.FIND);
            }
            return;
        }
        if (Inventory.interact(slot, config.number("dropOp"))) {
            droppedAt[slot] = now;
            logsDropped++;
        } else {
            problem("the drop was not sent (see the [actions] log)");
        }
        int base = config.number("dropDelay");
        nextActionAt = now + base + rng.nextInt(Math.max(1, base / 2));
    }

    private boolean isTree(SceneObject o, String want, int[] ids) {
        String n = o.name();
        if (!n.isEmpty()) return !want.isEmpty() && n.equalsIgnoreCase(want);
        return contains(ids, o.id());
    }

    private void enter(State s) {
        state = s;
        stateSince = System.currentTimeMillis();
        if (s == State.FIND) target = null;
        if (s == State.BANKING) { logsAtLastDeposit = -1; lastDepositChange = stateSince; }
    }

    private void problem(String why) { lastProblem = why; }

    // -----------------------------------------------------------------------------------------------
    // Drawing
    // -----------------------------------------------------------------------------------------------

    @Override
    public void render(Graphics2D g) {
        if (!Game.ready()) return;
        Color colour = config.colour("colour");
        g.setFont(Theme.UI);

        Walker.Route r = route;
        int[] aim = r == null ? null : r.aim();
        if (aim != null) Hud.tile(g, Game.tileOutlineWorld(aim[0], aim[1]), Theme.alpha(colour, 120));

        SceneObject t = target;
        if (t != null) {
            Polygon outline = Game.tileOutlineWorld(t.worldX(), t.worldY());
            Hud.tile(g, outline, colour);
            Point p = Game.projectWorld(t.worldX(), t.worldY());
            if (p != null) {
                String label = t.name().isEmpty() ? "loc " + t.id() : t.name();
                Hud.textCentred(g, label, p.x, p.y - 12, colour);
            }
        }

        if (config.bool("panel")) {
            int used = Inventory.used();
            Hud.Lines lines = new Hud.Lines()
                    .add("state", state.name().toLowerCase().replace('_', ' '), state == State.CHOP ? Theme.ON : Theme.TEXT)
                    .add("level", Skills.level(Skill.WOODCUTTING))
                    .add("xp gained", gainedXp())
                    .add("xp / hour", xpPerHour())
                    .add("trees clicked", treesClicked)
                    .add(config.bool("bank") ? "logs banked" : "logs dropped", config.bool("bank") ? logsBanked : logsDropped)
                    .add("inventory", used < 0 ? "?" : used + " / " + Inventory.SIZE)
                    .add("you are at", Game.me().worldX() + "," + Game.me().worldY())
                    .add("home", homeX + "," + homeY)
                    .add("running", elapsed());
            if (config.bool("bank") && !bankLabel.isEmpty()) lines.add("bank", bankLabel);
            if (!lastProblem.isEmpty()) lines.add(lastProblem, "", Theme.WARN);
            Hud.panel(g, 12, 12, "MntnChopper", lines);
        }
    }

    // -----------------------------------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------------------------------

    /** "1511, 1519" -> {1511, 1519}; anything that is not a number is skipped. */
    static int[] parseIds(String s) {
        List<Integer> out = new ArrayList<>();
        if (s != null) {
            for (String part : s.split("[,\\s]+")) {
                try {
                    if (!part.isEmpty()) out.add(Integer.parseInt(part.trim()));
                } catch (NumberFormatException ignored) {
                    // a typo in a settings box is not worth stopping for
                }
            }
        }
        int[] a = new int[out.size()];
        for (int i = 0; i < a.length; i++) a[i] = out.get(i);
        return a;
    }

    private static boolean contains(int[] set, int v) {
        for (int s : set) if (s == v) return true;
        return false;
    }

    private int gainedXp() {
        return Math.max(0, Skills.experience(Skill.WOODCUTTING) - startXp);
    }

    private int xpPerHour() {
        long ms = System.currentTimeMillis() - startedAt;
        if (ms < 10_000) return 0;
        return (int) (gainedXp() * 3_600_000L / ms);
    }

    private String elapsed() {
        long s = (System.currentTimeMillis() - startedAt) / 1000;
        return String.format("%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60);
    }
}
