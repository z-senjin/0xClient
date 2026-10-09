package oxclient.framework;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import oxclient.api.Game;
import oxclient.persist.JsonStore;

/**
 * Runs a list of {@link Task}s in order, one tick at a time, and remembers where it got to.
 *
 * <p>Progress is saved to {@code ~/.0xclient/scripts/<name>.json} (the index of the current task and the
 * plan it belongs to), so restarting the client resumes the same plan at the same task. A task that
 * fails stops the script with its reason; nothing retries forever.</p>
 */
public final class Script {

    private final String name;
    private final List<Task> tasks;
    private final String planKey;
    private int index;
    private boolean started;
    private String stopped = "";
    private long nextTick;

    /**
     * @param name     the save file's name
     * @param planKey  identifies the plan (its text): a changed plan starts from the top
     */
    public Script(String name, String planKey, List<Task> tasks) {
        this.name = name;
        this.planKey = planKey;
        this.tasks = new ArrayList<>(tasks);
        Map<String, Object> saved = JsonStore.read(file());
        if (saved != null && planKey.equals(saved.get("plan")) && saved.get("index") instanceof Number n) {
            index = Math.max(0, Math.min(this.tasks.size(), n.intValue()));
        }
    }

    /** Call every frame; it ticks the current task a few times a second. */
    public void tick() {
        if (isFinished() || !stopped.isEmpty() || !Game.ready()) return;
        long now = System.currentTimeMillis();
        if (now < nextTick) return;
        nextTick = now + 150;
        Task t = tasks.get(index);
        if (!started) { t.start(); started = true; }
        Task.Result r;
        try {
            r = t.tick(now);
        } catch (RuntimeException e) {
            stopped = t.name() + " threw " + e;
            return;
        }
        if (r == Task.Result.DONE) {
            index++;
            started = false;
            save();
        } else if (r == Task.Result.FAILED) {
            stopped = t.name() + ": " + t.failure();
        }
    }

    public boolean isFinished() { return index >= tasks.size(); }

    /** Why the script stopped, or "" while it runs. */
    public String stopped() { return stopped; }

    public Task current() { return isFinished() ? null : tasks.get(index); }

    public int index() { return index; }

    public List<Task> tasks() { return Collections.unmodifiableList(tasks); }

    /** Start over from the first task. */
    public void reset() { index = 0; started = false; stopped = ""; save(); }

    /** Skip the current task. */
    public void skip() { if (!isFinished()) { index++; started = false; stopped = ""; save(); } }

    private void save() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("plan", planKey);
        m.put("index", index);
        JsonStore.write(file(), m);
    }

    private Path file() {
        return Paths.get(System.getProperty("user.home"), ".0xclient", "scripts", name + ".json");
    }
}
