package dev.redstonelab.core;

/**
 * Number formatting for tick values.
 */
public final class Ticks {
	private Ticks() {
	}

	/** "6t" when it's always the same, "2-3t" when it varies (shortest-longest). */
	public static String lengths(java.util.List<Long> values) {
		if (values.isEmpty()) {
			return "";
		}
		long min = Long.MAX_VALUE;
		long max = Long.MIN_VALUE;
		for (long v : values) {
			min = Math.min(min, v);
			max = Math.max(max, v);
		}
		return min == max ? min + "t" : min + "-" + max + "t";
	}
}
