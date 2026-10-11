package dev.redstonelab.core;

/**
 * Item entity label: "64 Stone · 4:31 left · pickup 8t". Uses the game's numbers: an item
 * despawns at age 6000 (age -32768 = never), pickup delay 32767 = can't be picked up.
 */
public final class ItemLife {
	public static final int LIFETIME = 6000;
	public static final int NEVER = -32768;
	public static final int NO_PICKUP = 32767;

	private ItemLife() {
	}

	/** Ticks until it despawns, or -1 for never. */
	public static int ticksLeft(int age) {
		return age == NEVER ? -1 : Math.max(0, LIFETIME - age);
	}

	public static String label(int count, String name, int age, int pickupDelay) {
		StringBuilder sb = new StringBuilder().append(count).append(' ').append(name).append(" · ");
		int left = ticksLeft(age);
		if (left < 0) {
			sb.append("never despawns");
		} else if (left < 200) {
			sb.append(left).append("t left");
		} else {
			int s = left / 20;
			sb.append(s / 60).append(':').append(s % 60 < 10 ? "0" : "").append(s % 60).append(" left");
		}
		if (pickupDelay == NO_PICKUP) {
			sb.append(" · no pickup");
		} else if (pickupDelay > 0) {
			sb.append(" · pickup ").append(pickupDelay).append('t');
		}
		return sb.toString();
	}

	/** Red under 30 s, white otherwise. */
	public static int color(int age) {
		int left = ticksLeft(age);
		return left >= 0 && left < 600 ? 0xFFFF5555 : 0xFFFFFFFF;
	}
}
