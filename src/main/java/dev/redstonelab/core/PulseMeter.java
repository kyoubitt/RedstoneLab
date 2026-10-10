package dev.redstonelab.core;

/**
 * Pulse widths (on time) and gaps (off time) of a signal. On = power above 0. A rise and fall
 * in the same game tick is a 0-tick pulse.
 */
public final class PulseMeter {
	/** Ticks without any change after which the pulse lengths start over. */
	public static final long IDLE_RESET = 100;

	private final RunStats widths;
	private final RunStats gaps;

	private boolean initialized;
	private boolean on;
	private long lastRise = Long.MIN_VALUE;
	private long lastFall = Long.MIN_VALUE;
	private long lastOnLength = -1;
	private long lastOffLength = -1;

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
		// Length of the on/off state that just ended (kept even when the stats start over below).
		long since = nowOn ? this.lastFall : this.lastRise;
		long length = since == Long.MIN_VALUE ? -1 : gameTime - since;
		this.lastOnLength = nowOn ? -1 : length;
		this.lastOffLength = nowOn ? length : -1;
		// Quiet for a while: this is a new run, forget the old pulses (e.g. while building it).
		long lastEdge = Math.max(this.lastRise, this.lastFall);
		if (lastEdge != Long.MIN_VALUE && gameTime - lastEdge > IDLE_RESET) {
			this.widths.clear();
			this.gaps.clear();
			this.lastRise = Long.MIN_VALUE;
			this.lastFall = Long.MIN_VALUE;
		}
		this.on = nowOn;
		if (nowOn) {
			if (this.lastFall != Long.MIN_VALUE) {
				this.gaps.add(gameTime - this.lastFall);
			}
			this.lastRise = gameTime;
			return 1;
		}
		if (this.lastRise != Long.MIN_VALUE) {
			this.widths.add(gameTime - this.lastRise);
		}
		this.lastFall = gameTime;
		return -1;
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
			sb.append("on ").append(Ticks.exact(this.widths.values()));
		}
		if (!this.gaps.isEmpty()) {
			sb.append(sb.isEmpty() ? "" : " · ").append("off ").append(Ticks.exact(this.gaps.values()));
		}
		return sb.toString();
	}
}
