package dev.redstonelab.client;

/**
 * Slow motion with RedstoneLab on the server: the server says when it ticked, and the client
 * moves its world (pistons, mobs, items) exactly then, one step per server tick. Without it the
 * client keeps its own count (see the TickRateManager mixin).
 */
public final class WorldTicks {
	/** Server ticks announced but not run here yet (a few at most, if packets bunch up). */
	private static int pending;
	/** When the server last said it ticked, and when this client last moved its world (nanoseconds). */
	private static long lastSignal = Long.MIN_VALUE / 2;
	private static long lastRun;

	private WorldTicks() {
	}

	public static void onServerTick() {
		pending = Math.min(pending + 1, 4);
		lastSignal = System.nanoTime();
	}

	/** The server is announcing its ticks (the slowest speed, 1 tick a second, still counts). */
	public static boolean synced() {
		return System.nanoTime() - lastSignal < 2_000_000_000L;
	}

	/** Runs one announced tick, if there is one. */
	public static boolean take() {
		if (pending <= 0) {
			return false;
		}
		pending--;
		lastRun = System.nanoTime();
		return true;
	}

	/** How far the world is towards the server's next tick (0-1), for smooth movement. */
	public static float partial(float tickrate) {
		return Math.min(1f, (System.nanoTime() - lastRun) / (1_000_000_000f / tickrate));
	}

	/** When the last single step (frozen game) ran here. */
	private static long lastStep = Long.MIN_VALUE / 2;

	/** A frozen game stepped one tick on this client. */
	public static void onStep() {
		lastStep = System.nanoTime();
	}

	/** A step is still sliding into place: it takes one tick at the game's speed, at least 0.15 s. */
	public static boolean stepping(float tickrate) {
		return System.nanoTime() - lastStep < stepNanos(tickrate);
	}

	public static float stepPartial(float tickrate) {
		return Math.min(1f, (float) (System.nanoTime() - lastStep) / stepNanos(tickrate));
	}

	private static long stepNanos(float tickrate) {
		return Math.max(150_000_000L, (long) (1_000_000_000L / Math.max(1f, tickrate)));
	}

	public static void reset() {
		pending = 0;
		lastSignal = Long.MIN_VALUE / 2;
	}
}
