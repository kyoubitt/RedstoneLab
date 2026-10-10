package dev.redstonelab.core;

/**
 * Renders power levels (0-15) as a unicode sparkline for chat / bossbar output.
 */
public final class Sparkline {
	private static final char[] BARS = {'_', '▁', '▂', '▃', '▄', '▅', '▆', '▇', '█'};

	private Sparkline() {
	}

	public static char bar(int power) {
		if (power <= 0) {
			return BARS[0];
		}
		int idx = 1 + (Math.min(power, 15) - 1) * (BARS.length - 2) / 14;
		return BARS[idx];
	}

	/**
	 * @param values ring of samples, oldest first
	 * @param count  how many of the newest samples to render
	 */
	public static String render(int[] values, int count) {
		int start = Math.max(0, values.length - count);
		StringBuilder sb = new StringBuilder(values.length - start);
		for (int i = start; i < values.length; i++) {
			sb.append(bar(values[i]));
		}
		return sb.toString();
	}
}
