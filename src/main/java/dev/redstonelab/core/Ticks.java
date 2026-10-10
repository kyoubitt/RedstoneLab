package dev.redstonelab.core;

import java.util.Locale;

/**
 * Number formatting for tick values.
 */
public final class Ticks {
	private Ticks() {
	}

	/**
	 * Exact tick lengths, never a range or "varies" (oldest value first):
	 * <ul>
	 *     <li>"6t": always the same,</li>
	 *     <li>"3t/5t", "2t/3t/3t": a pattern that repeats (2 to 4 steps), shown from its
	 *     smallest step so the text doesn't change every pulse,</li>
	 *     <li>"2t or 3t": no pattern, these are the lengths it had (up to 4 of them).</li>
	 * </ul>
	 */
	public static String exact(java.util.List<Long> values) {
		int n = values.size();
		if (n == 0) {
			return "";
		}
		for (int p = 1; p <= 4; p++) {
			// A pattern of p steps must be seen at least twice (once for p = 1), plus one step.
			if (n < (p == 1 ? 1 : p + 2)) {
				break;
			}
			boolean repeats = true;
			for (int i = p; i < n && repeats; i++) {
				repeats = values.get(i).equals(values.get(i - p));
			}
			if (repeats) {
				return cycle(values.subList(n - p, n));
			}
		}
		java.util.TreeSet<Long> distinct = new java.util.TreeSet<>(values);
		while (distinct.size() > 4) {
			// Keep the newest lengths.
			java.util.TreeSet<Long> newest = new java.util.TreeSet<>();
			for (int i = n - 1; i >= 0 && newest.size() < 4; i--) {
				newest.add(values.get(i));
			}
			distinct = newest;
		}
		StringBuilder sb = new StringBuilder();
		for (long v : distinct) {
			sb.append(sb.isEmpty() ? "" : " or ").append(v).append('t');
		}
		return sb.toString();
	}

	/** "2t/3t/3t": one round of a repeating pattern, starting from its smallest rotation. */
	private static String cycle(java.util.List<Long> round) {
		int p = round.size();
		int best = 0;
		for (int start = 1; start < p; start++) {
			for (int k = 0; k < p; k++) {
				int c = Long.compare(round.get((start + k) % p), round.get((best + k) % p));
				if (c != 0) {
					if (c < 0) {
						best = start;
					}
					break;
				}
			}
		}
		StringBuilder sb = new StringBuilder();
		for (int k = 0; k < p; k++) {
			sb.append(k == 0 ? "" : "/").append(round.get((best + k) % p)).append('t');
		}
		return sb.toString();
	}

	public static String decimal(double value) {
		if (value == Math.rint(value)) {
			return Long.toString((long) value);
		}
		return String.format(Locale.ROOT, "%.2f", value);
	}
}
