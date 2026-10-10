package dev.redstonelab.util;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Plain-string formatting.
 */
public final class Fmt {
	private Fmt() {
	}

	public static String pos(BlockPos pos) {
		return pos.getX() + " " + pos.getY() + " " + pos.getZ();
	}

	/** "redstone_wire" -> "Redstone Wire". */
	public static String pretty(String id) {
		StringBuilder sb = new StringBuilder(id.length());
		boolean up = true;
		for (char c : id.toCharArray()) {
			if (c == '_') {
				sb.append(' ');
				up = true;
			} else {
				sb.append(up ? Character.toUpperCase(c) : c);
				up = false;
			}
		}
		return sb.toString();
	}

	public static String pretty(net.minecraft.world.item.Item item) {
		return pretty(BuiltInRegistries.ITEM.getKey(item).getPath());
	}

	public static String pretty(BlockState state) {
		return pretty(BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath());
	}
}
