package oxclient.api;

import java.util.Arrays;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * "Did what I just did actually happen?" -- without blocking the tick.
 *
 * <p>Every action in this API returns as soon as it is handed to the game; the effect (you start chopping,
 * the log lands in your inventory, the bank opens) shows up a few ticks later, or never. A tick must never
 * sleep, so instead of waiting in place a script starts an {@code Await} after acting and polls it on the
 * ticks that follow:</p>
 *
 * <pre>{@code
 * private final Await await = new Await();
 *
 * public void tick() {
 *     switch (await.poll()) {
 *         case WAITING:   return;                                // still waiting: do nothing this tick
 *         case TIMED_OUT: status("the click did nothing"); break; // fall through and decide again
 *         default:        break;                                 // MET, or nothing was pending
 *     }
 *     SceneObject tree = Scenery.nearest("Oak tree");
 *     if (tree != null && tree.interact("Chop down")) await.until(Await.animating(), 5000);
 * }
 * }</pre>
 *
 * <p>{@link #poll} reports {@link Status#MET} or {@link Status#TIMED_OUT} exactly once, then goes back to
 * {@link Status#IDLE}, so the outcome of one action is never read twice.</p>
 */
public final class Await {

    /** Where a wait stands. */
    public enum Status {
        /** Nothing is being waited for. */
        IDLE,
        /** The condition has not held yet and there is time left. Do nothing this tick. */
        WAITING,
        /** The condition held. Reported once. */
        MET,
        /** Time ran out first. Reported once. */
        TIMED_OUT
    }

    private final LongSupplier clock;
    private BooleanSupplier condition;
    private long deadline;

    public Await() { this(System::currentTimeMillis); }

    /** With an explicit clock, for tests. */
    Await(LongSupplier clock) { this.clock = clock; }

    /** Start waiting for {@code condition}, for at most {@code timeoutMs}. Replaces any wait in progress. */
    public void until(BooleanSupplier condition, long timeoutMs) {
        this.condition = condition;
        this.deadline = clock.getAsLong() + Math.max(0, timeoutMs);
    }

    /** Check the wait: see {@link Status}. Cheap; call it at the top of every tick. */
    public Status poll() {
        if (condition == null) return Status.IDLE;
        boolean met;
        try {
            met = condition.getAsBoolean();
        } catch (RuntimeException e) {
            met = false;                       // a condition that cannot be evaluated has not been met
        }
        if (met) { condition = null; return Status.MET; }
        if (clock.getAsLong() >= deadline) { condition = null; return Status.TIMED_OUT; }
        return Status.WAITING;
    }

    /** True while a wait is in progress (without evaluating it). */
    public boolean isWaiting() { return condition != null; }

    /** Forget the wait in progress. */
    public void cancel() { condition = null; }

    // -----------------------------------------------------------------------------------------------
    // The conditions scripts wait for most. Each captures what it needs at the moment it is created --
    // so create it right after the action, as in the example above.
    // -----------------------------------------------------------------------------------------------

    /** You have started an animation (chopping, mining, fishing, attacking). */
    public static BooleanSupplier animating() { return () -> !Game.me().isIdle(); }

    /** You have stopped animating. */
    public static BooleanSupplier idle() { return () -> Game.me().isIdle(); }

    /** Your inventory is different from what it is now: an item arrived, left, or a stack changed size. */
    public static BooleanSupplier inventoryChanged() {
        int[] before = inventorySnapshot();
        return () -> !Arrays.equals(before, inventorySnapshot());
    }

    /** That piece of scenery is gone (the tree fell, the rock is mined, the door opened). */
    public static BooleanSupplier gone(Scenery.SceneObject o) { return () -> o == null || !o.exists(); }

    /** You are within {@code tiles} of {@code p}. */
    public static BooleanSupplier near(WorldPoint p, int tiles) {
        return () -> Game.me().exists() && Game.me().location().within(p, tiles);
    }

    /** The bank is open. */
    public static BooleanSupplier bankOpen() { return Bank::isOpen; }

    /** A conversation is showing (a "Click here to continue" or an options menu). */
    public static BooleanSupplier dialog() { return Dialog::isOpen; }

    /** The inventory as the game holds it, {@code {id, quantity}} pairs; empty when unreadable. */
    private static int[] inventorySnapshot() { return oxclient.Natives.container(Inventory.CONTAINER); }
}
