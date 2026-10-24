package dev.redstonelab.feature;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;

import dev.redstonelab.core.PulseMeter;
import dev.redstonelab.core.Reading;
import dev.redstonelab.core.Ticks;

/**
 * What the metered blocks really did, read from the game every tick (lamp lit, repeater
 * powered, piston extended...), and what the meters should therefore say: their on / off
 * every tick, their pulse lengths and the order line. Worked out here from the recorded
 * states, independently of the meter code (only the number format is shared).
 */
final class CircuitTruth {
	private final ServerLevel level;
	private final ServerPlayer player;
	/** Per meter: the game's on / off at every sample (null = can't tell, e.g. mid-move). */
	private final Map<String, List<Boolean>> states = new LinkedHashMap<>();
	private final List<Long> times = new ArrayList<>();
	/** Per meter: the game time of each of its samples (meters can be added part way). */
	private final Map<String, List<Long>> labelTimes = new LinkedHashMap<>();
	/** Meters whose text has a 0-tick length: their lengths can't be checked by sampling. */
	final List<String> zeroTick = new ArrayList<>();
	int checks;
	int wrong;
	String firstWrong = "";

	CircuitTruth(ServerLevel level, ServerPlayer player) {
		this.level = level;
		this.player = player;
	}

	/** Call once per tick: records the game's state and checks every meter's on / off against it. */
	void sample() {
		this.times.add(this.level.getGameTime());
		for (String label : MeterFeature.labels(this.player)) {
			BlockPos pos = MeterFeature.posForTest(this.player, label);
			// A piston that is retracting (or being pushed) is a moving block: not out.
			Boolean on = this.level.getBlockState(pos).is(Blocks.MOVING_PISTON) ? Boolean.FALSE
					: RedstoneLabGameTests.gameSaysOn(this.level, pos);
			this.states.computeIfAbsent(label, k -> new ArrayList<>()).add(on);
			this.labelTimes.computeIfAbsent(label, k -> new ArrayList<>()).add(this.level.getGameTime());
			if (on != null) {
				this.checks++;
				boolean meter = Reading.active(MeterFeature.readingForTest(this.player, label));
				if (meter != on) {
					this.wrong++;
					if (this.firstWrong.isEmpty()) {
						this.firstWrong = label + " at " + this.level.getGameTime() + ": meter " + meter + ", game " + on
								+ " (" + this.level.getBlockState(pos) + ")";
					}
				}
			}
		}
	}

	/** Turn-on times of a meter (game time of the first sample where it is on). */
	List<Long> rises(String label) {
		List<Boolean> s = this.states.getOrDefault(label, List.of());
		List<Long> t = this.labelTimes.getOrDefault(label, List.of());
		List<Long> rises = new ArrayList<>();
		for (int i = 1; i < s.size(); i++) {
			if (Boolean.TRUE.equals(s.get(i)) && Boolean.FALSE.equals(s.get(i - 1))) {
				rises.add(t.get(i));
			}
		}
		return rises;
	}

	/** "on 2t · off 4t" as the meter should say it: the last 8 lengths, a long pause starts over. */
	String pulses(String label) {
		List<Boolean> s = this.states.getOrDefault(label, List.of());
		List<Long> on = new ArrayList<>();
		List<Long> off = new ArrayList<>();
		long lastEdge = Long.MIN_VALUE;
		for (int i = 1; i < s.size(); i++) {
			Boolean now = s.get(i);
			Boolean before = s.get(i - 1);
			if (now == null || before == null || now.equals(before)) {
				continue;
			}
			long t = this.labelTimes.get(label).get(i);
			if (lastEdge != Long.MIN_VALUE) {
				long length = t - lastEdge;
				if (now) {
					long longest = Math.max(max(on), max(off));
					if (length > Math.max(PulseMeter.IDLE_RESET, 3 * longest)) {
						on.clear();
						off.clear();
					} else {
						off.add(length);
					}
				} else {
					on.add(length);
				}
			}
			lastEdge = t;
		}
		StringBuilder sb = new StringBuilder();
		if (!on.isEmpty()) {
			sb.append("on ").append(Ticks.lengths(last8(on)));
		}
		if (!off.isEmpty()) {
			sb.append(sb.isEmpty() ? "" : " · ").append("off ").append(Ticks.lengths(last8(off)));
		}
		return sb.toString();
	}

	/**
	 * The order line: every meter's turn-ons measured from A's turn-on at or before them; one
	 * delay = a place in the line, several = "own timing".
	 */
	String order(List<String> labels) {
		long now = this.times.get(this.times.size() - 1);
		// The start: the first meter that turned on lately (within 2 periods, at least 100t).
		List<Long> anchor = List.of();
		for (String label : labels) {
			if (this.recent(label, now)) {
				anchor = this.rises(label);
				break;
			}
		}
		if (anchor.isEmpty()) {
			return "";
		}
		long maxAge = Math.max(100, 2 * period(anchor));
		TreeMap<Long, List<String>> groups = new TreeMap<>();
		List<String> own = new ArrayList<>();
		int total = 0;
		for (String label : labels) {
			List<Long> rises = this.rises(label);
			if (rises.isEmpty() || !this.recent(label, now) && now - rises.get(rises.size() - 1) > maxAge) {
				continue;
			}
			Set<Long> delays = new HashSet<>();
			List<Long> kept = anchor.subList(Math.max(0, anchor.size() - 8), anchor.size());
			for (long r : rises.subList(Math.max(0, rises.size() - 8), rises.size())) {
				long start = Long.MIN_VALUE;
				for (long a : kept) {
					if (a <= r) {
						start = a;
					}
				}
				if (start != Long.MIN_VALUE && r - start <= maxAge && r >= kept.get(0)) {
					delays.add(r - start);
				}
			}
			total += delays.isEmpty() ? 0 : 1;
			if (delays.size() == 1) {
				groups.computeIfAbsent(delays.iterator().next(), k -> new ArrayList<>()).add(label);
			} else if (delays.size() > 1) {
				own.add(label);
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
		if (!own.isEmpty()) {
			sb.append(" · own timing: ").append(String.join(" ", own));
		}
		return sb.toString();
	}

	private boolean recent(String label, long now) {
		List<Long> rises = this.rises(label);
		return !rises.isEmpty() && now - rises.get(rises.size() - 1) <= Math.max(100, 2 * period(rises));
	}

	private static long period(List<Long> rises) {
		return rises.size() < 2 ? 0 : rises.get(rises.size() - 1) - rises.get(rises.size() - 2);
	}

	/** Every meter's pulse text and the order line must match; returns what differs, or "". */
	String compare() {
		return this.compare(true);
	}

	/**
	 * Like {@link #compare()}; without the order line for circuits driven by arbitrary inputs
	 * (not a repeating signal), where it has nothing steady to show.
	 */
	String compare(boolean withOrder) {
		StringBuilder sb = new StringBuilder();
		List<String> labels = MeterFeature.labels(this.player);
		for (String label : labels) {
			String want = this.pulses(label);
			String got = MeterFeature.pulsesForTest(this.player, label);
			if (got.matches(".*\\b0(t|-\\d+t).*")) {
				// A 0-tick pulse (on and off inside one tick) can't be seen sampling once a tick.
				this.zeroTick.add(label);
				continue;
			}
			if (!want.equals(got)) {
				sb.append(label).append(": meter '").append(got).append("', game '").append(want).append("'. ");
			}
		}
		String want = withOrder ? this.order(labels) : "";
		String got = withOrder ? MeterFeature.orderForTest(this.player, this.level.getGameTime()) : "";
		for (String z : this.zeroTick) {
			// Its turn-ons after 0-tick offs are invisible here: leave it out of the meter's line too.
			got = got.replaceAll("(?<=^| )" + z + "(?= |$)", "").replaceAll(" {2,}", " ").trim();
		}
		// Meters turning on in the same tick are listed in game order; the truth only knows the tick.
		if (!sameTickSorted(want).equals(sameTickSorted(got))) {
			sb.append("order: meter '").append(got).append("', game '").append(want).append("'. ");
		}
		if (this.wrong > 0) {
			sb.append(this.wrong).append('/').append(this.checks).append(" ticks wrong, first ").append(this.firstWrong);
		}
		return sb.toString();
	}

	/** "C A → 2t → B" -> "A C → 2t → B": letters inside each same-tick group sorted. */
	static String sameTickSorted(String line) {
		String own = "";
		int at = line.indexOf(" \u00b7 own timing: ");
		if (at >= 0) {
			own = line.substring(at);
			line = line.substring(0, at);
		}
		String[] parts = line.split(" \u2192 ");
		for (int i = 0; i < parts.length; i++) {
			if (!parts[i].matches("\\d+t")) {
				String[] letters = parts[i].split(" ");
				Arrays.sort(letters);
				parts[i] = String.join(" ", letters);
			}
		}
		return String.join(" \u2192 ", parts) + own;
	}

	private static long max(List<Long> values) {
		long max = 0;
		for (long v : values) {
			max = Math.max(max, v);
		}
		return max;
	}

	private static List<Long> last8(List<Long> values) {
		return values.subList(Math.max(0, values.size() - 8), values.size());
	}
}
