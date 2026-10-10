package dev.redstonelab.feature;

import java.util.HashSet;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.PistonStructureResolver;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import dev.redstonelab.core.ContainerMath;

/**
 * Automated in-game tests: each one builds a small redstone setup in a test world, lets the game
 * run it, and checks what RedstoneLab reports against what the game really did.
 * Run with {@code ./gradlew runGameTest}.
 */
public class RedstoneLabGameTests {
	private static final String DOT = " · ";
	private static final String ARROW = " → ";

	private static BlockState piston(boolean sticky, Direction facing) {
		return (sticky ? Blocks.STICKY_PISTON : Blocks.PISTON).defaultBlockState().setValue(BlockStateProperties.FACING, facing);
	}

	private static BlockState observer(Direction facing) {
		return Blocks.OBSERVER.defaultBlockState().setValue(BlockStateProperties.FACING, facing);
	}

	/**
	 * Two observers facing each other make a clock. The test records the observers' real
	 * powered state every tick and checks the meters say the same on / off lengths and order.
	 */
	@GameTest(maxTicks = 120)
	public void observerClockMeters(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		BlockPos a = new BlockPos(2, 1, 2);
		BlockPos b = new BlockPos(3, 1, 2);
		helper.setBlock(a, observer(Direction.EAST));
		MeterFeature.toggle(player, level, helper.absolutePos(a));
		MeterFeature.toggle(player, level, helper.absolutePos(b));
		// Placing B in front of A starts the clock.
		helper.setBlock(b, observer(Direction.WEST));
		boolean[] poweredA = new boolean[100];
		boolean[] poweredB = new boolean[100];
		for (int t = 20; t < 100; t++) {
			int tick = t;
			helper.runAtTickTime(t, () -> {
				poweredA[tick] = level.getBlockState(helper.absolutePos(a)).getValue(BlockStateProperties.POWERED);
				poweredB[tick] = level.getBlockState(helper.absolutePos(b)).getValue(BlockStateProperties.POWERED);
			});
		}
		helper.runAtTickTime(100, () -> {
			String realA = "Observer" + DOT + pattern(poweredA);
			String realB = "Observer" + DOT + pattern(poweredB);
			String statusA = MeterFeature.statusForTest(player, "A");
			String statusB = MeterFeature.statusForTest(player, "B");
			helper.assertTrue(statusA.equals(realA), "A: meter says '" + statusA + "', game did '" + realA + "'");
			helper.assertTrue(statusB.equals(realB), "B: meter says '" + statusB + "', game did '" + realB + "'");
			long delay = firstRise(poweredB) - firstRise(poweredA);
			if (delay > 0) {
				String order = MeterFeature.orderForTest(player, level.getGameTime());
				String realOrder = "A" + ARROW + delay + "t" + ARROW + "B";
				helper.assertTrue(order.equals(realOrder), "order: meter says '" + order + "', game did '" + realOrder + "'");
			}
			MeterFeature.clear(player);
			helper.succeed();
		});
	}

	/** "on 2t · off 4t" from a recorded powered / unpowered history. */
	private static String pattern(boolean[] powered) {
		java.util.List<Integer> on = new java.util.ArrayList<>();
		java.util.List<Integer> off = new java.util.ArrayList<>();
		int start = -1;
		for (int t = 21; t < powered.length; t++) {
			if (powered[t] != powered[t - 1]) {
				if (start >= 0) {
					(powered[t - 1] ? on : off).add(t - start);
				}
				start = t;
			}
		}
		return "on " + range(on) + DOT + "off " + range(off);
	}

	/** Same exact-number format as the meter, from the values in time order (last 8, like the meter). */
	private static String range(java.util.List<Integer> values) {
		if (values.isEmpty()) {
			return "?";
		}
		java.util.List<Long> last = new java.util.ArrayList<>();
		for (int v : values.subList(Math.max(0, values.size() - 8), values.size())) {
			last.add((long) v);
		}
		return dev.redstonelab.core.Ticks.exact(last);
	}


	/** First tick (from 41 on) where the block turns on. */
	private static long firstRise(boolean[] powered) {
		for (int t = 41; t < powered.length; t++) {
			if (powered[t] && !powered[t - 1]) {
				return t;
			}
		}
		return -1;
	}

	/** The order line must not change from tick to tick while the clock runs. */
	@GameTest(maxTicks = 140)
	public void orderLineStaysPut(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		BlockPos a = new BlockPos(2, 1, 2);
		BlockPos b = new BlockPos(3, 1, 2);
		helper.setBlock(a, observer(Direction.EAST));
		MeterFeature.toggle(player, level, helper.absolutePos(a));
		MeterFeature.toggle(player, level, helper.absolutePos(b));
		helper.setBlock(b, observer(Direction.WEST));
		Set<String> seen = new HashSet<>();
		for (int t = 60; t < 100; t++) {
			helper.runAtTickTime(t, () -> seen.add(MeterFeature.orderForTest(player, level.getGameTime())));
		}
		helper.runAtTickTime(101, () -> {
			helper.assertTrue(seen.size() == 1, "order line changed: " + seen);
			MeterFeature.clear(player);
			helper.succeed();
		});
	}

	/** Pushing: our preview must agree with the game's own push calculation. */
	@GameTest
	public void pushPreviewMatchesVanilla(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockPos piston = new BlockPos(1, 1, 1);
		helper.setBlock(piston, piston(false, Direction.EAST));
		// A line of 3 blocks, with a slime block in the middle dragging a block above it.
		helper.setBlock(new BlockPos(2, 1, 1), Blocks.STONE);
		helper.setBlock(new BlockPos(3, 1, 1), Blocks.SLIME_BLOCK);
		helper.setBlock(new BlockPos(3, 2, 1), Blocks.STONE);
		helper.setBlock(new BlockPos(4, 1, 1), Blocks.STONE);
		check(helper, level, piston, "line + slime");
		// Too many blocks: 15 slime blocks stuck together.
		for (int x = 2; x <= 6; x++) {
			for (int y = 1; y <= 3; y++) {
				helper.setBlock(new BlockPos(x, y, 1), Blocks.SLIME_BLOCK);
			}
		}
		check(helper, level, piston, "too many");
		PushPreview tooMany = new PushPreview(level, helper.absolutePos(piston), Direction.EAST, true);
		helper.assertTrue(!tooMany.resolve() && tooMany.failure == PushPreview.Failure.TOO_MANY,
				"should be too many blocks, got " + tooMany.failure);
		// Obsidian in the way.
		for (int x = 2; x <= 6; x++) {
			for (int y = 1; y <= 3; y++) {
				helper.setBlock(new BlockPos(x, y, 1), Blocks.AIR);
			}
		}
		helper.setBlock(new BlockPos(2, 1, 1), Blocks.STONE);
		helper.setBlock(new BlockPos(3, 1, 1), Blocks.OBSIDIAN);
		check(helper, level, piston, "obsidian");
		PushPreview blocked = new PushPreview(level, helper.absolutePos(piston), Direction.EAST, true);
		helper.assertTrue(!blocked.resolve() && helper.absolutePos(new BlockPos(3, 1, 1)).equals(blocked.blocker),
				"should be blocked by the obsidian, got " + blocked.blocker);
		helper.succeed();
	}

	private static void check(GameTestHelper helper, ServerLevel level, BlockPos relPiston, String what) {
		BlockPos pos = helper.absolutePos(relPiston);
		PushPreview ours = new PushPreview(level, pos, Direction.EAST, true);
		boolean ok = ours.resolve();
		PistonStructureResolver vanilla = new PistonStructureResolver(level, pos, Direction.EAST, true);
		boolean vanillaOk = vanilla.resolve();
		helper.assertTrue(ok == vanillaOk, what + ": we say " + ok + ", game says " + vanillaOk);
		if (ok) {
			helper.assertTrue(ours.toPush.equals(vanilla.getToPush()), what + ": push order differs: " + ours.toPush + " vs " + vanilla.getToPush());
			helper.assertTrue(ours.toDestroy.equals(vanilla.getToDestroy()), what + ": broken blocks differ");
		}
	}

	/** A sticky piston pulling 4 slime blocks: the preview must say it works (the old bug said "blocked"), and the game must really pull them. */
	@GameTest(maxTicks = 40)
	public void stickyPistonPullsSlime(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockPos piston = new BlockPos(1, 1, 1);
		helper.setBlock(piston, piston(true, Direction.UP));
		for (int y = 2; y <= 5; y++) {
			helper.setBlock(new BlockPos(1, y, 1), Blocks.SLIME_BLOCK);
		}
		helper.setBlock(new BlockPos(0, 1, 1), Blocks.REDSTONE_BLOCK);
		helper.runAtTickTime(6, () -> {
			helper.assertBlockState(new BlockPos(1, 6, 1), s -> s.is(Blocks.SLIME_BLOCK), s -> Component.literal("slime should be pushed up"));
			PushPreview pull = new PushPreview(level, helper.absolutePos(piston), Direction.UP, false);
			helper.assertTrue(pull.resolve(), "preview says the pull fails: " + pull.failure + " at " + pull.blocker);
			helper.assertTrue(pull.toPush.size() == 4, "preview pulls " + pull.toPush.size() + " blocks, want 4");
			helper.setBlock(new BlockPos(0, 1, 1), Blocks.AIR);
		});
		helper.runAtTickTime(14, () -> {
			for (int y = 2; y <= 5; y++) {
				helper.assertBlockState(new BlockPos(1, y, 1), s -> s.is(Blocks.SLIME_BLOCK),
						s -> Component.literal("slime should be pulled back down"));
			}
			helper.succeed();
		});
	}

	/** Our comparator maths for containers must match the game for every item count. */
	@GameTest
	public void containerSignalMatchesVanilla(GameTestHelper helper) {
		BlockPos pos = new BlockPos(1, 1, 1);
		helper.setBlock(pos, Blocks.DROPPER);
		Container dropper = (Container) helper.getLevel().getBlockEntity(helper.absolutePos(pos));
		for (int items = 0; items <= 9 * 64; items++) {
			dropper.clearContent();
			int left = items;
			for (int slot = 0; slot < 9 && left > 0; slot++) {
				int n = Math.min(64, left);
				dropper.setItem(slot, new ItemStack(Items.STONE, n));
				left -= n;
			}
			int game = AbstractContainerMenu.getRedstoneSignalFromContainer(dropper);
			int ours = ContainerMath.signal(items, 9, 64);
			helper.assertTrue(game == ours, items + " items: game says " + game + ", we say " + ours);
		}
		helper.succeed();
	}

	/** Powered from two blocks above: no update means BUD; after an update it extends and shows QC. */
	@GameTest(maxTicks = 30)
	public void quasiConnectivityAndBud(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockPos piston = new BlockPos(1, 1, 1);
		helper.setBlock(piston, piston(false, Direction.EAST));
		helper.setBlock(new BlockPos(1, 3, 1), Blocks.REDSTONE_BLOCK);
		helper.runAtTickTime(3, () -> {
			String info = PistonView.powerInfo(level, helper.absolutePos(piston));
			helper.assertTrue("BUD".equals(info), "expected BUD, got " + info);
			int power = dev.redstonelab.util.PowerUtil.powerAt(level, helper.absolutePos(piston));
			helper.assertTrue(power == 15, "a QC-powered piston's meter should read 15, got " + power);
			int reading = dev.redstonelab.util.PowerUtil.read(level, helper.absolutePos(piston), level.getBlockState(helper.absolutePos(piston)));
			helper.assertTrue(dev.redstonelab.core.Reading.powered(reading) && !dev.redstonelab.core.Reading.active(reading),
					"QC piston: powered but not extended");
			// Any block update next to it makes it fire.
			helper.setBlock(new BlockPos(1, 1, 0), Blocks.STONE);
		});
		helper.runAtTickTime(8, () -> {
			helper.assertBlockState(piston, s -> s.getValue(BlockStateProperties.EXTENDED),
					s -> Component.literal("piston should have extended"));
			String info = PistonView.powerInfo(level, helper.absolutePos(piston));
			helper.assertTrue("QC".equals(info), "expected QC, got " + info);
			helper.succeed();
		});
	}

	// ------------------------------------------------------------------ niche clocks and edge cases

	private static void floor(GameTestHelper helper, int maxX, int maxZ) {
		for (int x = 0; x <= maxX; x++) {
			for (int z = 0; z <= maxZ; z++) {
				helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
			}
		}
	}

	/** Records a truth value every tick from {@code from} to {@code to}. */
	private static boolean[] record(GameTestHelper helper, int from, int to, java.util.function.BooleanSupplier truth) {
		boolean[] values = new boolean[to];
		for (int t = from; t < to; t++) {
			int tick = t;
			helper.runAtTickTime(t, () -> values[tick] = truth.getAsBoolean());
		}
		return values;
	}

	/** On / off lengths from a history, starting after the first change at or after {@code from}. */
	private static String pattern(boolean[] values, int from) {
		java.util.List<Integer> on = new java.util.ArrayList<>();
		java.util.List<Integer> off = new java.util.ArrayList<>();
		int start = -1;
		for (int t = from + 1; t < values.length; t++) {
			if (values[t] != values[t - 1]) {
				if (start >= 0) {
					(values[t - 1] ? on : off).add(t - start);
				}
				start = t;
			}
		}
		return "on " + range(on) + DOT + "off " + range(off);
	}

	/**
	 * A loop of two repeaters (delay 4) and dust with one pulse running round it: a slow clock
	 * with a short pulse. The meters on a repeater and on a dust must match the real states.
	 */
	@GameTest(maxTicks = 200)
	public void repeaterLoopClock(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		floor(helper, 4, 3);
		BlockPos r1 = new BlockPos(2, 1, 1);
		BlockPos r2 = new BlockPos(2, 1, 2);
		BlockPos dust = new BlockPos(3, 1, 1);
		// A repeater's FACING points at its input.
		helper.setBlock(r1, Blocks.REPEATER.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.WEST)
				.setValue(BlockStateProperties.DELAY, 4));
		helper.setBlock(r2, Blocks.REPEATER.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST)
				.setValue(BlockStateProperties.DELAY, 4));
		for (BlockPos p : new BlockPos[]{new BlockPos(3, 1, 1), new BlockPos(3, 1, 2), new BlockPos(1, 1, 2), new BlockPos(1, 1, 1)}) {
			helper.setBlock(p, Blocks.REDSTONE_WIRE);
		}
		MeterFeature.toggle(player, level, helper.absolutePos(r1));
		MeterFeature.toggle(player, level, helper.absolutePos(dust));
		// Start one pulse: power the loop for 3 ticks.
		helper.setBlock(new BlockPos(1, 1, 0), Blocks.REDSTONE_BLOCK);
		helper.runAtTickTime(3, () -> helper.setBlock(new BlockPos(1, 1, 0), Blocks.AIR));
		boolean[] repeater = record(helper, 60, 190, () -> level.getBlockState(helper.absolutePos(r1)).getValue(BlockStateProperties.POWERED));
		boolean[] wire = record(helper, 60, 190, () -> level.getBlockState(helper.absolutePos(dust)).getValue(BlockStateProperties.POWER) > 0);
		helper.runAtTickTime(190, () -> {
			String realR = "Repeater" + DOT + pattern(repeater, 60);
			int dustPower = level.getBlockState(helper.absolutePos(dust)).getValue(BlockStateProperties.POWER);
			String realD = "Redstone Wire" + (dustPower > 0 ? DOT + "power " + dustPower : "") + DOT + pattern(wire, 60);
			helper.assertTrue(!realR.contains("?"), "the loop isn't running: " + realR);
			String a = MeterFeature.statusForTest(player, "A");
			String b = MeterFeature.statusForTest(player, "B");
			helper.assertTrue(a.equals(realR), "repeater: meter says '" + a + "', game did '" + realR + "'");
			helper.assertTrue(b.equals(realD), "dust: meter says '" + b + "', game did '" + realD + "'");
			MeterFeature.clear(player);
			helper.succeed();
		});
	}

	/** A piston on an observer clock: "moves every / out" must match when the piston really is out. */
	@GameTest(maxTicks = 140)
	public void pistonOnObserverClock(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		BlockPos a = new BlockPos(2, 1, 2);
		BlockPos b = new BlockPos(3, 1, 2);
		BlockPos piston = new BlockPos(1, 1, 2);
		helper.setBlock(piston, piston(false, Direction.NORTH));
		helper.setBlock(a, observer(Direction.EAST));
		MeterFeature.toggle(player, level, helper.absolutePos(piston));
		helper.setBlock(b, observer(Direction.WEST));
		// Show its preview once: it must never drop while the piston keeps moving.
		PistorderFeature.toggle(player, level, helper.absolutePos(piston));
		int[] lost = new int[1];
		for (int t = 1; t < 130; t++) {
			helper.runAtTickTime(t, () -> lost[0] += PistorderFeature.shownForTest(player, helper.absolutePos(piston)) ? 0 : 1);
		}
		boolean[] out = record(helper, 40, 130, () -> {
			BlockState s = level.getBlockState(helper.absolutePos(piston));
			return s.is(Blocks.PISTON) && s.getValue(BlockStateProperties.EXTENDED);
		});
		helper.runAtTickTime(130, () -> {
			java.util.List<Integer> every = new java.util.ArrayList<>();
			java.util.List<Integer> outFor = new java.util.ArrayList<>();
			int lastRise = -1;
			for (int t = 41; t < out.length; t++) {
				if (out[t] && !out[t - 1]) {
					if (lastRise >= 0) {
						every.add(t - lastRise);
					}
					lastRise = t;
				}
				if (!out[t] && out[t - 1] && lastRise >= 0) {
					outFor.add(t - lastRise);
				}
			}
			String real = "Piston" + DOT + "moves every " + range(every) + DOT + "out " + range(outFor);
			String meter = MeterFeature.statusForTest(player, "A");
			helper.assertTrue(meter.equals(real), "piston: meter says '" + meter + "', game did '" + real + "'");
			helper.assertTrue(lost[0] == 0, "the preview of a piston on a clock dropped " + lost[0] + " times");
			helper.assertTrue(!PistorderFeature.labelForTest(player, helper.absolutePos(piston)).isEmpty(),
					"the preview of a piston on a clock must still get drawn (it is only still for a tick at a time)");
			PistorderFeature.clear(player);
			MeterFeature.clear(player);
			helper.succeed();
		});
	}

	/** Power on and off inside one tick (a 0-tick pulse) must still be seen. */
	@GameTest(maxTicks = 20)
	public void zeroTickPulse(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		floor(helper, 3, 2);
		BlockPos dust = new BlockPos(2, 1, 1);
		helper.setBlock(dust, Blocks.REDSTONE_WIRE);
		MeterFeature.toggle(player, level, helper.absolutePos(dust));
		helper.runAtTickTime(5, () -> {
			helper.setBlock(new BlockPos(1, 1, 1), Blocks.REDSTONE_BLOCK);
			helper.setBlock(new BlockPos(1, 1, 1), Blocks.AIR);
		});
		helper.runAtTickTime(8, () -> {
			String meter = MeterFeature.statusForTest(player, "A");
			helper.assertTrue(meter.equals("Redstone Wire" + DOT + "on 0t"), "meter says '" + meter + "'");
			MeterFeature.clear(player);
			helper.succeed();
		});
	}

	/**
	 * 300 random piston setups (stone, slime, honey, obsidian, glazed terracotta, glass, cobweb),
	 * pushing and sticky-pulling: our preview must give the same result, order and broken blocks
	 * as the game's own calculation every time.
	 */
	@GameTest(maxTicks = 20)
	public void randomPistonSetups(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		java.util.Random random = new java.util.Random(12345);
		BlockState[] palette = {
				Blocks.STONE.defaultBlockState(), Blocks.SLIME_BLOCK.defaultBlockState(), Blocks.HONEY_BLOCK.defaultBlockState(),
				Blocks.OBSIDIAN.defaultBlockState(), Blocks.WHITE_GLAZED_TERRACOTTA.defaultBlockState(), Blocks.GLASS.defaultBlockState(),
				Blocks.COBWEB.defaultBlockState()};
		int[] weights = {20, 14, 10, 4, 3, 4, 3};
		BlockPos piston = helper.absolutePos(new BlockPos(1, 3, 2));
		BlockPos head = piston.east();
		int mismatches = 0;
		String first = "";
		for (int round = 0; round < 300; round++) {
			for (int x = 2; x <= 7; x++) {
				for (int y = 1; y <= 5; y++) {
					for (int z = 0; z <= 4; z++) {
						BlockState s = Blocks.AIR.defaultBlockState();
						if (random.nextInt(100) < 55) {
							int r = random.nextInt(58);
							for (int i = 0; i < palette.length; i++) {
								if ((r -= weights[i]) < 0) {
									s = palette[i];
									break;
								}
							}
						}
						level.setBlock(helper.absolutePos(new BlockPos(x, y, z)), s, 2 | 16);
					}
				}
			}
			boolean extending = round % 2 == 0;
			if (extending) {
				level.setBlock(piston, piston(round % 4 == 0, Direction.EAST), 2 | 16);
			} else {
				// Sticky piston that is out, with its head already gone (like the game does before pulling).
				level.setBlock(piston, piston(true, Direction.EAST).setValue(BlockStateProperties.EXTENDED, true), 2 | 16);
				level.setBlock(head, Blocks.AIR.defaultBlockState(), 2 | 16);
			}
			PushPreview ours = new PushPreview(level, piston, Direction.EAST, extending);
			boolean ok = ours.resolve();
			PistonStructureResolver vanilla = new PistonStructureResolver(level, piston, Direction.EAST, extending);
			boolean vanillaOk = vanilla.resolve();
			boolean same = ok == vanillaOk && (!ok || ours.toPush.equals(vanilla.getToPush()) && ours.toDestroy.equals(vanilla.getToDestroy()));
			if (!same) {
				mismatches++;
				if (first.isEmpty()) {
					first = "round " + round + (extending ? " push" : " pull") + ": we say " + ok + " " + ours.toPush
							+ ", game says " + vanillaOk + " " + vanilla.getToPush();
				}
			}
		}
		helper.assertTrue(mismatches == 0, mismatches + "/300 setups differ. First: " + first);
		helper.succeed();
	}

	/**
	 * Observer clocks driving pistons into random slime / honey / stone / obsidian / glazed
	 * terracotta / cobweb / observer / redstone block / piston builds (QC-powered half the time):
	 * 60 builds, 34 ticks each. Every time a piston really moves, the blocks the game moved must
	 * be exactly the numbered blocks of its preview, the "breaks" blocks must be gone, and a
	 * piston whose preview said ✖ must not move.
	 */
	@GameTest(maxTicks = 2200)
	public void pistonPreviewsOnClocks(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		java.util.Random random = new java.util.Random(777);
		PistonTruth truth = new PistonTruth(helper.absolutePos(new BlockPos(0, 1, 0)), helper.absolutePos(new BlockPos(7, 7, 7)));
		int rounds = 60;
		int length = 34;
		for (int round = 0; round < rounds; round++) {
			int start = 2 + round * length;
			int r = round;
			helper.runAtTickTime(start, () -> {
				for (BlockPos p : BlockPos.betweenClosed(helper.absolutePos(new BlockPos(0, 0, 0)), helper.absolutePos(new BlockPos(7, 7, 7)))) {
					level.setBlock(p, p.getY() == helper.absolutePos(BlockPos.ZERO).getY() ? Blocks.STONE.defaultBlockState()
							: Blocks.AIR.defaultBlockState(), 2 | 16);
				}
				truth.reset();
				ClockBuilds.random(level, helper.absolutePos(new BlockPos(3, 2, 3)), random);
			});
			for (int t = start + 1; t < start + length; t++) {
				helper.runAtTickTime(t, () -> truth.step(level, "build " + r));
			}
		}
		helper.runAtTickTime(2 + rounds * length + 1, () -> {
			dev.redstonelab.RedstoneLab.LOGGER.info("pistonPreviewsOnClocks: {}", truth.summary());
			truth.problems.forEach(p -> dev.redstonelab.RedstoneLab.LOGGER.info("  wrong: {}", p));
			truth.shownProblems.forEach(p -> dev.redstonelab.RedstoneLab.LOGGER.info("  on screen: {}", p));
			helper.assertTrue(truth.checked > 200, "too few piston moves checked: " + truth.summary());
			helper.assertTrue(truth.wrong == 0, truth.summary() + ". First: " + truth.problems);
			helper.succeed();
		});
	}

	/**
	 * Firing order of shown pistons on an observer clock: two pistons powered by the same block
	 * (same tick, in the order the game runs them), one behind a 1-tick repeater (+2t) and one
	 * behind a 2-tick repeater (+2t more).
	 */
	@GameTest(maxTicks = 80)
	public void pistonFiringOrder(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		BlockPos[] pistons = FiringOrderBuild.build(level, helper.absolutePos(new BlockPos(1, 1, 1)));
		for (BlockPos p : pistons) {
			PistorderFeature.toggle(player, level, p);
		}
		java.util.List<BlockPos> extended = new java.util.ArrayList<>();
		helper.runAtTickTime(70, () -> {
			String[] got = new String[4];
			for (int i = 0; i < 4; i++) {
				got[i] = PistorderFeature.orderForTest(player, pistons[i]);
			}
			String all = String.join(" | ", got);
			// Pistons 0 and 3 extend in the same tick; which one the game runs first decides #1.
			boolean zeroFirst = got[0].equals("#1") && got[3].equals("#2 \u00b7 same tick");
			boolean threeFirst = got[3].equals("#1") && got[0].equals("#2 \u00b7 same tick");
			helper.assertTrue(zeroFirst || threeFirst, "same-tick pair: " + all);
			helper.assertTrue(got[1].equals("#3 \u00b7 +2t") && got[2].equals("#4 \u00b7 +2t"), "repeater pistons: " + all);
			helper.assertTrue(PistorderFeature.labelForTest(player, pistons[1]).startsWith("\u2714"), "preview still shown");
			PistorderFeature.clear(player);
			helper.succeed();
		});
	}

	/**
	 * Item in / out land in the part of the tick the game really moved the item: a dropper fires
	 * in its block tick (dropper out, hopper in), the hopper passes it on in its block-entity tick
	 * (hopper out, chest in). Nothing may show up as "players & commands".
	 */
	@GameTest(maxTicks = 40)
	public void itemMovesInTheRightPartOfTheTick(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		BlockPos dropper = new BlockPos(4, 1, 1);
		BlockPos hopper = new BlockPos(3, 1, 1);
		BlockPos chest = new BlockPos(2, 1, 1);
		helper.setBlock(chest, Blocks.CHEST);
		helper.setBlock(hopper, Blocks.HOPPER.defaultBlockState().setValue(BlockStateProperties.FACING_HOPPER, Direction.WEST));
		helper.setBlock(dropper, Blocks.DROPPER.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.WEST));
		Container items = (Container) level.getBlockEntity(helper.absolutePos(dropper));
		items.setItem(0, new ItemStack(Items.STONE, 1));
		items.setChanged();
		for (BlockPos p : new BlockPos[]{dropper, hopper, chest}) {
			MeterFeature.toggle(player, level, helper.absolutePos(p));
		}
		// Fires the dropper (a block tick 2 game ticks later).
		helper.runAtTickTime(3, () -> helper.setBlock(new BlockPos(5, 1, 1), Blocks.REDSTONE_BLOCK));
		helper.runAtTickTime(30, () -> {
			java.util.List<String> a = MeterFeature.eventsForTest(player, "A");
			java.util.List<String> b = MeterFeature.eventsForTest(player, "B");
			java.util.List<String> c = MeterFeature.eventsForTest(player, "C");
			String all = "dropper " + a + " hopper " + b + " chest " + c;
			helper.assertTrue(a.contains("ITEM_OUT@BLOCK_TICKS"), "dropper out: " + all);
			helper.assertTrue(b.contains("ITEM_IN@BLOCK_TICKS"), "hopper in: " + all);
			helper.assertTrue(b.contains("ITEM_OUT@BLOCK_ENTITIES"), "hopper out: " + all);
			helper.assertTrue(c.contains("ITEM_IN@BLOCK_ENTITIES"), "chest in: " + all);
			helper.assertTrue(!all.contains("ITEM_IN@PLAYERS") && !all.contains("ITEM_OUT@PLAYERS"), "wrong part of the tick: " + all);
			MeterFeature.clear(player);
			helper.succeed();
		});
	}

	/** Two pistons with meters powered 3 ticks apart: the order line must read "A → 3t → B". */
	@GameTest(maxTicks = 30)
	public void pistonFireTiming(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		BlockPos p1 = new BlockPos(1, 1, 1);
		BlockPos p2 = new BlockPos(1, 1, 4);
		helper.setBlock(p1, piston(false, Direction.UP));
		helper.setBlock(p2, piston(false, Direction.UP));
		MeterFeature.toggle(player, level, helper.absolutePos(p1));
		MeterFeature.toggle(player, level, helper.absolutePos(p2));
		helper.runAtTickTime(5, () -> helper.setBlock(new BlockPos(0, 1, 1), Blocks.REDSTONE_BLOCK));
		helper.runAtTickTime(8, () -> helper.setBlock(new BlockPos(0, 1, 4), Blocks.REDSTONE_BLOCK));
		helper.runAtTickTime(15, () -> {
			String order = MeterFeature.orderForTest(player, level.getGameTime());
			helper.assertTrue(order.equals("A \u2192 3t \u2192 B"), "order line says '" + order + "'");
			MeterFeature.clear(player);
			helper.succeed();
		});
	}

	/** Right-clicks a block as the player would. */
	private static net.minecraft.world.InteractionResult click(GameTestHelper helper, ServerPlayer player, BlockPos pos) {
		BlockPos abs = helper.absolutePos(pos);
		return PistorderFeature.onUseBlock(player, helper.getLevel(), net.minecraft.world.InteractionHand.MAIN_HAND,
				new net.minecraft.world.phys.BlockHitResult(abs.getCenter(), Direction.UP, abs, false));
	}

	/**
	 * Like Pistorder: right-click a piston with an empty hand shows it, again hides it. Holding a
	 * block (so it can be placed) or sneaking does nothing.
	 */
	@GameTest(maxTicks = 20)
	public void pistonPreviewByClicking(GameTestHelper helper) {
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		// Test players are not ops: let everyone use RedstoneLab.
		dev.redstonelab.config.ServerConfig.get().permissionLevel = 0;
		BlockPos piston = new BlockPos(1, 1, 2);
		BlockPos abs = helper.absolutePos(piston);
		helper.setBlock(piston, piston(false, Direction.EAST));
		for (int x = 2; x <= 4; x++) {
			helper.setBlock(new BlockPos(x, 1, 2), Blocks.STONE);
		}
		player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new ItemStack(Items.STONE, 64));
		helper.assertTrue(click(helper, player, piston) == net.minecraft.world.InteractionResult.PASS
				&& !PistorderFeature.shownForTest(player, abs), "with a block in hand the click must place it, not show the preview");
		player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, ItemStack.EMPTY);
		player.setShiftKeyDown(true);
		helper.assertTrue(click(helper, player, piston) == net.minecraft.world.InteractionResult.PASS
				&& !PistorderFeature.shownForTest(player, abs), "sneaking does nothing");
		player.setShiftKeyDown(false);
		click(helper, player, piston);
		helper.assertTrue(PistorderFeature.shownForTest(player, abs), "empty hand click shows it");
		helper.assertTrue(PistorderFeature.labelForTest(player, abs).equals("\u2714 push 3"), "label: " + PistorderFeature.labelForTest(player, abs));
		click(helper, player, piston);
		helper.assertTrue(!PistorderFeature.shownForTest(player, abs), "clicking again hides it");
		helper.succeed();
	}

	// ------------------------------------------------------------------ polish: every feature once

	/** Comparator meter: back, side and output must match the game. */
	@GameTest(maxTicks = 20)
	public void comparatorMeter(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		floor(helper, 6, 4);
		BlockPos comparator = new BlockPos(2, 1, 2);
		// A comparator's FACING points at its back input.
		helper.setBlock(comparator, Blocks.COMPARATOR.defaultBlockState()
				.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.WEST)
				.setValue(BlockStateProperties.MODE_COMPARATOR, net.minecraft.world.level.block.state.properties.ComparatorMode.SUBTRACT));
		helper.setBlock(new BlockPos(1, 1, 2), Blocks.REDSTONE_BLOCK);
		// Side input: dust two steps from a redstone block.
		helper.setBlock(new BlockPos(2, 1, 1), Blocks.REDSTONE_WIRE);
		helper.setBlock(new BlockPos(2, 1, 0), Blocks.REDSTONE_WIRE);
		helper.setBlock(new BlockPos(3, 1, 0), Blocks.REDSTONE_BLOCK);
		MeterFeature.toggle(player, level, helper.absolutePos(comparator));
		helper.runAtTickTime(10, () -> {
			int side = level.getBlockState(helper.absolutePos(new BlockPos(2, 1, 1))).getValue(BlockStateProperties.POWER);
			int out = ((net.minecraft.world.level.block.entity.ComparatorBlockEntity) level.getBlockEntity(helper.absolutePos(comparator))).getOutputSignal();
			helper.assertTrue(out == Math.max(0, 15 - side), "game sanity: out " + out + " side " + side);
			String meter = MeterFeature.statusForTest(player, "A");
			String real = "Comparator" + DOT + "subtract" + DOT + "back 15" + DOT + "side " + side + DOT + "out " + out;
			helper.assertTrue(meter.startsWith(real), "meter says '" + meter + "', game: '" + real + "'");
			MeterFeature.clear(player);
			helper.succeed();
		});
	}

	/** Expected pulse length: right length counts as ok, a wrong expectation is flagged. */
	@GameTest(maxTicks = 200)
	public void expectedPulseLength(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		BlockPos a = new BlockPos(2, 1, 2);
		helper.setBlock(a, observer(Direction.EAST));
		MeterFeature.toggle(player, level, helper.absolutePos(a));
		helper.setBlock(new BlockPos(3, 1, 2), observer(Direction.WEST));
		// Take the clock's own pulse as the right one.
		helper.runAtTickTime(60, () -> MeterFeature.expect(player, "A", null, null, false));
		helper.runAtTickTime(110, () -> {
			String meter = MeterFeature.statusForTest(player, "A");
			helper.assertTrue(meter.contains("\u2714") && !meter.contains("\u2716"), "should be all ok: " + meter);
			MeterFeature.expect(player, "A", 5, null, false);
		});
		helper.runAtTickTime(160, () -> {
			String meter = MeterFeature.statusForTest(player, "A");
			helper.assertTrue(meter.contains("\u2716") && meter.contains("want 5t"), "should be flagged wrong: " + meter);
			MeterFeature.clear(player);
			helper.succeed();
		});
	}

	/** A pulse longer than 5 s is still checked against the expected length. */
	@GameTest(maxTicks = 200)
	public void longPulseExpect(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		BlockPos dust = new BlockPos(2, 1, 2);
		BlockPos source = new BlockPos(3, 1, 2);
		helper.setBlock(dust, Blocks.REDSTONE_WIRE);
		MeterFeature.toggle(player, level, helper.absolutePos(dust));
		MeterFeature.expect(player, "A", 150, null, false);
		helper.runAtTickTime(5, () -> helper.setBlock(source, Blocks.REDSTONE_BLOCK));
		helper.runAtTickTime(155, () -> helper.setBlock(source, Blocks.AIR));
		helper.runAtTickTime(170, () -> {
			String meter = MeterFeature.statusForTest(player, "A");
			helper.assertTrue(meter.contains("\u2714 1 ok"), "150t pulse should be checked: " + meter);
			MeterFeature.clear(player);
			helper.succeed();
		});
	}

	// ------------------------------------------------------------------ powered vs active, tick phases, moving meters

	private static long delay(ServerPlayer player, dev.redstonelab.core.MeterEvent from, dev.redstonelab.core.MeterEvent to) {
		return MeterFeature.eventTimeForTest(player, "A", to) - MeterFeature.eventTimeForTest(player, "A", from);
	}

	/** A repeater (delay 1) is powered exactly 2 ticks before its output turns on, in the block tick phase. */
	@GameTest(maxTicks = 30)
	public void repeaterPoweredBeforeActive(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		floor(helper, 4, 4);
		BlockPos repeater = new BlockPos(2, 1, 2);
		helper.setBlock(repeater, Blocks.REPEATER.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.WEST));
		MeterFeature.toggle(player, level, helper.absolutePos(repeater));
		helper.runAtTickTime(4, () -> helper.setBlock(new BlockPos(1, 1, 2), Blocks.REDSTONE_BLOCK));
		helper.runAtTickTime(20, () -> {
			long d = delay(player, dev.redstonelab.core.MeterEvent.POWERED, dev.redstonelab.core.MeterEvent.TURNED_ON);
			helper.assertTrue(d == 2, "powered 2 ticks before on, got " + d);
			int r = MeterFeature.readingForTest(player, "A");
			helper.assertTrue(dev.redstonelab.core.Reading.active(r) && dev.redstonelab.core.Reading.powered(r), "on and powered at the end");
			java.util.List<String> events = MeterFeature.eventsForTest(player, "A");
			helper.assertTrue(events.contains("TURNED_ON@BLOCK_TICKS"), "repeater turns on in the block tick phase: " + events);
			helper.assertTrue(events.indexOf("POWERED@PLAYERS") >= 0 && events.indexOf("POWERED@PLAYERS") < events.indexOf("TURNED_ON@BLOCK_TICKS"),
					"powered first, then on: " + events);
			MeterFeature.clear(player);
			helper.succeed();
		});
	}

	/** A block's own change is listed before what it causes: repeater off, then the lamp it powers. */
	@GameTest(maxTicks = 30)
	public void causeBeforeEffect(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		floor(helper, 4, 4);
		BlockPos repeater = new BlockPos(2, 1, 2);
		BlockPos lamp = new BlockPos(3, 1, 2);
		BlockPos input = new BlockPos(1, 1, 2);
		helper.setBlock(repeater, Blocks.REPEATER.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.WEST));
		helper.setBlock(lamp, Blocks.REDSTONE_LAMP);
		MeterFeature.toggle(player, level, helper.absolutePos(repeater));
		MeterFeature.toggle(player, level, helper.absolutePos(lamp));
		helper.runAtTickTime(3, () -> helper.setBlock(input, Blocks.REDSTONE_BLOCK));
		helper.runAtTickTime(10, () -> {
			int on = MeterFeature.seqForTest(player, "A", dev.redstonelab.core.MeterEvent.TURNED_ON);
			int lampPowered = MeterFeature.seqForTest(player, "B", dev.redstonelab.core.MeterEvent.POWERED);
			helper.assertTrue(on >= 0 && lampPowered > on, "repeater on (" + on + ") before lamp powered (" + lampPowered + ")");
			helper.setBlock(input, Blocks.AIR);
		});
		helper.runAtTickTime(20, () -> {
			int off = MeterFeature.seqForTest(player, "A", dev.redstonelab.core.MeterEvent.TURNED_OFF);
			int lampUnpowered = MeterFeature.seqForTest(player, "B", dev.redstonelab.core.MeterEvent.UNPOWERED);
			helper.assertTrue(off >= 0 && lampUnpowered > off, "repeater off (" + off + ") before lamp unpowered (" + lampUnpowered + ")");
			MeterFeature.clear(player);
			helper.succeed();
		});
	}

	/** A lamp stays lit for 4 ticks after it loses power: on but not powered. */
	@GameTest(maxTicks = 30)
	public void lampStaysLit(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		BlockPos lamp = new BlockPos(2, 1, 2);
		BlockPos source = new BlockPos(1, 1, 2);
		helper.setBlock(lamp, Blocks.REDSTONE_LAMP);
		MeterFeature.toggle(player, level, helper.absolutePos(lamp));
		helper.runAtTickTime(4, () -> helper.setBlock(source, Blocks.REDSTONE_BLOCK));
		helper.runAtTickTime(8, () -> helper.setBlock(source, Blocks.AIR));
		helper.runAtTickTime(20, () -> {
			long on = delay(player, dev.redstonelab.core.MeterEvent.POWERED, dev.redstonelab.core.MeterEvent.TURNED_ON);
			long off = delay(player, dev.redstonelab.core.MeterEvent.UNPOWERED, dev.redstonelab.core.MeterEvent.TURNED_OFF);
			helper.assertTrue(on == 0, "a lamp lights up the moment it is powered, got " + on);
			helper.assertTrue(off == 4, "a lamp stays lit 4 ticks after losing power, got " + off);
			java.util.List<String> events = MeterFeature.eventsForTest(player, "A");
			helper.assertTrue(events.contains("TURNED_OFF@BLOCK_TICKS"), "lamp turns off in the block tick phase: " + events);
			MeterFeature.clear(player);
			helper.succeed();
		});
	}

	/** Meters move with the blocks a piston pushes and pulls; the piston extends in the block event phase. */
	@GameTest(maxTicks = 40)
	public void meterMovesWithBlock(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		BlockPos piston = new BlockPos(1, 1, 2);
		BlockPos stone = new BlockPos(2, 1, 2);
		BlockPos power = new BlockPos(1, 2, 2);
		helper.setBlock(piston, piston(true, Direction.EAST));
		helper.setBlock(stone, Blocks.STONE);
		MeterFeature.toggle(player, level, helper.absolutePos(stone));
		MeterFeature.toggle(player, level, helper.absolutePos(piston));
		helper.runAtTickTime(3, () -> helper.setBlock(power, Blocks.REDSTONE_BLOCK));
		helper.runAtTickTime(12, () -> {
			helper.assertTrue(MeterFeature.posForTest(player, "A").equals(helper.absolutePos(stone.east())),
					"meter should follow the pushed block, is at " + MeterFeature.posForTest(player, "A"));
			helper.assertTrue(MeterFeature.eventsForTest(player, "B").contains("TURNED_ON@BLOCK_EVENTS"),
					"piston extends in the block event phase: " + MeterFeature.eventsForTest(player, "B"));
			helper.setBlock(power, Blocks.AIR);
		});
		helper.runAtTickTime(25, () -> {
			helper.assertBlockState(stone, s -> s.is(Blocks.STONE), s -> Component.literal("sticky piston should pull the stone back"));
			helper.assertTrue(MeterFeature.posForTest(player, "A").equals(helper.absolutePos(stone)),
					"meter should come back with the pulled block, is at " + MeterFeature.posForTest(player, "A"));
			helper.assertTrue(MeterFeature.count(player) == 2, "still 2 meters");
			MeterFeature.clear(player);
			helper.succeed();
		});
	}

	/** Clicking a lever shows up as "clicked", then it turns on. */
	@GameTest(maxTicks = 20)
	public void leverClick(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		floor(helper, 4, 4);
		BlockPos lever = new BlockPos(2, 1, 2);
		helper.setBlock(lever, Blocks.LEVER.defaultBlockState().setValue(BlockStateProperties.ATTACH_FACE,
				net.minecraft.world.level.block.state.properties.AttachFace.FLOOR));
		MeterFeature.toggle(player, level, helper.absolutePos(lever));
		helper.runAtTickTime(3, () -> helper.useBlock(lever, player));
		helper.runAtTickTime(6, () -> {
			java.util.List<String> events = MeterFeature.eventsForTest(player, "A");
			int clicked = events.indexOf("CLICKED@PLAYERS");
			helper.assertTrue(clicked >= 0 && events.indexOf("TURNED_ON@PLAYERS") > clicked, "clicked, then on: " + events);
			helper.assertTrue(dev.redstonelab.core.Reading.active(MeterFeature.readingForTest(player, "A")), "lever on");
			MeterFeature.clear(player);
			helper.succeed();
		});
	}

	// ------------------------------------------------------------------ accuracy fuzz

	/** What the game says: is this block "on" right now? Null for blocks we don't check. */
	private static Boolean gameSaysOn(ServerLevel level, BlockPos pos) {
		BlockState s = level.getBlockState(pos);
		if (s.is(Blocks.REDSTONE_WIRE)) {
			return s.getValue(BlockStateProperties.POWER) > 0;
		}
		if (s.is(Blocks.REPEATER) || s.is(Blocks.OBSERVER) || s.is(Blocks.LEVER)) {
			return s.getValue(BlockStateProperties.POWERED);
		}
		if (s.is(Blocks.COMPARATOR) && level.getBlockEntity(pos) instanceof net.minecraft.world.level.block.entity.ComparatorBlockEntity c) {
			return c.getOutputSignal() > 0;
		}
		if (s.is(Blocks.REDSTONE_TORCH) || s.is(Blocks.REDSTONE_WALL_TORCH) || s.is(Blocks.REDSTONE_LAMP)) {
			return s.getValue(BlockStateProperties.LIT);
		}
		if (s.getBlock() instanceof net.minecraft.world.level.block.piston.PistonBaseBlock) {
			return s.getValue(BlockStateProperties.EXTENDED);
		}
		if (s.is(Blocks.DROPPER) || s.is(Blocks.DISPENSER)) {
			return s.getValue(BlockStateProperties.TRIGGERED);
		}
		if (s.is(Blocks.HOPPER)) {
			return !s.getValue(BlockStateProperties.ENABLED);
		}
		return null;
	}

	/**
	 * Random circuits (dust, repeaters, comparators, torches, lamps, observers, pistons, levers),
	 * 8 rounds of 50 ticks with levers flipped now and then. Every tick, every meter's on/off
	 * must be exactly what the block really is (meters moved by pistons are followed).
	 */
	@GameTest(maxTicks = 430)
	public void randomCircuitsMatchGame(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		java.util.Random random = new java.util.Random(4242);
		Direction[] horizontal = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};
		int[] checks = new int[1];
		int[] mismatches = new int[1];
		String[] first = {""};
		int[] signalChecks = new int[1];
		int[] signalWrong = new int[1];
		String[] firstSignal = {""};
		java.util.List<BlockPos> levers = new java.util.ArrayList<>();
		for (int round = 0; round < 8; round++) {
			int start = 2 + round * 50;
			int r = round;
			helper.runAtTickTime(start, () -> {
				MeterFeature.clear(player);
				levers.clear();
				for (int x = 0; x <= 7; x++) {
					for (int z = 0; z <= 7; z++) {
						helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
						for (int y = 1; y <= 3; y++) {
							helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
						}
					}
				}
				for (int x = 0; x <= 7; x++) {
					for (int z = 0; z <= 7; z++) {
						if (random.nextInt(100) >= 45) {
							continue;
						}
						BlockPos pos = new BlockPos(x, 1, z);
						Direction h = horizontal[random.nextInt(4)];
						int pick = random.nextInt(100);
						BlockState s;
						if (pick < 30) {
							s = Blocks.REDSTONE_WIRE.defaultBlockState();
						} else if (pick < 42) {
							s = Blocks.REPEATER.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, h)
									.setValue(BlockStateProperties.DELAY, 1 + random.nextInt(4));
						} else if (pick < 48) {
							s = Blocks.COMPARATOR.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, h)
									.setValue(BlockStateProperties.MODE_COMPARATOR, random.nextBoolean()
											? net.minecraft.world.level.block.state.properties.ComparatorMode.COMPARE
											: net.minecraft.world.level.block.state.properties.ComparatorMode.SUBTRACT);
						} else if (pick < 56) {
							s = Blocks.REDSTONE_TORCH.defaultBlockState();
						} else if (pick < 63) {
							s = Blocks.REDSTONE_LAMP.defaultBlockState();
						} else if (pick < 70) {
							s = observer(Direction.values()[random.nextInt(6)]);
						} else if (pick < 80) {
							s = piston(random.nextBoolean(), Direction.values()[random.nextInt(6)]);
						} else if (pick < 88) {
							s = Blocks.LEVER.defaultBlockState().setValue(BlockStateProperties.ATTACH_FACE,
									net.minecraft.world.level.block.state.properties.AttachFace.FLOOR)
									.setValue(BlockStateProperties.HORIZONTAL_FACING, h);
							levers.add(pos);
						} else if (pick < 90) {
							s = Blocks.REDSTONE_BLOCK.defaultBlockState();
						} else if (pick < 93) {
							s = Blocks.DROPPER.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.UP);
						} else if (pick < 95) {
							s = Blocks.HOPPER.defaultBlockState();
						} else {
							s = Blocks.STONE.defaultBlockState();
						}
						helper.setBlock(pos, s);
					}
				}
				int meters = 0;
				for (int x = 0; x <= 7 && meters < 26; x++) {
					for (int z = 0; z <= 7 && meters < 26; z++) {
						BlockPos abs = helper.absolutePos(new BlockPos(x, 1, z));
						if (gameSaysOn(level, abs) != null) {
							MeterFeature.toggle(player, level, abs);
							meters++;
						}
					}
				}
			});
			for (int t = start + 1; t < start + 48; t++) {
				int tick = t;
				helper.runAtTickTime(t, () -> {
					if ((tick - start) % 7 == 3 && !levers.isEmpty()) {
						BlockPos lever = levers.get(random.nextInt(levers.size()));
						if (level.getBlockState(helper.absolutePos(lever)).is(Blocks.LEVER)) {
							helper.pullLever(lever);
						}
					}
					// The piston preview's client-side power check must agree with the game.
					for (BlockPos p : BlockPos.betweenClosed(helper.absolutePos(new BlockPos(0, 1, 0)), helper.absolutePos(new BlockPos(7, 3, 7)))) {
						for (Direction d : Direction.values()) {
							signalChecks[0]++;
							if (PistonView.clientSignal(level, p, d) != level.hasSignal(p, d)) {
								signalWrong[0]++;
								if (firstSignal[0].isEmpty()) {
									firstSignal[0] = "round " + r + " tick " + (tick - start) + ": " + level.getBlockState(p) + " " + d;
								}
							}
						}
					}
					for (String label : MeterFeature.labels(player)) {
						BlockPos pos = MeterFeature.posForTest(player, label);
						Boolean truth = gameSaysOn(level, pos);
						if (truth == null) {
							continue;
						}
						checks[0]++;
						boolean ours = dev.redstonelab.core.Reading.active(MeterFeature.readingForTest(player, label));
						if (ours != truth) {
							mismatches[0]++;
							if (first[0].isEmpty()) {
								first[0] = "round " + r + " tick " + (tick - start) + ": " + label + " on "
										+ level.getBlockState(pos) + " meter says " + ours + ", game says " + truth;
							}
						}
					}
				});
			}
		}
		helper.runAtTickTime(start(8), () -> {
			helper.assertTrue(checks[0] > 3000, "too few checks: " + checks[0]);
			helper.assertTrue(mismatches[0] == 0, mismatches[0] + "/" + checks[0] + " meter readings differ from the game. First: " + first[0]);
			helper.assertTrue(signalWrong[0] == 0, signalWrong[0] + "/" + signalChecks[0]
					+ " client-side power checks differ from the game. First: " + firstSignal[0]);
			MeterFeature.clear(player);
			helper.succeed();
		});
	}

	private static int start(int round) {
		return 2 + round * 50;
	}

	/** Groups: switching away and back brings the same meters back. Removing one meter removes only that one. */
	@GameTest(maxTicks = 20)
	public void groupsAndRemoveOne(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		BlockPos a = new BlockPos(1, 1, 1);
		BlockPos b = new BlockPos(3, 1, 1);
		helper.setBlock(a, Blocks.REDSTONE_LAMP);
		helper.setBlock(b, Blocks.REDSTONE_LAMP);
		MeterFeature.toggle(player, level, helper.absolutePos(a));
		MeterFeature.toggle(player, level, helper.absolutePos(b));
		helper.assertTrue(MeterFeature.count(player) == 2, "two meters");
		MeterFeature.rename(player, "A", "clock");
		MeterFeature.switchGroup(player, "other");
		helper.assertTrue(MeterFeature.count(player) == 0, "new group should be empty, has " + MeterFeature.count(player));
		MeterFeature.switchGroup(player, "default");
		helper.assertTrue(MeterFeature.count(player) == 2, "back in default: " + MeterFeature.count(player) + " meters");
		helper.assertTrue(MeterFeature.statusForTest(player, "A").startsWith("clock" + DOT + "Redstone Lamp"),
				"name kept with the group: " + MeterFeature.statusForTest(player, "A"));
		MeterFeature.removeOne(player, null);
		helper.assertTrue(MeterFeature.count(player) == 1 && MeterFeature.labels(player).equals(java.util.List.of("A")),
				"remove newest should leave A: " + MeterFeature.labels(player));
		MeterFeature.removeOne(player, helper.absolutePos(a));
		helper.assertTrue(MeterFeature.count(player) == 0, "remove by position");
		MeterFeature.switchGroup(player, "other");
		MeterFeature.deleteGroup(player, "default");
		MeterFeature.clear(player);
		helper.succeed();
	}

	/** Item meter: item count, comparator level and the next level must match the game. */
	@GameTest(maxTicks = 20)
	public void itemMeter(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		BlockPos pos = new BlockPos(1, 1, 1);
		helper.setBlock(pos, Blocks.DROPPER);
		Container dropper = (Container) level.getBlockEntity(helper.absolutePos(pos));
		MeterFeature.toggle(player, level, helper.absolutePos(pos));
		helper.assertTrue(MeterFeature.statusForTest(player, "A").startsWith("Dropper" + DOT + "empty"),
				"empty: " + MeterFeature.statusForTest(player, "A"));
		dropper.setItem(0, new ItemStack(Items.STONE, 10));
		dropper.setChanged();
		helper.runAtTickTime(3, () -> {
			String meter = MeterFeature.statusForTest(player, "A");
			// Droppers fire: no comparator signal in their text.
			helper.assertTrue(meter.equals("Dropper" + DOT + "10 Stone"), "meter says '" + meter + "'");
			dropper.setItem(1, new ItemStack(Items.REDSTONE, 3));
			dropper.setChanged();
		});
		helper.runAtTickTime(6, () -> {
			String meter = MeterFeature.statusForTest(player, "A");
			helper.assertTrue(meter.startsWith("Dropper" + DOT + "10 Stone + 3 Redstone" + DOT), "two kinds: " + meter);
			dropper.setItem(2, new ItemStack(Items.DIRT, 1));
			dropper.setChanged();
		});
		helper.runAtTickTime(9, () -> {
			String meter = MeterFeature.statusForTest(player, "A");
			helper.assertTrue(meter.startsWith("Dropper" + DOT + "14 items, 3 kinds"), "three kinds: " + meter);
			// A hopper with 10 stone: signal and the next level must match the game.
			BlockPos hopperPos = new BlockPos(3, 1, 1);
			helper.setBlock(hopperPos, Blocks.HOPPER);
			Container hopper = (Container) level.getBlockEntity(helper.absolutePos(hopperPos));
			hopper.setItem(0, new ItemStack(Items.STONE, 10));
			hopper.setChanged();
			MeterFeature.toggle(player, level, helper.absolutePos(hopperPos));
			int signal = AbstractContainerMenu.getRedstoneSignalFromContainer(hopper);
			// The game's own formula, by brute force: the fewest stone that gives the next signal.
			int next = -1;
			for (int k = 10; k <= 5 * 64 && next < 0; k++) {
				net.minecraft.world.SimpleContainer probe = new net.minecraft.world.SimpleContainer(5);
				for (int slot = 0, left = k; left > 0; slot++, left -= 64) {
					probe.setItem(slot, new ItemStack(Items.STONE, Math.min(64, left)));
				}
				if (AbstractContainerMenu.getRedstoneSignalFromContainer(probe) > signal) {
					next = k;
				}
			}
			String real = "Hopper" + DOT + "10 Stone" + DOT + "signal " + signal + " (" + (signal + 1) + " at " + next + ")";
			helper.assertTrue(MeterFeature.statusForTest(player, "B").startsWith(real), "hopper: " + MeterFeature.statusForTest(player, "B") + " / game: " + real);
			MeterFeature.clear(player);
			helper.succeed();
		});
	}
}
