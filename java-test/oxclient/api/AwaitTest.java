// Await is the non-blocking "did it happen" every script leans on, so its contract is pinned against a fake
// clock: nothing pending is IDLE, MET and TIMED_OUT are each reported exactly once, and a condition that
// throws counts as not met rather than killing the tick.
package oxclient.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.Test;

public class AwaitTest
{
	private final AtomicLong now = new AtomicLong(1000);
	private final Await await = new Await(now::get);

	@Test
	public void nothingPendingIsIdle()
	{
		assertEquals(Await.Status.IDLE, await.poll());
		assertFalse(await.isWaiting());
	}

	@Test
	public void waitsThenReportsMetOnce()
	{
		AtomicBoolean done = new AtomicBoolean();
		await.until(done::get, 5000);
		assertEquals(Await.Status.WAITING, await.poll());
		assertTrue(await.isWaiting());
		done.set(true);
		assertEquals(Await.Status.MET, await.poll());
		assertEquals(Await.Status.IDLE, await.poll());
	}

	@Test
	public void timesOutOnceAtTheDeadline()
	{
		await.until(() -> false, 5000);
		now.addAndGet(4999);
		assertEquals(Await.Status.WAITING, await.poll());
		now.addAndGet(1);
		assertEquals(Await.Status.TIMED_OUT, await.poll());
		assertEquals(Await.Status.IDLE, await.poll());
	}

	@Test
	public void metWinsOverTimeoutOnTheSamePoll()
	{
		await.until(() -> true, 0);
		assertEquals(Await.Status.MET, await.poll());
	}

	@Test
	public void aConditionThatThrowsIsNotMet()
	{
		await.until(() -> { throw new IllegalStateException("unreadable"); }, 100);
		assertEquals(Await.Status.WAITING, await.poll());
		now.addAndGet(100);
		assertEquals(Await.Status.TIMED_OUT, await.poll());
	}

	@Test
	public void cancelAndReplace()
	{
		await.until(() -> false, 5000);
		await.cancel();
		assertEquals(Await.Status.IDLE, await.poll());
		await.until(() -> false, 5000);
		await.until(() -> true, 5000);
		assertEquals(Await.Status.MET, await.poll());
	}
}
