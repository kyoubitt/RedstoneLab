package dev.redstonelab.core;

/** Comparator level of a container like vanilla: floor(fullness * 14) + 1, or 0 when empty. */
public final class ContainerMath {
	private ContainerMath() {
	}

	/** Signal for {@code items} of one item type (stack size {@code maxStack}) in {@code slots} slots. */
	public static int signal(long items, int slots, int maxStack) {
		if (items <= 0 || slots <= 0 || maxStack <= 0) {
			return 0;
		}
		double fullness = Math.min(1.0, items / (double) maxStack / slots);
		return (int) Math.floor(fullness * 14) + 1;
	}

	/** How many items (of one type) give the next signal level, or -1 if already 15. */
	public static long itemsForNextLevel(int signal, int slots, int maxStack) {
		if (signal >= 15 || slots <= 0 || maxStack <= 0) {
			return -1;
		}
		if (signal <= 0) {
			return 1;
		}
		return (long) Math.ceil(signal * (double) slots * maxStack / 14.0);
	}
}
