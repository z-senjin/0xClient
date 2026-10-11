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
        if (!gameSaid.isEmpty()) return fail("the game says: " + gameSaid);
        if (now < waitUntil) return Result.CONTINUE;
        return step(now);
    }

    /** A game message that ends the task (LEVEL_TOO_LOW); set by {@link #onEvent}, acted on at the next tick. */
    private String gameSaid = "";

    /**
     * The default reaction to game messages: a level the method does not have ends the task (the picker then
     * tries again, sets the method aside or parks the goal). Tasks that care about more call this too.
     */
    @Override
    public void onEvent(ChatEvent event, String line) {
        if (event == ChatEvent.LEVEL_TOO_LOW) gameSaid = line.replaceFirst("^\\[\\d+:\\d+\\]\\s*", "");
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
