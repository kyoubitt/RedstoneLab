package dev.redstonelab.capture;

/** Fast-path switch every mixin hook reads first: with no meters placed, a hook costs one static read. */
public final class Capture {
	/** At least one block has a meter on it. */
	public static boolean watching;

	private Capture() {
	}
}
