package dev.redstonelab.core;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The "Order" line of the timeline: "A C → 2t → B D → 2t → E F". Every meter's turn-ons are
 * measured from the first meter's turn-on just before them, so the line stays the same while a
 * clock keeps running. Meters whose delay keeps changing (they run at a different speed than the
 * first meter) are listed separately ("· own timing: E F") instead of jumping around.
 */
public final class OrderLine {
	/** Meters that didn't turn on for this long are left out. */
	public static final long MAX_AGE = 100;

	/** One meter: its letter and its recent turn-on times (any order, unused slots = Long.MIN_VALUE). */
	public record Input(String label, long[] rises) {
		long newest() {
			long best = Long.MIN_VALUE;
			for (long r : this.rises) {
				best = Math.max(best, r);
			}
			return best;
		}

		long oldest() {
			long best = Long.MAX_VALUE;
			for (long r : this.rises) {
				if (r != Long.MIN_VALUE) {
					best = Math.min(best, r);
				}
			}
			return best;
		}

		long atOrBefore(long time) {
			long best = Long.MIN_VALUE;
			for (long r : this.rises) {
				if (r != Long.MIN_VALUE && r <= time && r > best) {
					best = r;
				}
			}
			return best;
		}
	}

	private OrderLine() {
	}

	/** The line, or "" when fewer than two meters turned on recently. The first meter with any turn-on is the start. */
	public static String compute(List<Input> meters, long now) {
		Input anchor = null;
		for (Input m : meters) {
			if (m.newest() != Long.MIN_VALUE) {
				anchor = m;
				break;
			}
		}
		if (anchor == null) {
			return "";
		}
		TreeMap<Long, List<String>> groups = new TreeMap<>();
		List<String> ownTiming = new ArrayList<>();
		int total = 0;
		for (Input m : meters) {
			long rise = m.newest();
			if (rise == Long.MIN_VALUE || now - rise > MAX_AGE) {
				continue;
			}
			// The delay of every remembered turn-on (that the first meter's history still covers).
			Set<Long> delays = new HashSet<>();
			for (long r : m.rises()) {
				if (r == Long.MIN_VALUE || anchor.oldest() > r) {
					continue;
				}
				long start = anchor.atOrBefore(r);
				if (start != Long.MIN_VALUE && r - start <= MAX_AGE) {
					delays.add(r - start);
				}
			}
			if (delays.isEmpty()) {
				continue;
			}
			total++;
			if (delays.size() > 1) {
				ownTiming.add(m.label());
			} else {
				groups.computeIfAbsent(delays.iterator().next(), k -> new ArrayList<>()).add(m.label());
			}
		}
		if (total < 2) {
			return "";
		}
		StringBuilder sb = new StringBuilder();
		long previous = 0;
		for (Map.Entry<Long, List<String>> g : groups.entrySet()) {
			if (!sb.isEmpty()) {
				sb.append(" → ").append(g.getKey() - previous).append("t → ");
			}
			sb.append(String.join(" ", g.getValue()));
			previous = g.getKey();
		}
		if (!ownTiming.isEmpty()) {
			sb.append(" \u00b7 own timing: ").append(String.join(" ", ownTiming));
		}
		return sb.toString();
	}
}
