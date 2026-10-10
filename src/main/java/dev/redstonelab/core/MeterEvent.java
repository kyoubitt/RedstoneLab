package dev.redstonelab.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Things that can happen to a watched block in one tick, shown as coloured marks on its
 * timeline row (like Redstone Multimeter). Several can happen in the same tick, so they are
 * stored as a bit set per tick.
 */
public enum MeterEvent {
	/** Became active (lamp lit, piston extended, repeater output on...). */
	TURNED_ON("on", 'I', 0xFFFF3333),
	TURNED_OFF("off", 'O', 0xFF888888),
	/** Started / stopped getting a redstone signal. */
	POWERED("powered", 'p', 0xFFFF7777),
	UNPOWERED("unpowered", 'u', 0xFF666666),
	POWER_CHANGED("power change", 'P', 0xFFFF9999),
	MOVED("moved", 'M', 0xFFFFFF55),
	PLACED("placed", '+', 0xFF55FF55),
	BROKEN("broken", 'x', 0xFFFF55FF),
	SCHEDULED_TICK("tick", 'T', 0xFFFFAA00),
	BLOCK_EVENT("block event", 'E', 0xFFDD55FF),
	BLOCK_UPDATE("update", 'U', 0xFF55AAFF),
	SHAPE_UPDATE("shape update", 'S', 0xFF00DDDD),
	ITEM_IN("item in", '>', 0xFFB4FF64),
	ITEM_OUT("item out", '<', 0xFFC88A46),
	/** A player right-clicked the block (lever, button, repeater delay...). */
	CLICKED("clicked", 'C', 0xFFFFFFFF),
	/** A pulse ended (or started) with a different length than the one the player expects. */
	WRONG_LENGTH("wrong length", '!', 0xFFFF0000);

	private final String displayName;
	private final char letter;
	private final int color;

	MeterEvent(String displayName, char letter, int color) {
		this.displayName = displayName;
		this.letter = letter;
		this.color = color;
	}

	public int bit() {
		return 1 << this.ordinal();
	}

	public String displayName() {
		return this.displayName;
	}

	public char letter() {
		return this.letter;
	}

	/** ARGB. */
	public int color() {
		return this.color;
	}

	public boolean in(int flags) {
		return (flags & this.bit()) != 0;
	}

	public static List<MeterEvent> of(int flags) {
		List<MeterEvent> list = new ArrayList<>();
		for (MeterEvent e : values()) {
			if (e.in(flags)) {
				list.add(e);
			}
		}
		return list;
	}

	/** "turned on, block update". */
	public static String describe(int flags) {
		StringBuilder sb = new StringBuilder();
		for (MeterEvent e : of(flags)) {
			if (!sb.isEmpty()) {
				sb.append(", ");
			}
			sb.append(e.displayName);
		}
		return sb.toString();
	}
}
