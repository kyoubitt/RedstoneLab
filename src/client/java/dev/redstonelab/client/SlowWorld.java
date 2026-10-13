package dev.redstonelab.client;

/**
 * Slow motion on the client: the player runs at full speed, the world (pistons, mobs, items)
 * at the game's speed. Implemented by the client's TickRateManager.
 */
public interface SlowWorld {
	/** Slow motion is on (game slower than 20 ticks a second, not frozen). */
	boolean redstonelab$slow();

	/** The world is drawn with its own timing: slow motion, or a single step sliding into place. */
	boolean redstonelab$smooth();

	/** After a client tick: the world waits until its next tick (only the player moves). */
	void redstonelab$hold();

	/** How far the world is from its last tick to its next one (0-1), for smooth movement. */
	float redstonelab$partial(float clientPartial);
}
