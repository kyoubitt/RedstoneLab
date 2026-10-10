package dev.redstonelab.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Running min / max / average / standard deviation over a window of the most recent values.
 */
public final class RunStats {
	private final int window;
	private final ArrayDeque<Long> values = new ArrayDeque<>();
	private long total;

	public RunStats(int window) {
		this.window = Math.max(1, window);
	}

	public void add(long value) {
		this.values.addLast(value);
		this.total++;
		while (this.values.size() > this.window) {
			this.values.removeFirst();
		}
	}

	public void clear() {
		this.values.clear();
		this.total = 0;
	}

	/** Values in the current window. */
	public int count() {
		return this.values.size();
	}

	/** Values ever added (including ones that left the window). */
	public long totalCount() {
		return this.total;
	}

	public boolean isEmpty() {
		return this.values.isEmpty();
	}

	public long min() {
		long min = Long.MAX_VALUE;
		for (long v : this.values) {
			min = Math.min(min, v);
		}
		return this.values.isEmpty() ? 0 : min;
	}

	public long max() {
		long max = Long.MIN_VALUE;
		for (long v : this.values) {
			max = Math.max(max, v);
		}
		return this.values.isEmpty() ? 0 : max;
	}

	public double average() {
		if (this.values.isEmpty()) {
			return 0;
		}
		double sum = 0;
		for (long v : this.values) {
			sum += v;
		}
		return sum / this.values.size();
	}

	public double stdDev() {
		int n = this.values.size();
		if (n < 2) {
			return 0;
		}
		double avg = this.average();
		double sq = 0;
		for (long v : this.values) {
			sq += (v - avg) * (v - avg);
		}
		return Math.sqrt(sq / (n - 1));
	}

	/** max - min over the window. */
	public long spread() {
		return this.max() - this.min();
	}

	/** True when at least two values differ by more than {@code threshold} ticks. */
	public boolean exceedsVariance(long threshold) {
		return this.values.size() >= 2 && this.spread() > threshold;
	}

	public long last() {
		return this.values.isEmpty() ? 0 : this.values.peekLast();
	}

	public List<Long> values() {
		return new ArrayList<>(this.values);
	}

	/** "min 4 / max 6 / avg 4.67 gt" */
	public String summary(String unit) {
		if (this.values.isEmpty()) {
			return "no data";
		}
		return "min " + this.min() + " / max " + this.max() + " / avg " + Ticks.decimal(this.average()) + " " + unit;
	}
}
