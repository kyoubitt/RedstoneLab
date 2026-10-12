package dev.redstonelab.feature;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.ComparatorMode;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

/**
 * Real circuits people build, with meters on their parts. Every tick each meter's on / off is
 * checked against the block; at the end the pulse lengths and the order line must be what the
 * game really did (see {@link CircuitTruth}), plus the text only some blocks have.
 */
public class ComplexCircuitTests {
	private static final String DOT = " · ";

	private static void floor(GameTestHelper helper) {
		for (int x = 0; x <= 7; x++) {
			for (int z = 0; z <= 7; z++) {
				helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
			}
		}
	}

	private static BlockState facing(net.minecraft.world.level.block.Block block, Direction d) {
		return block.defaultBlockState().setValue(block.defaultBlockState().hasProperty(BlockStateProperties.FACING)
				? BlockStateProperties.FACING : BlockStateProperties.HORIZONTAL_FACING, d);
	}

	/**
	 * What the test does each tick, in a fixed order: first the actions (redstone blocks placed
	 * or removed), then the probes (truth samples, preview checks). The framework runs separate
	 * callbacks of one tick in no fixed order, so they all go through one callback per tick.
	 */
	private static final class Script {
		private final java.util.Map<Integer, java.util.List<Runnable>> actions = new java.util.TreeMap<>();
		private final java.util.List<Runnable> probes = new java.util.ArrayList<>();

		void at(int tick, Runnable action) {
			this.actions.computeIfAbsent(tick, k -> new java.util.ArrayList<>()).add(action);
		}

		void every(Runnable probe) {
			this.probes.add(probe);
		}

		/** Runs ticks {@code from} to {@code to} (exclusive); then {@code end} at {@code to}. */
		void run(GameTestHelper helper, int from, int to, Runnable end) {
			for (int t = 1; t < to; t++) {
				int tick = t;
				helper.runAtTickTime(t, () -> {
					this.actions.getOrDefault(tick, java.util.List.of()).forEach(Runnable::run);
					if (tick >= from) {
						this.probes.forEach(Runnable::run);
					}
				});
			}
			helper.runAtTickTime(to, end);
		}
	}

	private static void meter(GameTestHelper helper, ServerPlayer player, BlockPos... blocks) {
		for (BlockPos b : blocks) {
			MeterFeature.toggle(player, helper.getLevel(), helper.absolutePos(b));
		}
	}

	private static void check(GameTestHelper helper, CircuitTruth truth, String what) {
		String diff = truth.compare();
		helper.assertTrue(diff.isEmpty(), what + ": " + diff);
		helper.assertTrue(truth.checks > 100, what + ": too few checks " + truth.checks);
	}

	/**
	 * Two droppers facing each other with one item, a comparator reading the first and a lamp.
	 * Each input pulse puts a redstone block on top of each dropper. The first block also powers
	 * the second dropper through quasi-connectivity and goes first, so the first dropper is left
	 * triggered without power (a BUD) and never fires again; only the second keeps firing. The
	 * meters must show exactly that: A stuck "ON · not powered", B firing every 20t.
	 */
	@GameTest(maxTicks = 220)
	public void dropperPairBud(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		floor(helper);
		BlockPos d1 = new BlockPos(2, 1, 3);
		BlockPos d2 = new BlockPos(3, 1, 3);
		BlockPos comparator = new BlockPos(2, 1, 4);
		BlockPos lamp = new BlockPos(2, 1, 5);
		helper.setBlock(d1, facing(Blocks.DROPPER, Direction.EAST));
		helper.setBlock(d2, facing(Blocks.DROPPER, Direction.WEST));
		((Container) level.getBlockEntity(helper.absolutePos(d1))).setItem(0, new ItemStack(Items.STONE));
		helper.setBlock(comparator, facing(Blocks.COMPARATOR, Direction.NORTH));
		helper.setBlock(lamp, Blocks.REDSTONE_LAMP);
		meter(helper, player, d1, d2, comparator, lamp);
		Script script = new Script();
		for (int t = 10; t < 190; t += 20) {
			script.at(t, () -> {
				helper.setBlock(d1.above(), Blocks.REDSTONE_BLOCK);
				helper.setBlock(d2.above(), Blocks.REDSTONE_BLOCK);
			});
			script.at(t + 4, () -> {
				helper.setBlock(d1.above(), Blocks.AIR);
				helper.setBlock(d2.above(), Blocks.AIR);
			});
		}
		CircuitTruth truth = new CircuitTruth(level, player);
		script.every(truth::sample);
		script.run(helper, 2, 200, () -> {
			helper.assertTrue(truth.compare().isEmpty(), "dropper pair: " + truth.compare() + " | rises A " + MeterFeature.risesForTest(player, "A")
					+ " B " + MeterFeature.risesForTest(player, "B") + " C " + MeterFeature.risesForTest(player, "C") + " D "
					+ MeterFeature.risesForTest(player, "D") + " truth A " + truth.rises("A") + " B " + truth.rises("B") + " C " + truth.rises("C")
					+ " D " + truth.rises("D") + " now " + level.getGameTime());
			check(helper, truth, "dropper pair");
			String a0 = MeterFeature.statusForTest(player, "A");
			helper.assertTrue(dev.redstonelab.core.Reading.active(MeterFeature.readingForTest(player, "A"))
					&& !dev.redstonelab.core.Reading.powered(MeterFeature.readingForTest(player, "A")), "A is a BUD: triggered, not powered. " + a0);
			String b = MeterFeature.statusForTest(player, "B");
			helper.assertTrue(b.contains("fires every 20t"), "B: " + b);
			String c = MeterFeature.statusForTest(player, "C");
			String a = MeterFeature.statusForTest(player, "A");
			boolean full = a.contains("1 Stone");
			helper.assertTrue(c.contains("compare" + DOT + "back " + (full ? 1 : 0) + DOT + "side 0" + DOT + "out " + (full ? 1 : 0)),
					"comparator inputs: " + c + " | " + a);
			MeterFeature.clear(player);
			helper.succeed();
		});
	}

	/**
	 * Comparator clock: a subtract comparator fed by a redstone block, its output looped back
	 * through dust and a repeater into its side. A lamp under the dust shows the clock.
	 */
	@GameTest(maxTicks = 160)
	public void comparatorClock(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		floor(helper);
		BlockPos comparator = new BlockPos(2, 1, 3);
		BlockPos repeater = new BlockPos(2, 1, 4);
		BlockPos lamp = new BlockPos(3, 0, 3);
		helper.setBlock(lamp, Blocks.REDSTONE_LAMP);
		helper.setBlock(new BlockPos(3, 1, 3), Blocks.REDSTONE_WIRE);
		helper.setBlock(new BlockPos(3, 1, 4), Blocks.REDSTONE_WIRE);
		helper.setBlock(new BlockPos(3, 1, 5), Blocks.REDSTONE_WIRE);
		helper.setBlock(new BlockPos(2, 1, 5), Blocks.REDSTONE_WIRE);
		helper.setBlock(repeater, facing(Blocks.REPEATER, Direction.SOUTH));
		helper.setBlock(comparator, facing(Blocks.COMPARATOR, Direction.WEST).setValue(BlockStateProperties.MODE_COMPARATOR, ComparatorMode.SUBTRACT));
		meter(helper, player, comparator, lamp, repeater, new BlockPos(3, 1, 3));
		Script script = new Script();
		script.at(5, () -> helper.setBlock(new BlockPos(1, 1, 3), Blocks.REDSTONE_BLOCK));
		CircuitTruth truth = new CircuitTruth(level, player);
		script.every(truth::sample);
		script.run(helper, 2, 150, () -> {
			check(helper, truth, "comparator clock");
			String a = MeterFeature.pulsesForTest(player, "A");
			helper.assertTrue(!a.isEmpty(), "the clock should run: " + MeterFeature.statusForTest(player, "A"));
			helper.assertTrue(MeterFeature.statusForTest(player, "A").contains("subtract" + DOT + "back 15"), MeterFeature.statusForTest(player, "A"));
			MeterFeature.clear(player);
			helper.succeed();
		});
	}

	/**
	 * Double piston extender: a sticky piston pushes a second sticky piston, which then pushes a
	 * slime block with stone on it; both retract again, 4 rounds. The meters ride along with the
	 * pistons, and every piston preview must match what the pistons really moved.
	 */
	@GameTest(maxTicks = 200)
	public void doublePistonExtender(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		floor(helper);
		BlockPos p1 = new BlockPos(1, 1, 3);
		BlockPos p2 = new BlockPos(2, 1, 3);
		helper.setBlock(p1, facing(Blocks.STICKY_PISTON, Direction.EAST));
		helper.setBlock(p2, facing(Blocks.STICKY_PISTON, Direction.EAST));
		helper.setBlock(new BlockPos(3, 1, 3), Blocks.SLIME_BLOCK);
		helper.setBlock(new BlockPos(3, 2, 3), Blocks.STONE);
		// Nothing under the slime's path (it would stick to the floor).
		for (int x = 3; x <= 6; x++) {
			helper.setBlock(new BlockPos(x, 0, 3), Blocks.AIR);
		}
		meter(helper, player, p1, p2);
		Script script = new Script();
		for (int t = 10; t < 170; t += 40) {
			script.at(t, () -> helper.setBlock(new BlockPos(1, 1, 2), Blocks.REDSTONE_BLOCK));
			script.at(t + 6, () -> helper.setBlock(new BlockPos(3, 1, 2), Blocks.REDSTONE_BLOCK));
			script.at(t + 16, () -> helper.setBlock(new BlockPos(3, 1, 2), Blocks.AIR));
			script.at(t + 22, () -> helper.setBlock(new BlockPos(1, 1, 2), Blocks.AIR));
		}
		PistonTruth previews = new PistonTruth(helper.absolutePos(new BlockPos(0, 1, 0)), helper.absolutePos(new BlockPos(7, 4, 7)));
		CircuitTruth truth = new CircuitTruth(level, player);
		script.every(() -> previews.step(level, "tick " + level.getGameTime()));
		script.every(truth::sample);
		script.run(helper, 2, 190, () -> {
			check(helper, truth, "double extender");
			helper.assertTrue(previews.wrong == 0 && previews.shownWrong == 0, previews.summary() + " " + previews.problems + " " + previews.shownProblems);
			helper.assertTrue(previews.checked >= 8, "previews checked: " + previews.summary());
			for (String p : new String[]{"A", "B"}) {
				String s = MeterFeature.statusForTest(player, p);
				helper.assertTrue(s.contains("moves every 40t"), p + ": " + s);
			}
			MeterFeature.clear(player);
			helper.succeed();
		});
	}

	/**
	 * One-torch clock: a wall torch whose own output runs back through dust into its block. It
	 * flips every 2t until it burns out (8 flips in 60t), then the meter says "burnt out".
	 */
	@GameTest(maxTicks = 120)
	public void torchBurnoutClock(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		floor(helper);
		BlockPos block = new BlockPos(2, 1, 3);
		BlockPos torch = new BlockPos(3, 1, 3);
		helper.setBlock(block, Blocks.STONE);
		for (BlockPos d : new BlockPos[]{new BlockPos(4, 1, 3), new BlockPos(4, 1, 4), new BlockPos(4, 1, 5), new BlockPos(3, 1, 5),
				new BlockPos(2, 1, 5), new BlockPos(2, 1, 4)}) {
			helper.setBlock(d, Blocks.REDSTONE_WIRE);
		}
		meter(helper, player, torch, new BlockPos(2, 1, 4));
		Script script = new Script();
		script.at(5, () -> helper.setBlock(torch, Blocks.REDSTONE_WALL_TORCH.defaultBlockState()
				.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST)));
		// Burnt out = off and unpowered for 3+ ticks (between normal flips it's off for 2).
		java.util.List<Boolean> offUnpowered = new java.util.ArrayList<>();
		java.util.List<Boolean> meterBurnt = new java.util.ArrayList<>();
		script.every(() -> {
			BlockState s = level.getBlockState(helper.absolutePos(torch));
			boolean powered = level.hasSignal(helper.absolutePos(block), Direction.WEST);
			offUnpowered.add(s.is(Blocks.REDSTONE_WALL_TORCH) && !s.getValue(BlockStateProperties.LIT) && !powered);
			meterBurnt.add(MeterFeature.statusForTest(player, "A").contains("burnt out"));
		});
		CircuitTruth truth = new CircuitTruth(level, player);
		script.every(truth::sample);
		script.run(helper, 6, 110, () -> {
			check(helper, truth, "torch clock");
			int burntTicks = 0;
			for (int i = 0; i < offUnpowered.size(); i++) {
				int start = i;
				while (start > 0 && offUnpowered.get(start - 1)) {
					start--;
				}
				int end = i;
				while (end + 1 < offUnpowered.size() && offUnpowered.get(end + 1)) {
					end++;
				}
				boolean burnt = offUnpowered.get(i) && (end - start + 1 >= 3 || end == offUnpowered.size() - 1) && i - start >= 2;
				boolean couldBe = offUnpowered.get(i) && (end - start + 1 >= 3 || end == offUnpowered.size() - 1);
				burntTicks += burnt ? 1 : 0;
				helper.assertTrue(!burnt || meterBurnt.get(i), "sample " + i + ": burnt out, meter doesn't say so");
				helper.assertTrue(!meterBurnt.get(i) || couldBe, "sample " + i + ": meter says burnt out, the torch isn't");
			}
			helper.assertTrue(burntTicks > 10, "the torch should burn out: " + burntTicks);
			MeterFeature.clear(player);
			helper.succeed();
		});
	}

	/**
	 * Repeater latch: data into a repeater, a second repeater locks it from the side. Locked, the
	 * output keeps its value when the data changes; the meter says "locked" exactly then.
	 */
	@GameTest(maxTicks = 140)
	public void repeaterLatch(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		floor(helper);
		BlockPos latch = new BlockPos(3, 1, 3);
		BlockPos lock = new BlockPos(3, 1, 4);
		BlockPos lamp = new BlockPos(4, 1, 3);
		helper.setBlock(latch, facing(Blocks.REPEATER, Direction.WEST));
		helper.setBlock(lock, facing(Blocks.REPEATER, Direction.SOUTH));
		helper.setBlock(lamp, Blocks.REDSTONE_LAMP);
		meter(helper, player, latch, lamp, lock);
		BlockPos data = new BlockPos(2, 1, 3);
		BlockPos lockIn = new BlockPos(3, 1, 5);
		Script script = new Script();
		// data on, lock, data off (output stays on), unlock (output follows), data on / off unlocked, lock while on...
		int[][] steps = {{10, 1, -1}, {20, -1, 1}, {30, 0, -1}, {45, -1, 0}, {60, 1, -1}, {70, 0, -1}, {80, 1, -1}, {90, -1, 1},
				{100, 0, -1}, {115, -1, 0}};
		for (int[] step : steps) {
			script.at(step[0], () -> {
				if (step[1] >= 0) {
					helper.setBlock(data, step[1] == 1 ? Blocks.REDSTONE_BLOCK : Blocks.AIR);
				}
				if (step[2] >= 0) {
					helper.setBlock(lockIn, step[2] == 1 ? Blocks.REDSTONE_BLOCK : Blocks.AIR);
				}
			});
		}
		String[] bad = {""};
		script.every(() -> {
			boolean locked = level.getBlockState(helper.absolutePos(latch)).getValue(BlockStateProperties.LOCKED);
			String s = MeterFeature.statusForTest(player, "A");
			if (s.contains("locked") != locked && bad[0].isEmpty()) {
				bad[0] = "tick " + level.getGameTime() + " locked " + locked + ": " + s;
			}
		});
		CircuitTruth truth = new CircuitTruth(level, player);
		script.every(truth::sample);
		script.run(helper, 3, 130, () -> {
			check(helper, truth, "repeater latch");
			helper.assertTrue(bad[0].isEmpty(), "locked text: " + bad[0]);
			MeterFeature.clear(player);
			helper.succeed();
		});
	}

	/**
	 * Item line: a dropper pulsed every 20t drops into a hopper beside it, which passes items on
	 * to a second hopper and a chest. In / out every 20t, and the chest's signal follows its items.
	 */
	@GameTest(maxTicks = 260)
	public void hopperLine(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		floor(helper);
		BlockPos dropper = new BlockPos(1, 1, 3);
		BlockPos h1 = new BlockPos(2, 1, 3);
		BlockPos h2 = new BlockPos(3, 1, 3);
		BlockPos chest = new BlockPos(4, 1, 3);
		helper.setBlock(dropper, facing(Blocks.DROPPER, Direction.EAST));
		((Container) level.getBlockEntity(helper.absolutePos(dropper))).setItem(0, new ItemStack(Items.STONE, 64));
		helper.setBlock(h1, Blocks.HOPPER.defaultBlockState().setValue(BlockStateProperties.FACING_HOPPER, Direction.EAST));
		helper.setBlock(h2, Blocks.HOPPER.defaultBlockState().setValue(BlockStateProperties.FACING_HOPPER, Direction.EAST));
		helper.setBlock(chest, Blocks.CHEST);
		meter(helper, player, dropper, h1, h2, chest);
		Script script = new Script();
		for (int t = 10; t < 220; t += 20) {
			script.at(t, () -> helper.setBlock(dropper.above(), Blocks.REDSTONE_BLOCK));
			script.at(t + 2, () -> helper.setBlock(dropper.above(), Blocks.AIR));
		}
		script.run(helper, 2, 250, () -> {
			String a = MeterFeature.statusForTest(player, "A");
			String b = MeterFeature.statusForTest(player, "B");
			String c = MeterFeature.statusForTest(player, "C");
			String d = MeterFeature.statusForTest(player, "D");
			int items = ((Container) level.getBlockEntity(helper.absolutePos(chest))).getItem(0).getCount();
			helper.assertTrue(items == 11, "11 items should have arrived: " + items);
			helper.assertTrue(a.contains("53 Stone") && a.contains("fires every 20t"), "dropper: " + a);
			helper.assertTrue(b.equals("Hopper" + DOT + "empty" + DOT + "in / out every 20t"), "hopper 1: " + b);
			helper.assertTrue(c.equals("Hopper" + DOT + "empty" + DOT + "in / out every 20t"), "hopper 2: " + c);
			int signal = net.minecraft.world.inventory.AbstractContainerMenu.getRedstoneSignalFromContainer(
					(Container) level.getBlockEntity(helper.absolutePos(chest)));
			helper.assertTrue(d.startsWith("Chest" + DOT + "11 Stone" + DOT + "signal " + signal) && d.contains("in every 20t"), "chest: " + d);
			MeterFeature.clear(player);
			helper.succeed();
		});
	}
}
