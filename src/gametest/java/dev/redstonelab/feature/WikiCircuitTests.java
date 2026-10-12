package dev.redstonelab.feature;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

/**
 * Circuits from the Minecraft Wiki (Redstone circuits / Clock, Memory), built from the wiki's
 * own schematics. Each must run with the timings the wiki gives for Java Edition, the meters
 * must say exactly those timings, and every meter is checked against the game every tick
 * ({@link CircuitTruth}).
 */
public class WikiCircuitTests {
	static final String BIG = "redstonelab-test:big";
	private static final String DOT = " · ";
	/** North-west corner of the schematics. */
	private static final BlockPos O = new BlockPos(3, 1, 3);

	private static ServerPlayer start(GameTestHelper helper) {
		for (int x = 0; x < 16; x++) {
			for (int z = 0; z < 16; z++) {
				helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
			}
		}
		return helper.makeMockServerPlayerInLevel();
	}

	private static void meter(GameTestHelper helper, ServerPlayer player, BlockPos... blocks) {
		for (BlockPos b : blocks) {
			MeterFeature.toggle(player, helper.getLevel(), helper.absolutePos(b));
		}
	}

	private static void items(GameTestHelper helper, BlockPos pos, int count) {
		((Container) helper.getLevel().getBlockEntity(helper.absolutePos(pos))).setItem(0, new ItemStack(Items.STONE, count));
	}

	/** The meter's pulse lengths must be exactly what the wiki says, and every meter must match the game. */
	private static void expect(GameTestHelper helper, ServerPlayer player, CircuitTruth truth, String what, String label, String pulses) {
		String diff = truth.compare();
		helper.assertTrue(diff.isEmpty(), what + ": " + diff);
		helper.assertTrue(truth.checks > 100, what + ": too few checks " + truth.checks);
		String got = MeterFeature.pulsesForTest(player, label);
		helper.assertTrue(got.equals(pulses), what + ": wiki says '" + pulses + "', meter " + label + " says '" + got + "' ("
				+ MeterFeature.statusForTest(player, label) + ")");
	}

	private static BlockPos at(int x, int z) {
		return O.offset(x, 0, z);
	}

	/** Hopper-Loop Clock: one item bounces between two hoppers. Java: period 14gt, on 7gt / off 7gt. */
	@GameTest(structure = BIG, maxTicks = 200)
	public void hopperLoopClock(GameTestHelper helper) {
		ServerPlayer player = start(helper);
		WikiSchematic.build(helper, O, "| input+le-d | ho-e | ho-w | rc-e | output+rd-ew |-");
		items(helper, at(1, 0), 1);
		TestScript script = new TestScript();
		script.at(30, () -> meter(helper, player, at(3, 0), at(4, 0), at(1, 0), at(2, 0)));
		CircuitTruth truth = new CircuitTruth(helper.getLevel(), player);
		script.every(truth::sample);
		script.run(helper, 2, 30 + 14 * 9, () -> {
			expect(helper, player, truth, "hopper-loop clock", "A", "on 7t" + DOT + "off 7t");
			expect(helper, player, truth, "hopper-loop clock", "B", "on 7t" + DOT + "off 7t");
			MeterFeature.clear(player);
			helper.succeed();
		});
	}

	/** N-Hopper-Loop Clock with 4 hoppers. Java: period 28gt (n * 7), on 7gt / off 21gt. */
	@GameTest(structure = BIG, maxTicks = 340)
	public void fourHopperLoopClock(GameTestHelper helper) {
		ServerPlayer player = start(helper);
		WikiSchematic.build(helper, O, """
				 | input+le-d | ho-e | ho-s | rc-e | output+rd-ew |-
				 | | ho-n | ho-w | | """);
		items(helper, at(2, 1), 1);
		TestScript script = new TestScript();
		script.at(40, () -> meter(helper, player, at(3, 0), at(4, 0)));
		CircuitTruth truth = new CircuitTruth(helper.getLevel(), player);
		script.every(truth::sample);
		script.run(helper, 2, 40 + 28 * 9, () -> {
			expect(helper, player, truth, "4-hopper loop clock", "A", "on 7t" + DOT + "off 21t");
			MeterFeature.clear(player);
			helper.succeed();
		});
	}

	/**
	 * Etho Hopper Clock with 3 items: two hoppers, two comparators, two sticky pistons moving a
	 * block of redstone. Java: period [(n-1)*8+2]*2 = 36gt; each comparator on 34gt, off 2gt.
	 */
	@GameTest(structure = BIG, maxTicks = 500)
	public void ethoHopperClock(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		ServerPlayer player = start(helper);
		WikiSchematic.build(helper, O, """
				 | rd-nsew! | sp-e! | se-e | BR | sp-w | rd-nsew! |-
				 | SB | rc-w! | ho-e | ho-w | rc-e! | SB""");
		items(helper, at(3, 1), 3);
		TestScript script = new TestScript();
		script.at(100, () -> meter(helper, player, at(1, 1), at(4, 1), at(1, 0), at(4, 0)));
		PistonTruth previews = new PistonTruth(helper.absolutePos(new BlockPos(0, 1, 0)), helper.absolutePos(new BlockPos(15, 4, 15)));
		script.every(() -> previews.step(level, "tick " + level.getGameTime()));
		CircuitTruth truth = new CircuitTruth(level, player);
		script.every(truth::sample);
		script.run(helper, 2, 100 + 36 * 9, () -> {
			expect(helper, player, truth, "Etho hopper clock", "A", "on 34t" + DOT + "off 2t");
			expect(helper, player, truth, "Etho hopper clock", "B", "on 34t" + DOT + "off 2t");
			for (String p : new String[]{"C", "D"}) {
				String s = MeterFeature.statusForTest(player, p);
				helper.assertTrue(s.contains("moves every 36t"), "piston " + p + ": " + s);
			}
			helper.assertTrue(previews.wrong == 0 && previews.shownWrong == 0 && previews.checked > 10,
					previews.summary() + " " + previews.problems + " " + previews.shownProblems);
			MeterFeature.clear(player);
			helper.succeed();
		});
	}

	/** 4gt Subtraction Clock: only the dust next to the comparator toggles. Java: on 2gt / off 2gt. */
	@GameTest(structure = BIG, maxTicks = 120)
	public void subtractionClock4gt(GameTestHelper helper) {
		ServerPlayer player = start(helper);
		WikiSchematic w = WikiSchematic.build(helper, O, """
				 |input+rd-ew|rs-e|SB|-
				 ||rd-nse|rd-ew|-
				 ||output+rd-ns|""");
		TestScript script = new TestScript();
		// The input: a block of redstone under the input dust.
		script.at(3, () -> helper.setBlock(w.mark("input").below(), Blocks.REDSTONE_BLOCK));
		script.at(20, () -> meter(helper, player, at(1, 1), at(1, 0), at(1, 2)));
		CircuitTruth truth = new CircuitTruth(helper.getLevel(), player);
		script.every(truth::sample);
		script.run(helper, 2, 100, () -> {
			expect(helper, player, truth, "4gt subtraction clock", "A", "on 2t" + DOT + "off 2t");
			MeterFeature.clear(player);
			helper.succeed();
		});
	}

	/** 8-20gt Subtraction Clock with the repeater at 1: Java: period 8gt, on 4gt / off 4gt. */
	@GameTest(structure = BIG, maxTicks = 160)
	public void subtractionClock8gt(GameTestHelper helper) {
		ServerPlayer player = start(helper);
		WikiSchematic w = WikiSchematic.build(helper, O, """
				 |input+rd-ew|rs-e|SB|rd-nsew|-
				 ||rd-ne|rr-w1|SB""");
		TestScript script = new TestScript();
		script.at(3, () -> helper.setBlock(w.mark("input").below(), Blocks.REDSTONE_BLOCK));
		script.at(30, () -> meter(helper, player, at(2, 1), at(1, 0), at(1, 1), at(3, 0)));
		CircuitTruth truth = new CircuitTruth(helper.getLevel(), player);
		script.every(truth::sample);
		script.run(helper, 2, 30 + 8 * 12, () -> {
			expect(helper, player, truth, "8gt subtraction clock", "A", "on 4t" + DOT + "off 4t");
			MeterFeature.clear(player);
			helper.succeed();
		});
	}

	/** 58gt Fader Pulser: a comparator fader loop renewed by a torch. Java: on 54gt / off 4gt. */
	@GameTest(structure = BIG, maxTicks = 420)
	public void faderPulser58gt(GameTestHelper helper) {
		ServerPlayer player = start(helper);
		WikiSchematic w = WikiSchematic.build(helper, O, """
				 |||input+rd-ns!|||-
				 |rd-se|rc-e|SB|rt-w|output+rd-ew|-
				 |rd-ne|rc-w|rd-ew|rd-nw|""");
		TestScript script = new TestScript();
		script.at(3, () -> w.settle(helper));
		script.at(30, () -> meter(helper, player, at(4, 1), at(3, 1), at(1, 1), at(1, 2)));
		CircuitTruth truth = new CircuitTruth(helper.getLevel(), player);
		script.every(truth::sample);
		script.run(helper, 2, 30 + 58 * 6, () -> {
			// The torch's output dust: the torch stays on 4gt, then the loop holds it off (period 58gt).
			expect(helper, player, truth, "58gt fader pulser", "A", "on 4t" + DOT + "off 54t");
			String loop = "torch " + MeterFeature.pulsesForTest(player, "B") + ", comparators " + MeterFeature.pulsesForTest(player, "C")
					+ " / " + MeterFeature.pulsesForTest(player, "D");
			helper.assertTrue(MeterFeature.pulsesForTest(player, "C").equals("on 54t" + DOT + "off 4t"), "fader loop on 54t: " + loop);
			MeterFeature.clear(player);
			helper.succeed();
		});
	}

	/** 6-observer clock: N observers in a loop, period N redstone ticks. Java: on 2gt / off 10gt. */
	@GameTest(structure = BIG, maxTicks = 200)
	public void sixObserverClock(GameTestHelper helper) {
		ServerPlayer player = start(helper);
		WikiSchematic.build(helper, O, """
				 |obs-w|obs-w|obs-n||-
				 |obs-s|obs-e|obs-e|output+rd-ew!""");
		TestScript script = new TestScript();
		script.at(3, () -> restart(helper, at(1, 1)));
		script.at(30, () -> meter(helper, player, at(3, 1), at(0, 0), at(2, 0)));
		CircuitTruth truth = new CircuitTruth(helper.getLevel(), player);
		script.every(truth::sample);
		script.run(helper, 2, 30 + 12 * 10, () -> {
			expect(helper, player, truth, "6-observer clock", "A", "on 2t" + DOT + "off 10t");
			MeterFeature.clear(player);
			helper.succeed();
		});
	}

	/** 5-observer clock, the gap bridged by dust powered through a block. Java: on 2gt / off 8gt. */
	@GameTest(structure = BIG, maxTicks = 200)
	public void fiveObserverClock(GameTestHelper helper) {
		ServerPlayer player = start(helper);
		WikiSchematic.build(helper, O, """
				 |SB|obs-w|obs-w|obs-n||-
				 |rd-ew|rd-ew|obs-e|obs-e|output+rd-ew!""");
		TestScript script = new TestScript();
		script.at(3, () -> restart(helper, at(2, 1)));
		script.at(30, () -> meter(helper, player, at(4, 1), at(0, 1), at(1, 0)));
		CircuitTruth truth = new CircuitTruth(helper.getLevel(), player);
		script.every(truth::sample);
		script.run(helper, 2, 30 + 10 * 10, () -> {
			expect(helper, player, truth, "5-observer clock", "A", "on 2t" + DOT + "off 8t");
			MeterFeature.clear(player);
			helper.succeed();
		});
	}

	/** Double observer clock, the second observer placed by hand. Java: 6gt, on 2gt / off 4gt. */
	@GameTest(structure = BIG, maxTicks = 150)
	public void doubleObserverClockPlaced(GameTestHelper helper) {
		ServerPlayer player = start(helper);
		WikiSchematic.build(helper, O, "|rd-ew|obs-w||output+rd-ew!");
		TestScript script = new TestScript();
		script.at(3, () -> helper.setBlock(at(2, 0), WikiSchematic.state("obs-e")));
		script.at(20, () -> meter(helper, player, at(3, 0), at(0, 0), at(1, 0), at(2, 0)));
		CircuitTruth truth = new CircuitTruth(helper.getLevel(), player);
		script.every(truth::sample);
		script.run(helper, 2, 20 + 6 * 12, () -> {
			expect(helper, player, truth, "6gt observer clock", "A", "on 2t" + DOT + "off 4t");
			MeterFeature.clear(player);
			helper.succeed();
		});
	}

	/** Double observer clock, the second observer pushed in by a piston. Java: 4gt, on 2gt / off 2gt. */
	@GameTest(structure = BIG, maxTicks = 150)
	public void doubleObserverClockPushed(GameTestHelper helper) {
		ServerPlayer player = start(helper);
		WikiSchematic.build(helper, O, """
				 |rd-ew|obs-w||output+rd-ew!|-
				 |||obs-e||-
				 |||pi-n||""");
		TestScript script = new TestScript();
		script.at(3, () -> helper.setBlock(at(3, 2), Blocks.REDSTONE_BLOCK));
		script.at(20, () -> meter(helper, player, at(3, 0), at(0, 0), at(1, 0), at(2, 0)));
		CircuitTruth truth = new CircuitTruth(helper.getLevel(), player);
		script.every(truth::sample);
		script.run(helper, 2, 20 + 4 * 15, () -> {
			expect(helper, player, truth, "4gt observer clock", "A", "on 2t" + DOT + "off 2t");
			MeterFeature.clear(player);
			helper.succeed();
		});
	}

	/**
	 * Simple 4 game tick Piston Clock (D), side view: a sticky piston with a block of redstone on
	 * it that powers the dust that powers the piston. The wiki says 4gt; built like this it runs
	 * 6gt in 1.21.11 (the dust on 1gt / off 5gt), checked here against the game.
	 */
	@GameTest(structure = BIG, maxTicks = 120)
	public void pistonClock4gt(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		ServerPlayer player = start(helper);
		// |sp-$e |BR |-   (y = 3)
		// |SB |rd-$ew! |output |-   (y = 2)
		// |GB-$ |SB   (y = 1)
		BlockPos base = O;
		helper.setBlock(base.offset(0, 0, 0), Blocks.GLASS);
		helper.setBlock(base.offset(1, 0, 0), Blocks.STONE);
		helper.setBlock(base.offset(0, 1, 0), Blocks.STONE);
		helper.setBlock(base.offset(2, 1, 0), Blocks.REDSTONE_LAMP);
		helper.setBlock(base.offset(0, 2, 0), WikiSchematic.state("sp-e"));
		helper.setBlock(base.offset(1, 1, 0), WikiSchematic.dust(level, helper.absolutePos(base.offset(1, 1, 0))));
		TestScript script = new TestScript();
		// The block of redstone goes on last: the clock starts.
		script.at(3, () -> helper.setBlock(base.offset(1, 2, 0), Blocks.REDSTONE_BLOCK));
		script.at(20, () -> meter(helper, player, base.offset(1, 1, 0), base.offset(0, 2, 0), base.offset(2, 1, 0)));
		PistonTruth previews = new PistonTruth(helper.absolutePos(new BlockPos(0, 1, 0)), helper.absolutePos(new BlockPos(15, 5, 15)));
		script.every(() -> previews.step(level, "tick " + level.getGameTime()));
		CircuitTruth truth = new CircuitTruth(level, player);
		script.every(truth::sample);
		script.run(helper, 2, 20 + 4 * 20, () -> {
			// The wiki says 4gt (on 1, off 3); this build runs 6gt in 1.21.11 (piston out 3t), the meter
			// must say what the game does.
			expect(helper, player, truth, "piston clock D", "A", "on 1t" + DOT + "off 5t");
			String piston = MeterFeature.statusForTest(player, "B");
			helper.assertTrue(piston.contains("moves every 6t" + DOT + "out 3t"), "piston: " + piston);
			helper.assertTrue(previews.wrong == 0 && previews.shownWrong == 0, previews.summary() + " " + previews.problems);
			MeterFeature.clear(player);
			helper.succeed();
		});
	}

	/**
	 * T Flip-flop O1 (observer, Java): a piston pushes an observer next to a second piston, whose
	 * short pulse toggles a block of redstone. Toggles on every rising edge of T.
	 */
	@GameTest(structure = BIG, maxTicks = 420)
	public void observerTFlipFlop(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		ServerPlayer player = start(helper);
		WikiSchematic w = WikiSchematic.build(helper, O, "|input+T|sp-e|obs-e||sp-e|BR||output+Q");
		BlockPos t = w.mark("T");
		BlockPos q = w.mark("Q");
		// Q read from dust: a lamp would add its 4t turn-off delay.
		helper.setBlock(q, WikiSchematic.dust(level, helper.absolutePos(q)));
		TestScript script = new TestScript();
		// T: on for 10t every 40t.
		for (int tick = 20; tick < 380; tick += 40) {
			script.at(tick, () -> helper.setBlock(t, Blocks.REDSTONE_BLOCK));
			script.at(tick + 10, () -> helper.setBlock(t, Blocks.AIR));
		}
		script.at(5, () -> meter(helper, player, q, at(2, 0), at(1, 0), at(4, 0)));
		PistonTruth previews = new PistonTruth(helper.absolutePos(new BlockPos(0, 1, 0)), helper.absolutePos(new BlockPos(15, 4, 15)));
		script.every(() -> previews.step(level, "tick " + level.getGameTime()));
		CircuitTruth truth = new CircuitTruth(level, player);
		script.every(truth::sample);
		script.run(helper, 2, 400, () -> {
			expect(helper, player, truth, "observer TFF", "A", "on 40t" + DOT + "off 40t");
			helper.assertTrue(previews.wrong == 0 && previews.shownWrong == 0 && previews.checked > 10,
					previews.summary() + " " + previews.problems + " " + previews.shownProblems);
			MeterFeature.clear(player);
			helper.succeed();
		});
	}

	/**
	 * Dual-piston T flip-flop O (Java, quasi-connectivity), 3 levels: two pistons under torches
	 * swap a block of redstone. Toggles on the falling edge of T, once per T pulse.
	 */
	@GameTest(structure = BIG, maxTicks = 440)
	public void dualPistonTFlipFlop(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		ServerPlayer player = start(helper);
		WikiSchematic.build(helper, O, """
				 ||output+Q|output+Q||-
				 |pi-e!|pe-e|BR|pi-w|-
				 ||output+Q|output+Q|""");
		WikiSchematic.build(helper, O.above(), """
				 |||||-
				 |rt-e!|SB|SB|rt-w!|-
				 ||||""");
		WikiSchematic.build(helper, O.above(2), """
				 ||input+T|input+T||-
				 ||rd-ew|rd-ew||-
				 ||input+T|input+T|""");
		BlockPos q = at(2, 0);
		BlockPos notQ = at(1, 2);
		helper.setBlock(q, WikiSchematic.dust(level, helper.absolutePos(q)));
		helper.setBlock(notQ, WikiSchematic.dust(level, helper.absolutePos(notQ)));
		BlockPos t = O.offset(1, 2, 0);
		TestScript script = new TestScript();
		for (int tick = 20; tick < 400; tick += 40) {
			script.at(tick, () -> helper.setBlock(t, Blocks.REDSTONE_BLOCK));
			script.at(tick + 10, () -> helper.setBlock(t, Blocks.AIR));
		}
		script.at(5, () -> meter(helper, player, q, notQ, at(0, 1), at(3, 1), O.offset(0, 1, 1)));
		PistonTruth previews = new PistonTruth(helper.absolutePos(new BlockPos(0, 1, 0)), helper.absolutePos(new BlockPos(15, 4, 15)));
		script.every(() -> previews.step(level, "tick " + level.getGameTime()));
		CircuitTruth truth = new CircuitTruth(level, player);
		script.every(truth::sample);
		script.run(helper, 2, 420, () -> {
			// One toggle per T pulse (80t a cycle); the block of redstone leaves on one edge and
			// arrives (2t of piston movement later) on the other: 38t / 42t, checked against the game.
			expect(helper, player, truth, "dual-piston TFF", "A", "on 38t" + DOT + "off 42t");
			helper.assertTrue(previews.wrong == 0 && previews.shownWrong == 0 && previews.checked > 5,
					previews.summary() + " " + previews.problems + " " + previews.shownProblems);
			MeterFeature.clear(player);
			helper.succeed();
		});
	}

	/**
	 * 3x3 piston T flip-flop N (Java), 2 levels: a quasi-connected sticky piston makes a 1-tick
	 * pulse, which makes the other sticky piston drop / take back a block a torch powers.
	 * Toggles on the rising edge of T: Q on 40t / off 40t.
	 */
	@GameTest(structure = BIG, maxTicks = 440)
	public void pistonTFlipFlopN(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		ServerPlayer player = start(helper);
		WikiSchematic.build(helper, O, """
				 |sp-u|SB|AB|-
				 |SB|AB|AB|-
				 |SB|AB|rt-n!""");
		WikiSchematic w = WikiSchematic.build(helper, O.above(), """
				 |MB|rr-e1|sp-s|-
				 |rr-n1|AB|MB|-
				 |input+T|AB||output+Q""");
		BlockPos q = w.mark("Q");
		BlockPos t = w.mark("T");
		helper.setBlock(q.below(), Blocks.STONE);
		helper.setBlock(q, WikiSchematic.dust(level, helper.absolutePos(q)));
		TestScript script = new TestScript();
		for (int tick = 20; tick < 400; tick += 40) {
			script.at(tick, () -> helper.setBlock(t, Blocks.REDSTONE_BLOCK));
			script.at(tick + 10, () -> helper.setBlock(t, Blocks.AIR));
		}
		script.at(5, () -> meter(helper, player, q, O.offset(2, 1, 0), O.offset(0, 0, 0), O.offset(1, 1, 0), O.offset(0, 1, 1)));
		PistonTruth previews = new PistonTruth(helper.absolutePos(new BlockPos(0, 1, 0)), helper.absolutePos(new BlockPos(15, 5, 15)));
		script.every(() -> previews.step(level, "tick " + level.getGameTime()));
		CircuitTruth truth = new CircuitTruth(level, player);
		script.every(truth::sample);
		script.run(helper, 2, 420, () -> {
			expect(helper, player, truth, "piston TFF N", "A", "on 40t" + DOT + "off 40t");
			helper.assertTrue(previews.wrong == 0 && previews.shownWrong == 0 && previews.checked > 5,
					previews.summary() + " " + previews.problems + " " + previews.shownProblems);
			MeterFeature.clear(player);
			helper.succeed();
		});
	}

	/**
	 * Flying machine: the wiki's "Vertical, 2-way, common slim variant" engine (2 observers,
	 * 2 sticky pistons, 4 slime blocks), turned on its side to fly west. The meters ride along on
	 * the observers and pistons, every piston preview must match what really moves, and the
	 * meters must match the game every tick while the whole thing flies.
	 */
	@GameTest(structure = BIG, maxTicks = 100)
	public void flyingMachine(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		ServerPlayer player = start(helper);
		// Side view, top row first:  obs-s | SL  /  sp-s | SL  /  SL | sp-n  /  SL | obs-n
		// "up" in the picture = east here; columns = z.
		int b = 10;
		int y = 4;
		helper.setBlock(new BlockPos(b + 3, y, 3), Blocks.OBSERVER.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.EAST));
		helper.setBlock(new BlockPos(b + 3, y, 4), Blocks.SLIME_BLOCK);
		helper.setBlock(new BlockPos(b + 2, y, 3), WikiSchematic.state("sp-w"));
		helper.setBlock(new BlockPos(b + 2, y, 4), Blocks.SLIME_BLOCK);
		helper.setBlock(new BlockPos(b + 1, y, 3), Blocks.SLIME_BLOCK);
		helper.setBlock(new BlockPos(b + 1, y, 4), WikiSchematic.state("sp-e"));
		helper.setBlock(new BlockPos(b, y, 3), Blocks.SLIME_BLOCK);
		helper.setBlock(new BlockPos(b, y, 4), Blocks.OBSERVER.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.WEST));
		meter(helper, player, new BlockPos(b + 3, y, 3), new BlockPos(b, y, 4), new BlockPos(b + 2, y, 3), new BlockPos(b + 1, y, 4));
		TestScript script = new TestScript();
		// Start: something changes in front of the east observer.
		script.at(5, () -> helper.setBlock(new BlockPos(b + 4, y, 3), Blocks.STONE));
		script.at(6, () -> helper.setBlock(new BlockPos(b + 4, y, 3), Blocks.AIR));
		PistonTruth previews = new PistonTruth(helper.absolutePos(new BlockPos(0, y - 1, 0)), helper.absolutePos(new BlockPos(15, y + 1, 15)));
		script.every(() -> previews.step(level, "tick " + level.getGameTime()));
		CircuitTruth truth = new CircuitTruth(level, player);
		script.every(truth::sample);
		BlockPos[] starts = {helper.absolutePos(new BlockPos(b + 3, y, 3)), helper.absolutePos(new BlockPos(b, y, 4)),
				helper.absolutePos(new BlockPos(b + 2, y, 3)), helper.absolutePos(new BlockPos(b + 1, y, 4))};
		script.run(helper, 2, 60, () -> {
			// Absolute positions: the meters must sit on their moved blocks, all shifted the same.
			BlockPos a = MeterFeature.posForTest(player, "A");
			BlockPos shift = a.subtract(starts[0]);
			helper.assertTrue(Math.abs(shift.getX()) + Math.abs(shift.getZ()) >= 4 && level.getBlockState(a).is(Blocks.OBSERVER),
					"it should fly 4+ blocks with the meter on its observer: moved " + shift.toShortString() + ", " + level.getBlockState(a));
			// The two halves move one after the other: a half can be a block behind.
			String[] labels = {"A", "B", "C", "D"};
			net.minecraft.world.level.block.Block[] kinds = {Blocks.OBSERVER, Blocks.OBSERVER, Blocks.STICKY_PISTON, Blocks.STICKY_PISTON};
			for (int i = 1; i < 4; i++) {
				BlockPos p = MeterFeature.posForTest(player, labels[i]);
				BlockPos want = starts[i].offset(shift);
				helper.assertTrue((level.getBlockState(p).is(kinds[i]) || level.getBlockState(p).is(Blocks.MOVING_PISTON)) && p.distManhattan(want) <= 1, labels[i] + " should ride along on its "
						+ kinds[i].getName().getString() + ": at " + p.toShortString() + " (" + level.getBlockState(p) + "), about " + want.toShortString());
			}
			String diff = truth.compare();
			helper.assertTrue(diff.isEmpty(), "flying machine: " + diff);
			helper.assertTrue(previews.wrong == 0 && previews.shownWrong == 0 && previews.checked > 3,
					previews.summary() + " " + previews.problems + " " + previews.shownProblems);
			String c = MeterFeature.statusForTest(player, "C");
			helper.assertTrue(c.contains("moves every"), "piston C: " + c);
			MeterFeature.clear(player);
			helper.succeed();
		});
	}

	/** Rising edge detectors: input pulses 20t on every 60t, the output must fire once per rise. */
	private static void edgeDetector(GameTestHelper helper, String what, String schematic, String outputPulses, String order) {
		ServerPlayer player = start(helper);
		WikiSchematic w = WikiSchematic.build(helper, O, schematic);
		BlockPos in = w.mark("input");
		BlockPos out = w.mark("output");
		TestScript script = new TestScript();
		script.at(3, () -> w.settle(helper));
		// The first rise after building primes the circuit (its torch / locks settle): start after that.
		for (int tick = 20; tick < 560; tick += 60) {
			script.at(tick, () -> helper.setBlock(in.below(), Blocks.REDSTONE_BLOCK));
			script.at(tick + 20, () -> helper.setBlock(in.below(), Blocks.STONE));
		}
		script.at(70, () -> meter(helper, player, in, out));
		CircuitTruth truth = new CircuitTruth(helper.getLevel(), player);
		script.every(truth::sample);
		script.run(helper, 2, 580, () -> {
			expect(helper, player, truth, what, "A", "on 20t" + DOT + "off 40t");
			expect(helper, player, truth, what, "B", outputPulses);
			String line = MeterFeature.orderForTest(player, helper.getLevel().getGameTime());
			helper.assertTrue(line.equals(order), what + " order line: " + line);
			MeterFeature.clear(player);
			helper.succeed();
		});
	}

	/** Locked-Repeater Rising Edge Detector (Corner). Wiki: delay 3 ticks (6gt), output 1 tick (2gt). */
	@GameTest(structure = BIG, maxTicks = 620)
	public void lockedRepeaterRisingEdgeCorner(GameTestHelper helper) {
		edgeDetector(helper, "locked-repeater RED (corner)", """
				 | input+rd-ew | SB | rt-w! | rr-e1! | SB |-
				 | | | rd-ne! | rr-e1! | lr-s1 |-
				 | | | | | output+rd-ns""", "on 2t" + DOT + "off 58t", "A" + " \u2192 6t \u2192 " + "B");
	}

	/** Locked-Repeater Rising Edge Detector (In-line). Wiki: delay 3 ticks (6gt), output 1 tick (2gt). */
	@GameTest(structure = BIG, maxTicks = 620)
	public void lockedRepeaterRisingEdgeInline(GameTestHelper helper) {
		edgeDetector(helper, "locked-repeater RED (in-line)", """
				 | input+rd-ew | SB | SB | lr-e1 | output+rd-ew |-
				 | | rt-n! | rr-n1! | rr-n1! | |-
				 | | rd-ne! | rd-new! | rd-nw! |""", "on 2t" + DOT + "off 58t", "A" + " \u2192 6t \u2192 " + "B");
	}

	/** Moved-Block Rising Edge Detector: a piston pulls the block that carries the output away. Wiki: delay 1 tick (2gt). */
	@GameTest(structure = BIG, maxTicks = 620)
	public void movedBlockRisingEdge(GameTestHelper helper) {
		edgeDetector(helper, "moved-block RED", """
				 | input+rd-ew | rd-sew | rr-e1 | MB | output+rd-ew |-
				 | | rd-ne | rr-e2 | sp-n |""", "on 2t" + DOT + "off 58t", "A" + " \u2192 2t \u2192 " + "B");
	}

	/** Replaces a block with air and back, with updates: the observer watching it starts the chain. */
	private static void restart(GameTestHelper helper, BlockPos pos) {
		BlockState s = helper.getBlockState(pos);
		helper.getLevel().setBlock(helper.absolutePos(pos), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
		helper.getLevel().setBlock(helper.absolutePos(pos), s, Block.UPDATE_ALL);
	}
}
