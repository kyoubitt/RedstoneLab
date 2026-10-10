package dev.redstonelab.core;

import java.util.Locale;

/**
 * Number formatting for tick values.
 */
public final class Ticks {
	private Ticks() {
	}

	/**
	 * Exact tick lengths, no ranges: "6t" when they are all the same, "5t/7t" when they
	 * alternate (5, 7, 5, 7...; smaller first so it doesn't flip every pulse), otherwise the newest one with "(varies)". Oldest value first.
	 */
	public static String exact(java.util.List<Long> values) {
		int n = values.size();
		if (n == 0) {
			return "";
		}
		long last = values.get(n - 1);
		boolean same = true;
		for (long v : values) {
			same &= v == last;
		}
		if (same) {
			return last + "t";
		}
		boolean alternating = n >= 3;
		for (int i = 2; i < n && alternating; i++) {
			alternating = values.get(i).equals(values.get(i - 2));
		}
		if (alternating) {
			long other = values.get(n - 2);
			return Math.min(other, last) + "t/" + Math.max(other, last) + "t";
		}
		return last + "t (varies)";
	}

	public static String decimal(double value) {
		if (value == Math.rint(value)) {
			return Long.toString((long) value);
		}
		return String.format(Locale.ROOT, "%.2f", value);
	}
}
