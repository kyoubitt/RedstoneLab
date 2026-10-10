package dev.redstonelab.core;

/**
 * Measures pulse width (on time), gap (off time) and period of a signal from power level
 * changes. A block counts as "on" when its power level is above 0.
 *
 * <p>Changes can be fed with sub-tick precision: a rise and fall within the same game tick
 * produces a 0 gt pulse (a "0-tick pulse").</p>
 */
public final class PulseMeter {
	private final RunStats widths;
	private final RunStats gaps;
	private final RunStats periods;

	private boolean initialized;
	private boolean on;
	private long lastRise = Long.MIN_VALUE;
	private long lastFall = Long.MIN_VALUE;
	private long pulses;
	private long lastOnLength = -1;
	private long lastOffLength = -1;

	/** Ticks without any change after which the pulse lengths start over. */
	public static final long IDLE_RESET = 100;

	public PulseMeter(int window) {
		this.widths = new RunStats(window);
		this.gaps = new RunStats(window);
		this.periods = new RunStats(window);
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
			this.periods.clear();
			this.lastRise = Long.MIN_VALUE;
			this.lastFall = Long.MIN_VALUE;
		}
		this.on = nowOn;
		if (nowOn) {
			if (this.lastFall != Long.MIN_VALUE) {
				this.gaps.add(gameTime - this.lastFall);
			}
			if (this.lastRise != Long.MIN_VALUE) {
				this.periods.add(gameTime - this.lastRise);
			}
			this.lastRise = gameTime;
			return 1;
		} else {
			if (this.lastRise != Long.MIN_VALUE) {
				this.widths.add(gameTime - this.lastRise);
				this.pulses++;
			}
			this.lastFall = gameTime;
			return -1;
		}
	}

	public void reset() {
		this.initialized = false;
		this.on = false;
		this.lastRise = Long.MIN_VALUE;
		this.lastFall = Long.MIN_VALUE;
		this.pulses = 0;
		this.lastOnLength = -1;
		this.lastOffLength = -1;
		this.widths.clear();
		this.gaps.clear();
		this.periods.clear();
	}

	/** On length that ended with the latest falling edge (also a long one), else -1. */
	public long lastOnLength() {
		return this.lastOnLength;
	}

	/** Off length that ended with the latest rising edge (also a long one), else -1. */
	public long lastOffLength() {
		return this.lastOffLength;
	}

	public boolean isOn() {
		return this.on;
	}

	public long pulseCount() {
		return this.pulses;
	}

	public RunStats widths() {
		return this.widths;
	}

	public RunStats gaps() {
		return this.gaps;
	}

	public RunStats periods() {
		return this.periods;
	}

	/** Duration of the current on/off state so far. */
	public long currentStateDuration(long gameTime) {
		long since = this.on ? this.lastRise : this.lastFall;
		return since == Long.MIN_VALUE ? -1 : gameTime - since;
	}

	/**
	 * A clock is flagged inconsistent once at least 3 periods were measured and the pulse
	 * width or period varies by more than {@code tolerance} game ticks.
	 */
	public boolean isInconsistentClock(long tolerance) {
		if (this.periods.count() < 3) {
			return false;
		}
		return this.periods.spread() > tolerance || this.widths.spread() > tolerance;
	}

	/** Short summary, e.g. "on 4t \u00b7 off 4t" (t = game ticks). Empty before the first pulse. */
	public String summary() {
		if (this.widths.isEmpty() && this.gaps.isEmpty()) {
			return "";
		}
		StringBuilder sb = new StringBuilder();
		if (!this.widths.isEmpty()) {
			sb.append("on ").append(Ticks.exact(this.widths.values()));
		}
		if (!this.gaps.isEmpty()) {
			sb.append(sb.isEmpty() ? "" : " \u00b7 ").append("off ").append(Ticks.exact(this.gaps.values()));
		}
		return sb.toString();
	}



}
