package dev.redstonelab.core;

/**
 * Item entity label, two lines:
 * <pre>
 * 12 Redstone Dust
 * despawn 4:59 · pickup in 19t · hopper in 3t · 4.8 b/s
 * </pre>
 * The game's numbers: despawn at age 6000 (age -32768 = never), pickup delay 32767 = never.
 */
public final class ItemLife {
	public static final int LIFETIME = 6000;
	public static final int NEVER = -32768;
	public static final int NO_PICKUP = 32767;

	/** What the hopper under the item will do with it. */
	public enum Hopper {
		/** Not over a hopper. */
		NONE,
		/** Taken in exactly N ticks. */
		NEXT,
		/** Taken next, but something feeding the hopper can delay it. */
		NEXT_UNTIMED,
		/** Nth in line. */
		QUEUE,
		LOCKED,
		/** Doesn't fit. */
		FULL,
		/** A full block or container on top: it doesn't take items from above. */
		BLOCKED,
		/** In the bowl of a covered hopper that keeps moving items: taken when it's idle. */
		BUSY
	}

	private ItemLife() {
	}

	/** Ticks until it despawns, or -1 for never. */
	public static int ticksLeft(int age) {
		return age == NEVER ? -1 : Math.max(0, LIFETIME - age);
	}

	/** "19t" under 10 s, else "4:59". */
	public static String time(int ticks) {
		if (ticks < 200) {
			return ticks + "t";
		}
		int s = ticks / 20;
		return s / 60 + ":" + (s % 60 < 10 ? "0" : "") + s % 60;
	}

	/**
	 * @param hopperValue ticks until the hopper takes it (NEXT) or its place in line (QUEUE)
	 * @param speed       blocks per second
	 */
	public static String label(int count, String name, int age, int pickupDelay, Hopper hopper, int hopperValue, float speed) {
		StringBuilder sb = new StringBuilder();
		int left = ticksLeft(age);
		sb.append(left < 0 ? "never despawns" : "despawn " + time(left));
		if (pickupDelay == NO_PICKUP) {
			sb.append(" · no pickup");
		} else if (pickupDelay > 0) {
			sb.append(" · pickup in ").append(time(pickupDelay));
		}
		switch (hopper) {
			case NEXT -> sb.append(" · hopper in ").append(hopperValue).append('t');
			case QUEUE -> sb.append(" · hopper queue ").append(hopperValue);
			case LOCKED -> sb.append(" · hopper locked");
			case FULL -> sb.append(" · hopper full");
			case BLOCKED -> sb.append(" · hopper blocked");
			case NONE -> {
			}
		}
		if (speed >= 0.1f) {
			sb.append(" · ").append(Math.round(speed * 10) / 10f).append(" b/s");
		}
		return count + " " + name + "\n" + sb;
	}

	/** Red under 30 s, white otherwise. */
	public static int color(int age) {
		int left = ticksLeft(age);
		return left >= 0 && left < 600 ? 0xFFFF5555 : 0xFFFFFFFF;
	}
}
