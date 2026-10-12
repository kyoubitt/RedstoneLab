package dev.redstonelab.core;

/**
 * Pulse widths (on time) and gaps (off time) of a signal. On = power above 0. A rise and fall
 * in the same game tick is a 0-tick pulse.
 */
public final class PulseMeter {
	/** An off time longer than this (and 3x every recent length) is a pause, not part of the clock. */
	public static final long IDLE_RESET = 100;

	private final RunStats widths;
	private final RunStats gaps;

	private boolean initialized;
	private boolean on;
	private long lastRise = Long.MIN_VALUE;
	private long lastFall = Long.MIN_VALUE;
	private long lastOnLength = -1;
	private long lastOffLength = -1;
	private boolean lastWasPause;

	public PulseMeter(int window) {
		this.widths = new RunStats(window);
		this.gaps = new RunStats(window);
	}

	/**
	 * Feed a new power level.
	 *
	 * @return +1 for a rising edge, -1 for a falling edge, 0 otherwise
	 */
	public int update(long gameTime, int power) {
		boolean nowOn = power > 0;
		if (!this.initialized) {
			this.initialized = true;
			this.on = nowOn;
			return 0;
		}
		if (nowOn == this.on) {
			return 0;
		}
		// Length of the on/off state that just ended.
		long since = nowOn ? this.lastFall : this.lastRise;
		long length = since == Long.MIN_VALUE ? -1 : gameTime - since;
		this.lastOnLength = nowOn ? -1 : length;
		this.lastOffLength = nowOn ? length : -1;
		this.on = nowOn;
		if (!nowOn) {
			// An on time is always real, however long (a slow clock, a lamp left on).
			if (length >= 0) {
				this.widths.add(length);
			}
			this.lastFall = gameTime;
			return -1;
		}
		// A long off time is a pause (the circuit stopped, or is being built): start over without
		// it. A slow clock isn't paused: its off times are like its other lengths.
		this.lastWasPause = length > Math.max(IDLE_RESET, 3 * Math.max(this.widths.max(), this.gaps.max()));
		if (this.lastWasPause) {
			this.widths.clear();
			this.gaps.clear();
		} else if (length >= 0) {
			this.gaps.add(length);
		}
		this.lastRise = gameTime;
		return 1;
	}

	/** The off time that ended with the latest rising edge was a pause (not checked, not shown). */
	public boolean lastWasPause() {
		return this.lastWasPause;
	}

	/** On length that ended with the latest falling edge (also a long one), else -1. */
	public long lastOnLength() {
		return this.lastOnLength;
	}

	/** Off length that ended with the latest rising edge (also a long one), else -1. */
	public long lastOffLength() {
		return this.lastOffLength;
	}

	public RunStats widths() {
		return this.widths;
	}

	public RunStats gaps() {
		return this.gaps;
	}

	/** "on 4t · off 4t" (t = game ticks); empty before the first pulse. */
	public String summary() {
		StringBuilder sb = new StringBuilder();
		if (!this.widths.isEmpty()) {
			sb.append("on ").append(Ticks.lengths(this.widths.values()));
		}
		if (!this.gaps.isEmpty()) {
			sb.append(sb.isEmpty() ? "" : " · ").append("off ").append(Ticks.lengths(this.gaps.values()));
		}
		return sb.toString();
	}
}
