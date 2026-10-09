package oxclient.framework;

/**
 * One piece of work a {@link Script} runs: "chop willows to level 35", "bank the logs", "walk to Draynor".
 *
 * <p>{@link #tick} is called a few times a second while the task is current. It must never block -- do one
 * thing, remember where you are, return. The script moves on when it returns {@link Result#DONE}, stops
 * with the reason when it returns {@link Result#FAILED}, and calls it again otherwise.</p>
 */
public interface Task {

    enum Result { CONTINUE, DONE, FAILED }

    /** A short label for the panel, e.g. "Woodcutting to 30 at Draynor". */
    String name();

    /** One decision. Never blocks. */
    Result tick(long now);

    /** What it is doing right now ("walking to the bank"), for the panel. */
    default String state() { return ""; }

    /** Why it failed, when {@link #tick} returned FAILED. */
    default String failure() { return ""; }

    /** Called once when the task becomes current (and again after a restart). */
    default void start() {}
}
