package dev.redstonelab.feature;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import net.minecraft.gametest.framework.GameTestHelper;

/**
 * What the test does each tick, in a fixed order: first the actions (redstone blocks placed
 * or removed), then the probes (truth samples, preview checks). The framework runs separate
 * callbacks of one tick in no fixed order, so they all go through one callback per tick.
 */
final class TestScript {
	private final Map<Integer, List<Runnable>> actions = new TreeMap<>();
	private final List<Runnable> probes = new ArrayList<>();

	void at(int tick, Runnable action) {
		this.actions.computeIfAbsent(tick, k -> new ArrayList<>()).add(action);
	}

	void every(Runnable probe) {
		this.probes.add(probe);
	}

	/** Runs ticks {@code from} to {@code to} (exclusive); then {@code end} at {@code to}. */
	void run(GameTestHelper helper, int from, int to, Runnable end) {
		for (int t = 1; t < to; t++) {
			int tick = t;
			helper.runAtTickTime(t, () -> {
				this.actions.getOrDefault(tick, List.of()).forEach(Runnable::run);
				if (tick >= from) {
					this.probes.forEach(Runnable::run);
				}
			});
		}
		helper.runAtTickTime(to, end);
	}
}

