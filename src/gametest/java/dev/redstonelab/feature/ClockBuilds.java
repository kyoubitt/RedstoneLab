package dev.redstonelab.feature;

import java.util.Random;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * Random piston builds on an observer clock: a piston (sticky or not, facing a random side or
 * up), a 3 x 3 x 3 field of random blocks in front of it, and two observers facing each other
 * that power it (or power the block above it: quasi-connectivity).
 */
final class ClockBuilds {
	private static final BlockState[] PALETTE = {
			Blocks.STONE.defaultBlockState(), Blocks.SLIME_BLOCK.defaultBlockState(), Blocks.HONEY_BLOCK.defaultBlockState(),
			Blocks.OBSIDIAN.defaultBlockState(), Blocks.WHITE_GLAZED_TERRACOTTA.defaultBlockState(), Blocks.COBWEB.defaultBlockState(),
			Blocks.GLASS.defaultBlockState(), Blocks.REDSTONE_BLOCK.defaultBlockState(), Blocks.OBSERVER.defaultBlockState(),
			Blocks.STICKY_PISTON.defaultBlockState(), Blocks.PISTON.defaultBlockState()};
	private static final int[] WEIGHTS = {16, 16, 8, 3, 3, 2, 3, 2, 2, 2, 2};
	private static final Direction[] FACINGS = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP};

	private ClockBuilds() {
	}

	/** Builds around {@code piston} (needs 3 blocks of room on every side and above). */
	static void random(Level level, BlockPos piston, Random random) {
		Direction facing = FACINGS[random.nextInt(FACINGS.length)];
		boolean qc = facing != Direction.UP && random.nextBoolean();
		for (int k = 1; k <= 3; k++) {
			for (int a = -1; a <= 1; a++) {
				for (int b = -1; b <= 1; b++) {
					BlockPos f = piston.relative(facing, k);
					f = facing.getAxis() == Direction.Axis.Y ? f.offset(a, 0, b)
							: facing.getAxis() == Direction.Axis.X ? f.offset(0, a, b) : f.offset(a, b, 0);
					if (random.nextInt(100) >= 60) {
						continue;
					}
					int pick = random.nextInt(59);
					BlockState s = PALETTE[0];
					for (int i = 0; i < PALETTE.length; i++) {
						if ((pick -= WEIGHTS[i]) < 0) {
							s = PALETTE[i];
							break;
						}
					}
					if (s.hasProperty(BlockStateProperties.FACING)) {
						s = s.setValue(BlockStateProperties.FACING, Direction.values()[random.nextInt(6)]);
					}
					level.setBlock(f, s, 2 | 16);
				}
			}
		}
		Direction side;
		do {
			side = FACINGS[random.nextInt(4)];
		} while (side == facing);
		BlockPos target = qc ? piston.above() : piston;
		level.setBlock(piston, (random.nextBoolean() ? Blocks.STICKY_PISTON : Blocks.PISTON).defaultBlockState()
				.setValue(BlockStateProperties.FACING, facing), 2 | 16);
		level.setBlock(target.relative(side), Blocks.OBSERVER.defaultBlockState().setValue(BlockStateProperties.FACING, side), 2 | 16);
		// Placing the second observer with updates starts the clock.
		level.setBlock(target.relative(side, 2), Blocks.OBSERVER.defaultBlockState().setValue(BlockStateProperties.FACING, side.getOpposite()), 3);
	}
}
