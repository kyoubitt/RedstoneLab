package dev.redstonelab.util;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.ComparatorBlock;
import net.minecraft.world.level.block.DiodeBlock;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.RedstoneTorchBlock;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ComparatorBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import dev.redstonelab.core.Reading;
import dev.redstonelab.mixin.DiodeBlockInvoker;
import dev.redstonelab.mixin.PistonBaseBlockInvoker;
import dev.redstonelab.mixin.RedstoneTorchBlockInvoker;

/**
 * "Power level" of a block as shown by RedstoneLab:
 * <ul>
 *     <li>redstone dust: its POWER property</li>
 *     <li>comparator: its block entity output signal</li>
 *     <li>other signal sources (repeater, torch, lever, button, observer, ...): the strongest
 *     signal they emit in any direction</li>
 *     <li>containers: what a comparator reads from them (vanilla's own output)</li>
 *     <li>pistons: the signal the piston itself reacts to, including QC (power next to the
 *     block above it), exactly like the game checks it</li>
 *     <li>everything else: the strongest signal the block receives</li>
 * </ul>
 */
public final class PowerUtil {
	private static final Direction[] DIRECTIONS = Direction.values();

	private PowerUtil() {
	}

	public static int powerAt(Level level, BlockPos pos) {
		return powerAt(level, pos, level.getBlockState(pos));
	}

	public static int powerAt(Level level, BlockPos pos, BlockState state) {
		if (state.getBlock() instanceof RedStoneWireBlock) {
			return state.getValue(RedStoneWireBlock.POWER);
		}
		if (state.getBlock() instanceof ComparatorBlock) {
			BlockEntity be = level.getBlockEntity(pos);
			if (be instanceof ComparatorBlockEntity comparator) {
				return comparator.getOutputSignal();
			}
			return 0;
		}
		if (state.hasBlockEntity() && level.getBlockEntity(pos) instanceof Container container) {
			// What a comparator reads (double chests, blocked chests, crafters, bookshelves...).
			return state.hasAnalogOutputSignal() ? comparatorSignal(level, pos, state)
					: AbstractContainerMenu.getRedstoneSignalFromContainer(container);
		}
		if (state.isSignalSource()) {
			int max = 0;
			for (Direction d : DIRECTIONS) {
				max = Math.max(max, state.getSignal(level, pos, d));
				if (max >= 15) {
					break;
				}
			}
			return max;
		}
		if (state.getBlock() instanceof PistonBaseBlock && state.hasProperty(PistonBaseBlock.FACING)) {
			return pistonPower(level, pos, state.getValue(PistonBaseBlock.FACING));
		}
		return level.getBestNeighborSignal(pos);
	}

	/** What a comparator facing away from the block reads (the best side: shelves only give it to the front). */
	public static int comparatorSignal(Level level, BlockPos pos, BlockState state) {
		int max = 0;
		for (Direction d : DIRECTIONS) {
			max = Math.max(max, state.getAnalogOutputSignal(level, pos, d));
		}
		return max;
	}

	/** The items a block holds; a double chest is both halves, like comparators and hoppers see it. */
	public static @org.jetbrains.annotations.Nullable Container container(Level level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		if (state.getBlock() instanceof ChestBlock chest) {
			return ChestBlock.getContainer(chest, state, level, pos, true);
		}
		return level.getBlockEntity(pos) instanceof Container c ? c : null;
	}

	/** A chest comparators can't read (solid block or cat on top of it or its other half): signal 0. */
	public static boolean chestBlocked(Level level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		return state.getBlock() instanceof ChestBlock chest && ChestBlock.getContainer(chest, state, level, pos, false) == null;
	}

	/**
	 * Power level plus active / powered, like Redstone Multimeter. Powered uses the block's own
	 * check where it has one (repeater input, torch, piston incl. QC, dispenser incl. QC).
	 */
	public static int read(Level level, BlockPos pos, BlockState state) {
		if (state.is(Blocks.MOVING_PISTON)) {
			// Being moved by a piston: nothing to measure until it lands.
			return Reading.of(0, false, false);
		}
		int power = powerAt(level, pos, state);
		Block block = state.getBlock();
		boolean active;
		boolean powered;
		if (block instanceof RedStoneWireBlock) {
			active = powered = power > 0;
		} else if (block instanceof DiodeBlock) {
			// Repeater / comparator: powered = input on, active = output on.
			active = state.hasProperty(BlockStateProperties.POWERED) ? state.getValue(BlockStateProperties.POWERED) : power > 0;
			powered = ((DiodeBlockInvoker) block).redstonelab$backInput(level, pos, state) > 0;
		} else if (block instanceof RedstoneTorchBlock) {
			// Torch: powered = the block it sits on is powered, active = lit.
			active = state.getValue(BlockStateProperties.LIT);
			powered = ((RedstoneTorchBlockInvoker) block).redstonelab$isPowered(level, pos, state);
		} else if (block instanceof PistonBaseBlock) {
			active = state.getValue(PistonBaseBlock.EXTENDED);
			powered = ((PistonBaseBlockInvoker) block).redstonelab$isPowered(level, pos, state.getValue(PistonBaseBlock.FACING));
		} else if (state.hasProperty(BlockStateProperties.TRIGGERED)) {
			// Dispensers, droppers, crafters: on = triggered (powered; it fires when it turns on).
			active = state.getValue(BlockStateProperties.TRIGGERED);
			powered = level.hasNeighborSignal(pos) || (block instanceof DispenserBlock && level.hasNeighborSignal(pos.above()));
		} else if (block instanceof net.minecraft.world.level.block.HopperBlock) {
			// Hoppers: on = locked by redstone.
			active = !state.getValue(BlockStateProperties.ENABLED);
			powered = level.hasNeighborSignal(pos);
		} else if (level.getBlockEntity(pos) instanceof net.minecraft.world.level.block.entity.CommandBlockEntity command) {
			// Command blocks: on = powered (they have no blockstate for it).
			active = powered = command.isPowered();
		} else if (state.hasBlockEntity() && level.getBlockEntity(pos) instanceof Container) {
			// Chests, barrels...: on = a comparator reading it gives a signal.
			active = power > 0;
			powered = level.hasNeighborSignal(pos);
		} else if (state.isSignalSource()) {
			// Observer, lever, button, redstone block...: on = gives a signal.
			active = powered = power > 0;
		} else {
			// Lamps, doors, trapdoors, note blocks, rails, plain blocks.
			powered = state.hasProperty(BlockStateProperties.POWERED) ? state.getValue(BlockStateProperties.POWERED) : power > 0;
			if (state.hasProperty(BlockStateProperties.OPEN)) {
				active = state.getValue(BlockStateProperties.OPEN);
			} else if (state.hasProperty(BlockStateProperties.LIT)) {
				active = state.getValue(BlockStateProperties.LIT);
			} else {
				active = powered;
			}
		}
		return Reading.of(power, active, powered);
	}

	/** Same sides as PistonBaseBlock.getNeighborSignal, but as a level (0-15). */
	private static int pistonPower(Level level, BlockPos pos, Direction facing) {
		int max = 0;
		for (Direction d : DIRECTIONS) {
			if (d != facing) {
				max = Math.max(max, level.getSignal(pos.relative(d), d));
			}
		}
		max = Math.max(max, level.getSignal(pos, Direction.DOWN));
		BlockPos above = pos.above();
		for (Direction d : DIRECTIONS) {
			if (d != Direction.DOWN) {
				max = Math.max(max, level.getSignal(above.relative(d), d));
			}
		}
		return max;
	}
}
