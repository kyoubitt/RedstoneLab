package dev.redstonelab.client;

/** The client's tick clock (the game's frame timer), nudged to stay in step with the server. */
public interface TickClock {
	/**
	 * The server just finished a tick: move the client's tick rhythm a little, so its ticks come
	 * half a tick after the server's. Then every client tick has the server's newest tick to show,
	 * evenly spaced: pistons and mobs move smoothly instead of stalling and catching up.
	 */
	void redstonelab$alignToServerTick();
}
