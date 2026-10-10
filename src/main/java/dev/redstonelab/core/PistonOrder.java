package dev.redstonelab.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Firing order of the shown pistons: "#1", "#2 · +3t", "#3 · same tick". Like the meters' order
 * line, every piston's extensions are measured from the first shown piston's extension just
 * before them, so the numbers stay put while a clock keeps running. Pistons that extend in the
 * same tick are ordered by when the game ran them inside that tick.
 */
public final class PistonOrder {
	/** Pistons that didn't extend for this long are left out. */
	public static final long MAX_AGE = 100;
	private static final int HISTORY = 8;

	/** Recent extensions of one piston: game time and a sequence number (later = higher). */
	public static final class Fires {
		private final long[] times = new long[HISTORY];
		private final long[] seqs = new long[HISTORY];
		private int count;
		private int next;

		public void add(long time, long seq) {
			this.times[this.next] = time;
			this.seqs[this.next] = seq;
			this.next = (this.next + 1) % HISTORY;
			this.count = Math.min(this.count + 1, HISTORY);
		}

		public void clear() {
			this.count = 0;
			this.next = 0;
		}

		private int newestIndex() {
			return this.count == 0 ? -1 : (this.next + HISTORY - 1) % HISTORY;
		}

		long newest() {
			int i = this.newestIndex();
			return i < 0 ? Long.MIN_VALUE : this.times[i];
		}

		long newestSeq() {
			int i = this.newestIndex();
			return i < 0 ? Long.MIN_VALUE : this.seqs[i];
		}

		long oldest() {
			long best = Long.MAX_VALUE;
			for (int i = 0; i < this.count; i++) {
				best = Math.min(best, this.times[i]);
			}
			return best;
		}

		/** The newest extension at or before {@code time} (same tick counts if it came first or is this one). */
		long atOrBefore(long time) {
			long best = Long.MIN_VALUE;
			for (int i = 0; i < this.count; i++) {
				if (this.times[i] <= time && this.times[i] > best) {
					best = this.times[i];
				}
			}
			return best;
		}
	}

	private PistonOrder() {
	}

	/**
	 * One label per piston, in the same order as {@code pistons} (the first one shown is the
	 * start): "#1", "#2 · +3t" (3 ticks after the one before), "#3 · same tick" (same tick, run
	 * after the one before), "own timing" (its delay keeps changing) or "" (not extending
	 * lately). All "" when fewer than two pistons extended lately.
	 *
	 * @param exact the times are the server's; otherwise (measured on the client) a delay that
	 *              differs now and then is network jitter, so the most common one is used
	 */
	public static List<String> compute(List<Fires> pistons, long now, boolean exact) {
		List<String> labels = new ArrayList<>();
		pistons.forEach(p -> labels.add(""));
		Fires anchor = null;
		for (Fires f : pistons) {
			if (f.count > 0 && now - f.newest() <= MAX_AGE) {
				anchor = f;
				break;
			}
		}
		if (anchor == null) {
			return labels;
		}
		record Entry(int index, long delay, long time, long seq) {
		}
		List<Entry> ordered = new ArrayList<>();
		List<Integer> ownTiming = new ArrayList<>();
		for (int i = 0; i < pistons.size(); i++) {
			Fires f = pistons.get(i);
			if (f.count == 0 || now - f.newest() > MAX_AGE) {
				continue;
			}
			Map<Long, Integer> delays = new HashMap<>();
			for (int k = 0; k < f.count; k++) {
				long r = f.times[k];
				if (r < anchor.oldest()) {
					continue;
				}
				long start = anchor.atOrBefore(r);
				if (start != Long.MIN_VALUE && r - start <= MAX_AGE) {
					delays.merge(r - start, 1, Integer::sum);
				}
			}
			if (delays.isEmpty()) {
				continue;
			}
			if (exact && delays.size() > 1) {
				ownTiming.add(i);
				continue;
			}
			long delay = delays.entrySet().stream()
					.max((a, b) -> a.getValue().equals(b.getValue()) ? Long.compare(b.getKey(), a.getKey()) : a.getValue() - b.getValue())
					.get().getKey();
			ordered.add(new Entry(i, delay, f.newest(), f.newestSeq()));
		}
		if (ordered.size() + ownTiming.size() < 2) {
			return labels;
		}
		// Same delay = same tick: the one the game ran first goes first.
		ordered.sort((a, b) -> a.delay() != b.delay() ? Long.compare(a.delay(), b.delay())
				: a.time() == b.time() ? Long.compare(a.seq(), b.seq()) : 0);
		for (int n = 0; n < ordered.size(); n++) {
			Entry e = ordered.get(n);
			String label = "#" + (n + 1);
			if (n > 0) {
				long gap = e.delay() - ordered.get(n - 1).delay();
				label += gap == 0 ? " · same tick" : " · +" + gap + "t";
			}
			labels.set(e.index(), label);
		}
		for (int i : ownTiming) {
			labels.set(i, "own timing");
		}
		return labels;
	}
}
