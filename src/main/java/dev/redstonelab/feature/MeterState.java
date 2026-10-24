package dev.redstonelab.feature;

import java.util.ArrayList;
import java.util.List;

/** One player's meters and groups. */
final class MeterState {
	final List<Meter> meters = new ArrayList<>();
	/** Saved groups; the active one mirrors {@link #meters}. */
	MeterStore.Groups groups = new MeterStore.Groups();
	long lastSampleTime = Long.MIN_VALUE;
	boolean dirty = true;
	int statsCountdown;
	long lastClick = -1000;
	/** A piston moved a meter: save soon (not on every move, flying machines move a lot). */
	boolean moved;
}
