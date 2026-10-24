package dev.redstonelab.feature;

import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.ChiseledBookShelfBlock;
import net.minecraft.world.level.block.ComparatorBlock;
import net.minecraft.world.level.block.CrafterBlock;
import net.minecraft.world.level.block.JukeboxBlock;
import net.minecraft.world.level.block.ShelfBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.ComparatorMode;
import dev.redstonelab.core.ContainerMath;
import dev.redstonelab.mixin.DiodeBlockInvoker;
import dev.redstonelab.util.Fmt;
import dev.redstonelab.util.PowerUtil;

/** Texts and counts about the inventory of a metered block. */
final class ContainerInfo {
	private ContainerInfo() {
	}

	/**
	 * "3 at 83": the next comparator level and the item count that gives it. Only when exact: the usual fullness
	 * signal (not crafter, bookshelf, jukebox, shelf or blocked chest) and equal stack sizes.
	 */
	static String nextLevel(ServerLevel level, BlockPos pos, int signal) {
		Container container = loadedAround(level, pos) ? PowerUtil.container(level, pos) : null;
		if (container == null || signal != AbstractContainerMenu.getRedstoneSignalFromContainer(container)
				|| level.getBlockState(pos).getBlock() instanceof CrafterBlock
				|| level.getBlockState(pos).getBlock() instanceof ChiseledBookShelfBlock
				|| level.getBlockState(pos).getBlock() instanceof JukeboxBlock
				|| level.getBlockState(pos).getBlock() instanceof ShelfBlock) {
			return "";
		}
		int maxStack = -1;
		for (int i = 0; i < container.getContainerSize(); i++) {
			ItemStack stack = container.getItem(i);
			if (!stack.isEmpty()) {
				int size = Math.min(container.getMaxStackSize(stack), stack.getMaxStackSize());
				if (maxStack >= 0 && size != maxStack) {
					return "";
				}
				maxStack = size;
			}
		}
		long need = ContainerMath.itemsForNextLevel(signal, container.getContainerSize(), maxStack < 0 ? 64 : maxStack);
		return need < 0 ? "max" : (signal + 1) + " at " + need;
	}

	/** Comparator text ("subtract - back 15 - side 14 - out 1"), inputs read like the game does. Empty for other blocks. */
	static String comparatorInfo(ServerLevel level, BlockPos pos, int out) {
		// The inputs are read up to 2 blocks away: don't make the game load chunks for that.
		if (!level.hasChunkAt(pos.offset(-2, 0, -2)) || !level.hasChunkAt(pos.offset(2, 0, 2))
				|| !level.hasChunkAt(pos.offset(-2, 0, 2)) || !level.hasChunkAt(pos.offset(2, 0, -2))) {
			return "";
		}
		BlockState state = level.getBlockState(pos);
		if (!(state.getBlock() instanceof ComparatorBlock)) {
			return "";
		}
		DiodeBlockInvoker diode = (DiodeBlockInvoker) state.getBlock();
		int back = diode.redstonelab$backInput(level, pos, state);
		int side = diode.redstonelab$sideInput(level, pos, state);
		boolean subtract = state.getValue(BlockStateProperties.MODE_COMPARATOR)
				== ComparatorMode.SUBTRACT;
		// The output follows the inputs 2 ticks later, so show the three values as they are now.
		return (subtract ? "subtract" : "compare") + " \u00b7 back " + back + " \u00b7 side " + side + " \u00b7 out " + out;
	}

	/** What is inside: "10 Stone", "10 Stone + 3 Dirt" or "20 items, 4 kinds". Empty if empty or not a container. */
	static String contents(ServerLevel level, BlockPos pos) {
		Container container = loadedAround(level, pos) ? PowerUtil.container(level, pos) : null;
		if (container == null) {
			return "";
		}
		Map<Item, Integer> kinds = new LinkedHashMap<>();
		int total = 0;
		for (int i = 0; i < container.getContainerSize(); i++) {
			ItemStack stack = container.getItem(i);
			if (!stack.isEmpty()) {
				kinds.merge(stack.getItem(), stack.getCount(), Integer::sum);
				total += stack.getCount();
			}
		}
		if (kinds.isEmpty()) {
			return "";
		}
		if (kinds.size() > 2) {
			return total + " items, " + kinds.size() + " kinds";
		}
		StringBuilder sb = new StringBuilder();
		for (Map.Entry<Item, Integer> e : kinds.entrySet()) {
			sb.append(sb.isEmpty() ? "" : " + ").append(e.getValue()).append(' ').append(Fmt.pretty(e.getKey()));
		}
		return sb.toString();
	}

	/** The block and everything next to it is loaded: reading it never makes the game load a chunk. */
	static boolean loadedAround(ServerLevel level, BlockPos pos) {
		return level.hasChunksAt(pos.offset(-1, 0, -1), pos.offset(1, 0, 1));
	}

	/** Items inside a hopper, chest (both halves), dropper..., or -1 if the block is not a container. */
	static int itemCount(ServerLevel level, BlockPos pos) {
		Container container = loadedAround(level, pos) ? PowerUtil.container(level, pos) : null;
		if (container == null) {
			return -1;
		}
		int items = 0;
		for (int i = 0; i < container.getContainerSize(); i++) {
			items += container.getItem(i).getCount();
		}
		return items;
	}
}
