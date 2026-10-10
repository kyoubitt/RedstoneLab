package dev.redstonelab.core;

/**
 * What a meter reads from its block, packed in one int: the power level (0-15), whether the
 * block is <b>active</b> (doing its thing: lamp lit, piston extended, repeater output on...)
 * and whether it is <b>powered</b> (gets a redstone signal). Like Redstone Multimeter, the two
 * are separate: a repeater is powered 2 ticks before it turns on.
 */
public final class Reading {
	public static final int ACTIVE = 16;
	public static final int POWERED = 32;

	private Reading() {
	}

	public static int of(int level, boolean active, boolean powered) {
		return (level & 15) | (active ? ACTIVE : 0) | (powered ? POWERED : 0);
	}

	public static int level(int reading) {
		return reading & 15;
	}

	public static boolean active(int reading) {
		return (reading & ACTIVE) != 0;
	}

	public static boolean powered(int reading) {
		return (reading & POWERED) != 0;
	}
}
