package dev.redstonelab.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** The most recent values (lengths in ticks), up to a fixed number. */
public final class RunStats {
	private final int window;
	private final ArrayDeque<Long> values = new ArrayDeque<>();

	public RunStats(int window) {
		this.window = Math.max(1, window);
	}

	public void add(long value) {
		this.values.addLast(value);
		while (this.values.size() > this.window) {
			this.values.removeFirst();
		}
	}

	public void clear() {
		this.values.clear();
	}

	public boolean isEmpty() {
		return this.values.isEmpty();
	}

	public long last() {
		return this.values.isEmpty() ? 0 : this.values.peekLast();
	}

	public List<Long> values() {
		return new ArrayList<>(this.values);
	}
}
