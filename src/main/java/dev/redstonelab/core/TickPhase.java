package dev.redstonelab.core;

/**
 * The part of the game tick that is running, in the order the game runs them. Set by the
 * server mixins, read when a meter records an event (for the "what happened in this tick"
 * list).
 */
public enum TickPhase {
	/** Anything outside a world tick: players clicking, commands, other dimensions. */
	PLAYERS("players & commands"),
	TICK_START("tick start"),
	BLOCK_TICKS("block ticks"),
	FLUID_TICKS("fluid ticks"),
	CHUNKS("chunks (random ticks)"),
	BLOCK_EVENTS("block events"),
	ENTITIES("entities"),
	BLOCK_ENTITIES("block entities");

	/** Server thread only. */
	public static TickPhase current = PLAYERS;

	private final String displayName;

	TickPhase(String displayName) {
		this.displayName = displayName;
	}

	public String displayName() {
		return this.displayName;
	}

	private static final TickPhase[] VALUES = values();

	public static TickPhase byOrdinal(int ordinal) {
		return ordinal >= 0 && ordinal < VALUES.length ? VALUES[ordinal] : PLAYERS;
	}
}
