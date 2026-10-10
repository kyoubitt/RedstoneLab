package dev.redstonelab.feature;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * An observer clock powering a stone block. Around the stone: piston 0 on top and piston 3 at
 * the side (both powered in the same tick), a 1-tick repeater to piston 1 (+2t) and a 2-tick
 * repeater to piston 2 (+4t). All pistons face up.
 */
final class FiringOrderBuild {
	private FiringOrderBuild() {
	}

	/** {@code origin} = the first observer; needs 6 x 3 x 5 blocks of room (+x, +y, +z, and one block below). */
	static BlockPos[] build(Level level, BlockPos origin) {
		BlockPos o1 = origin;
		BlockPos o2 = origin.east();
		BlockPos stone = origin.east(2);
		BlockPos[] pistons = {stone.above(), stone.east(3), stone.south(3), stone.north()};
		level.setBlock(stone, Blocks.STONE.defaultBlockState(), 2 | 16);
		for (BlockPos p : pistons) {
			level.setBlock(p, Blocks.PISTON.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.UP), 2 | 16);
		}
		for (BlockPos p : new BlockPos[]{stone.east(), stone.east(2), stone.south(), stone.south(2)}) {
			level.setBlock(p.below(), Blocks.STONE.defaultBlockState(), 2 | 16);
		}
		// Repeaters point away from the stone (facing = the side the input comes from).
		level.setBlock(stone.east(), Blocks.REPEATER.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.WEST), 2 | 16);
		level.setBlock(stone.east(2), Blocks.REDSTONE_WIRE.defaultBlockState(), 2 | 16);
		level.setBlock(stone.south(), Blocks.REPEATER.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH)
				.setValue(BlockStateProperties.DELAY, 2), 2 | 16);
		level.setBlock(stone.south(2), Blocks.REDSTONE_WIRE.defaultBlockState(), 2 | 16);
		level.setBlock(o2, Blocks.OBSERVER.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.WEST), 2 | 16);
		// Placing the second observer with updates starts the clock.
		level.setBlock(o1, Blocks.OBSERVER.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.EAST), 3);
		return pistons;
	}
}
