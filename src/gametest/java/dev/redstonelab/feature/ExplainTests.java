package dev.redstonelab.feature;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.ComparatorMode;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

/**
 * "Look at it": the words for what a block does and why, checked on real builds against what the
 * game did. x+ = east, z+ = south.
 */
public class ExplainTests {
	private static final String DOT = " · ";

	private static void floor(GameTestHelper helper) {
		for (int x = 0; x <= 7; x++) {
			for (int z = 0; z <= 7; z++) {
				helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
			}
		}
	}

	private static List<String> lines(GameTestHelper helper, BlockPos rel) {
		return Explain.lines(helper.getLevel(), helper.absolutePos(rel), false);
	}

	private static void expect(GameTestHelper helper, BlockPos rel, String now) {
		List<String> lines = lines(helper, rel);
		String got = lines.isEmpty() ? "(nothing)" : lines.get(0);
		helper.assertTrue(got.equals(now), "at " + rel.toShortString() + ": expected '" + now + "', got '" + got + "'");
	}

	private static BlockState facing(BlockState state, Direction d) {
		return state.setValue(BlockStateProperties.HORIZONTAL_FACING, d);
	}

	/** Redstone block -> repeater -> 3 dust: power and where it comes from, along the line. */
	@GameTest(maxTicks = 20)
	public void explainDustLine(GameTestHelper helper) {
		floor(helper);
		helper.setBlock(new BlockPos(0, 1, 2), Blocks.REDSTONE_BLOCK);
		// Repeater FACING points at its input.
		helper.setBlock(new BlockPos(1, 1, 2), facing(Blocks.REPEATER.defaultBlockState(), Direction.WEST));
		for (int x = 2; x <= 4; x++) {
			helper.setBlock(new BlockPos(x, 1, 2), Blocks.REDSTONE_WIRE);
		}
		helper.runAtTickTime(10, () -> {
			expect(helper, new BlockPos(1, 1, 2), "Repeater" + DOT + "on" + DOT + "delay 2t" + DOT + "powered by Redstone Block (west, 15)");
			expect(helper, new BlockPos(2, 1, 2), "Dust" + DOT + "power 15" + DOT + "from Repeater (west, 15)");
			expect(helper, new BlockPos(4, 1, 2), "Dust" + DOT + "power 13" + DOT + "2 blocks from Repeater (15)");
			expect(helper, new BlockPos(0, 1, 2), "Redstone Block" + DOT + "gives 15");
			expect(helper, new BlockPos(2, 0, 2), "Stone" + DOT + "weakly powered by Dust (above, 15)" + DOT + "powers components, not dust");
			helper.assertTrue(lines(helper, new BlockPos(1, 0, 2)).isEmpty(), "a repeater doesn't power the block under it");
			helper.assertTrue(lines(helper, new BlockPos(2, 1, 2)).get(1).startsWith("Loses 1 power per block"), "dust rule");
			helper.succeed();
		});
	}

	/** A lever strongly powers its block, the torch on the other side goes off; a torch on plain stone stays on. */
	@GameTest(maxTicks = 20)
	public void explainTorchLever(GameTestHelper helper) {
		floor(helper);
		helper.setBlock(new BlockPos(3, 1, 3), Blocks.STONE);
		helper.setBlock(new BlockPos(2, 1, 3), Blocks.LEVER.defaultBlockState().setValue(BlockStateProperties.ATTACH_FACE, AttachFace.WALL)
				.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.WEST).setValue(BlockStateProperties.POWERED, true));
		helper.setBlock(new BlockPos(4, 1, 3), facing(Blocks.REDSTONE_WALL_TORCH.defaultBlockState(), Direction.EAST));
		helper.setBlock(new BlockPos(3, 1, 6), Blocks.REDSTONE_TORCH);
		helper.runAtTickTime(8, () -> {
			expect(helper, new BlockPos(4, 1, 3), "Torch" + DOT + "off" + DOT + "Stone behind it is powered by Lever (15)");
			expect(helper, new BlockPos(3, 1, 3), "Stone" + DOT + "strongly powered by Lever (west, 15)" + DOT + "powers dust too");
			expect(helper, new BlockPos(2, 1, 3), "Lever" + DOT + "on" + DOT + "gives 15");
			expect(helper, new BlockPos(3, 1, 6), "Torch" + DOT + "on" + DOT + "Stone under it isn't powered");
			helper.succeed();
		});
	}

	/** Subtract mode with a dust side input, and a repeater locked by another one. */
	@GameTest(maxTicks = 20)
	public void explainComparatorLock(GameTestHelper helper) {
		floor(helper);
		helper.setBlock(new BlockPos(2, 1, 3), Blocks.REDSTONE_BLOCK);
		helper.setBlock(new BlockPos(3, 1, 3), facing(Blocks.COMPARATOR.defaultBlockState(), Direction.WEST)
				.setValue(BlockStateProperties.MODE_COMPARATOR, ComparatorMode.SUBTRACT));
		helper.setBlock(new BlockPos(3, 1, 0), Blocks.REDSTONE_BLOCK);
		helper.setBlock(new BlockPos(3, 1, 1), Blocks.REDSTONE_WIRE);
		helper.setBlock(new BlockPos(3, 1, 2), Blocks.REDSTONE_WIRE);
		// R1 reads from the west (nothing there); R2 points into its side.
		helper.setBlock(new BlockPos(6, 1, 5), facing(Blocks.REPEATER.defaultBlockState(), Direction.WEST));
		helper.setBlock(new BlockPos(6, 1, 4), facing(Blocks.REPEATER.defaultBlockState(), Direction.NORTH));
		helper.setBlock(new BlockPos(6, 1, 3), Blocks.REDSTONE_BLOCK);
		helper.runAtTickTime(10, () -> {
			expect(helper, new BlockPos(3, 1, 3), "Comparator" + DOT + "subtract" + DOT + "back 15, side 14 → out 1");
			expect(helper, new BlockPos(6, 1, 5), "Repeater" + DOT + "locked off" + DOT + "by Repeater (north)");
			expect(helper, new BlockPos(6, 1, 4), "Repeater" + DOT + "on" + DOT + "delay 2t" + DOT + "powered by Redstone Block (north, 15)");
			helper.assertTrue(lines(helper, new BlockPos(3, 1, 3)).get(1).startsWith("Subtract: back minus side."), "comparator rule");
			helper.succeed();
		});
	}

	/** QC: powered only next to the block above, so it waits for an update (BUD), then it's out; a stuck piston; an observer. */
	@GameTest(maxTicks = 30)
	public void explainPistons(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		floor(helper);
		BlockPos piston = new BlockPos(2, 1, 2);
		helper.setBlock(piston, Blocks.PISTON.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.EAST));
		// Diagonal to the piston: no update reaches it.
		helper.setBlock(new BlockPos(2, 2, 1), Blocks.REDSTONE_BLOCK);
		helper.setBlock(new BlockPos(3, 1, 5), Blocks.OBSIDIAN);
		helper.setBlock(new BlockPos(1, 1, 5), Blocks.REDSTONE_BLOCK);
		helper.setBlock(new BlockPos(2, 1, 5), Blocks.PISTON.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.EAST));
		helper.setBlock(new BlockPos(6, 1, 1), Blocks.STONE);
		helper.setBlock(new BlockPos(6, 1, 2), Blocks.OBSERVER.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.NORTH));
		helper.runAtTickTime(5, () -> {
			expect(helper, piston, "Piston" + DOT + "powered, not out yet: moves on the next block update (BUD)");
			expect(helper, new BlockPos(2, 1, 5), "Piston" + DOT + "powered, can't push: Obsidian can't move");
			expect(helper, new BlockPos(6, 1, 2), "Observer" + DOT + "watching Stone (north)");
			// A block next to it: the update makes it extend.
			helper.setBlock(new BlockPos(2, 1, 3), Blocks.STONE);
		});
		helper.runAtTickTime(15, () -> {
			helper.assertTrue(level.getBlockState(helper.absolutePos(piston)).getValue(BlockStateProperties.EXTENDED), "the update should extend it");
			expect(helper, piston, "Piston" + DOT + "out" + DOT + "powered from above (QC) by Redstone Block (15) next to the block above it");
			helper.succeed();
		});
	}

	/** A lamp and a hopper next to redstone blocks, a free hopper, a dropper powered from above (QC). */
	@GameTest(maxTicks = 20)
	public void explainLampHopperDropper(GameTestHelper helper) {
		floor(helper);
		helper.setBlock(new BlockPos(1, 1, 1), Blocks.REDSTONE_LAMP);
		helper.setBlock(new BlockPos(0, 1, 1), Blocks.REDSTONE_BLOCK);
		// Placed after its power, with no update: powered but still off.
		helper.setBlock(new BlockPos(0, 1, 4), Blocks.REDSTONE_BLOCK);
		helper.setBlock(new BlockPos(0, 2, 4), Blocks.REDSTONE_LAMP);
		helper.setBlock(new BlockPos(3, 1, 1), Blocks.HOPPER);
		helper.setBlock(new BlockPos(4, 1, 1), Blocks.REDSTONE_BLOCK);
		helper.setBlock(new BlockPos(3, 1, 4), Blocks.HOPPER);
		helper.setBlock(new BlockPos(6, 1, 6), Blocks.DROPPER.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.UP));
		helper.setBlock(new BlockPos(6, 2, 5), Blocks.REDSTONE_BLOCK);
		helper.runAtTickTime(10, () -> {
			expect(helper, new BlockPos(1, 1, 1), "Lamp" + DOT + "on" + DOT + "powered by Redstone Block (west, 15)");
			expect(helper, new BlockPos(0, 2, 4), "Lamp" + DOT + "powered but off: lights on the next block update (BUD)");
			expect(helper, new BlockPos(3, 1, 1), "Hopper" + DOT + "locked" + DOT + "powered by Redstone Block (east, 15)");
			expect(helper, new BlockPos(3, 1, 4), "Hopper" + DOT + "not locked" + DOT + "points into Stone (below)");
			expect(helper, new BlockPos(6, 1, 6), "Dropper" + DOT + "powered from above (QC) by Redstone Block (north, 15)");
			helper.succeed();
		});
	}

	/** A torch clock that burns out: while it's out (off, no power, 3+ ticks), it says so. */
	@GameTest(maxTicks = 120)
	public void explainBurntTorch(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		floor(helper);
		BlockPos block = new BlockPos(2, 1, 3);
		BlockPos torch = new BlockPos(3, 1, 3);
		helper.setBlock(block, Blocks.STONE);
		for (BlockPos d : new BlockPos[]{new BlockPos(4, 1, 3), new BlockPos(4, 1, 4), new BlockPos(4, 1, 5), new BlockPos(3, 1, 5),
				new BlockPos(2, 1, 5), new BlockPos(2, 1, 4)}) {
			helper.setBlock(d, Blocks.REDSTONE_WIRE);
		}
		helper.runAtTickTime(5, () -> helper.setBlock(torch, facing(Blocks.REDSTONE_WALL_TORCH.defaultBlockState(), Direction.EAST)));
		List<String> wrong = new ArrayList<>();
		int[] offFor = {0};
		int[] burntSeen = {0};
		for (int t = 6; t < 110; t++) {
			helper.runAtTickTime(t, () -> {
				BlockState s = level.getBlockState(helper.absolutePos(torch));
				boolean powered = level.hasSignal(helper.absolutePos(block), Direction.WEST);
				boolean offUnpowered = !s.getValue(BlockStateProperties.LIT) && !powered;
				helper.assertTrue(offUnpowered == Explain.torchOffUnpowered(level, helper.absolutePos(torch), s),
						"off and unpowered: game says " + offUnpowered);
				offFor[0] = offUnpowered ? offFor[0] + 1 : 0;
				// The look-at view's rule: off without power for more than 3t = burnt out.
				String now = Explain.lines(level, helper.absolutePos(torch), offFor[0] > 3).get(0);
				if (offFor[0] == 1 && !now.equals("Torch" + DOT + "turning on (2t)") && !now.contains("burnt out")) {
					wrong.add(level.getGameTime() + " (just off): " + now);
				}
				if (offFor[0] > 3) {
					burntSeen[0]++;
					if (!now.equals("Torch" + DOT + "burnt out (switched 8 times in 60t)")) {
						wrong.add(level.getGameTime() + ": " + now);
					}
				}
			});
		}
		helper.runAtTickTime(110, () -> {
			helper.assertTrue(burntSeen[0] > 0, "the torch never burnt out");
			helper.assertTrue(wrong.isEmpty(), "burnt out but said: " + wrong);
			helper.succeed();
		});
	}
}
