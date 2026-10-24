package dev.redstonelab.core;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The "Order" line: "A C -> 2t -> B D". Each meter's turn-ons are measured from the first meter's turn-on
 * just before them, so the line stays put while a clock runs. Meters whose delay keeps changing
 * are listed apart ("own timing").
 */
public final class OrderLine {
	/** Meters that didn't turn on for this long (or 2 periods of the first meter, if longer) are left out. */
	public static final long MAX_AGE = 100;

	/** One meter: letter, recent turn-on times (unused = Long.MIN_VALUE) and each one's place in the game's order inside its tick. */
	public record Input(String label, long[] rises, int[] seqs) {
		public Input(String label, long[] rises) {
			this(label, rises, new int[rises.length]);
		}

		/** Place inside its tick of the newest turn-on. */
		int newestSeq() {
			long newest = this.newest();
			for (int i = 0; i < this.rises.length; i++) {
				if (this.rises[i] == newest) {
					return this.seqs[i];
				}
			}
			return 0;
		}

		long newest() {
			long best = Long.MIN_VALUE;
			for (long r : this.rises) {
				best = Math.max(best, r);
			}
			return best;
		}

		/** Time between the two newest turn-ons, or 0. */
		long period() {
			long newest = this.newest();
			long before = Long.MIN_VALUE;
			for (long r : this.rises) {
				if (r != Long.MIN_VALUE && r < newest && r > before) {
					before = r;
				}
			}
			return before == Long.MIN_VALUE ? 0 : newest - before;
		}

		/** Turned on lately: within 2 of its own periods (at least {@link #MAX_AGE}). */
		boolean recent(long now) {
			long newest = this.newest();
			return newest != Long.MIN_VALUE && now - newest <= Math.max(MAX_AGE, 2 * this.period());
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

	/** The line, or "" when fewer than two meters turned on lately. The start is the first meter that did. */
	public static String compute(List<Input> meters, long now) {
		Input anchor = null;
		for (Input m : meters) {
			if (m.recent(now)) {
				anchor = m;
				break;
			}
		}
		if (anchor == null) {
			return "";
		}
		// A slow clock (hopper clock) still counts as running for 2 of its periods.
		long maxAge = Math.max(MAX_AGE, 2 * anchor.period());
		// Same delay = same tick: listed in the order the game turned them on inside that tick.
		TreeMap<Long, List<Input>> groups = new TreeMap<>();
		List<String> ownTiming = new ArrayList<>();
		int total = 0;
		for (Input m : meters) {
			if (!m.recent(now) && now - m.newest() > maxAge) {
				continue;
			}
			// The delay of every remembered turn-on (that the first meter's history still covers).
			Set<Long> delays = new HashSet<>();
			for (long r : m.rises()) {
				if (r == Long.MIN_VALUE || anchor.oldest() > r) {
					continue;
				}
				long start = anchor.atOrBefore(r);
				if (start != Long.MIN_VALUE && r - start <= maxAge) {
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
				groups.computeIfAbsent(delays.iterator().next(), k -> new ArrayList<>()).add(m);
			}
		}
		if (total < 2) {
			return "";
		}
		StringBuilder sb = new StringBuilder();
		long previous = 0;
		for (Map.Entry<Long, List<Input>> g : groups.entrySet()) {
			if (!sb.isEmpty()) {
				sb.append(" → ").append(g.getKey() - previous).append("t → ");
			}
			List<Input> group = g.getValue();
			group.sort((a, b) -> a.newest() != b.newest() ? Long.compare(a.newest(), b.newest()) : Integer.compare(a.newestSeq(), b.newestSeq()));
			List<String> names = new ArrayList<>();
			group.forEach(m -> names.add(m.label()));
			sb.append(String.join(" ", names));
			previous = g.getKey();
		}
		if (!ownTiming.isEmpty()) {
			sb.append(" \u00b7 own timing: ").append(String.join(" ", ownTiming));
		}
		return sb.toString();
	}
}
