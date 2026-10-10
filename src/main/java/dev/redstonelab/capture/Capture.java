package dev.redstonelab.capture;

/**
 * Global fast-path switch read by every mixin hook before doing anything else. With no
 * meters placed every hook is a single static field read.
 */
public final class Capture {
	/** At least one block has a meter on it. */
	public static boolean watching;

	private Capture() {
	}
}
