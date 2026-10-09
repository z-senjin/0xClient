package oxclient.framework;

import java.util.Random;

/**
 * The common parts of a task: a state label, a failure reason, a "do not act before" time, and a little
 * randomness for pauses -- so a task's {@link #tick} reads as plain decisions.
 */
public abstract class BaseTask implements Task {

    protected final Random rng = new Random();
    protected String state = "";
    protected String failure = "";
    protected long waitUntil;
    protected long stateSince;

    @Override public String state() { return state; }

    @Override public String failure() { return failure; }

    @Override
    public final Result tick(long now) {
        if (now < waitUntil) return Result.CONTINUE;
        return step(now);
    }

    /** One decision; called only when not waiting. */
    protected abstract Result step(long now);

    /** Change the state label (and remember when it changed). */
    protected void state(String s) {
        if (!s.equals(state)) { state = s; stateSince = System.currentTimeMillis(); }
    }

    /** How long the current state has lasted, in ms. */
    protected long inState(long now) { return now - stateSince; }

    /** Do nothing for between {@code min} and {@code max} ms. */
    protected void sleep(long now, int min, int max) { waitUntil = now + min + rng.nextInt(Math.max(1, max - min)); }

    protected Result fail(String why) { failure = why; return Result.FAILED; }
}
