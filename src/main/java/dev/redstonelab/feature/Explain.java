package dev.redstonelab.feature;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.ComparatorBlock;
import net.minecraft.world.level.block.CopperBulbBlock;
import net.minecraft.world.level.block.CrafterBlock;
import net.minecraft.world.level.block.DaylightDetectorBlock;
import net.minecraft.world.level.block.DiodeBlock;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.NoteBlock;
import net.minecraft.world.level.block.ObserverBlock;
import net.minecraft.world.level.block.PressurePlateBlock;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.RedstoneLampBlock;
import net.minecraft.world.level.block.RedstoneTorchBlock;
import net.minecraft.world.level.block.RedstoneWallTorchBlock;
import net.minecraft.world.level.block.RepeaterBlock;
import net.minecraft.world.level.block.SculkSensorBlock;
import net.minecraft.world.level.block.TargetBlock;
import net.minecraft.world.level.block.TrappedChestBlock;
import net.minecraft.world.level.block.WeightedPressurePlateBlock;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.ComparatorMode;

import dev.redstonelab.mixin.DiodeBlockInvoker;
import dev.redstonelab.mixin.RedstoneTorchBlockInvoker;
import dev.redstonelab.util.Fmt;
import dev.redstonelab.util.PowerUtil;

/**
 * Plain words for a redstone block: what it is doing now and why ("Dust · power 13 · 2 blocks
 * from Repeater (15)"), and the rule behind it ("Loses 1 power per block..."). Works the way the
 * game checks power, on the server and on the client (where comparator levels aren't known).
 */
public final class Explain {
	private static final Direction[] DIRS = Direction.values();
	private static final String DOT = " · ";
	/** A comparator's level on the client: on, but the number isn't sent to clients. */
	private static final int UNKNOWN = 16;

	private Explain() {
	}

	/**
	 * What the block does now and why, then the rule. Empty for blocks that aren't redstone (and
	 * plain blocks that aren't powered). {@code longOff}: a torch has been off without power for
	 * more than 3t (a working one relights after 2t; the game forgets toggles after 60t, so its
	 * own burnout list can't tell for long).
	 */
	public static List<String> lines(Level level, BlockPos pos, boolean longOff) {
		BlockState state = level.getBlockState(pos);
		String now = now(level, pos, state, longOff);
		if (now == null) {
			return List.of();
		}
		List<String> lines = new ArrayList<>(2);
		lines.add(now);
		String rule = rule(level, pos, state);
		if (rule != null) {
			lines.add(rule);
		}
		return lines;
	}

	// ------------------------------------------------------------------ now

	private static @Nullable String now(Level level, BlockPos pos, BlockState state, boolean longOff) {
		Block block = state.getBlock();
		String name = name(state);
		if (block instanceof RedStoneWireBlock) {
			return dust(level, pos, state);
		}
		if (block instanceof RepeaterBlock) {
			return repeater(level, pos, state);
		}
		if (block instanceof ComparatorBlock) {
			return comparator(level, pos, state);
		}
		if (block instanceof RedstoneTorchBlock) {
			return torch(level, pos, state, longOff);
		}
		if (block instanceof ObserverBlock) {
			Direction facing = state.getValue(BlockStateProperties.FACING);
			return name + DOT + (state.getValue(BlockStateProperties.POWERED) ? "pulsing" : "watching") + " "
					+ name(level.getBlockState(pos.relative(facing))) + " (" + word(facing) + ")";
		}
		if (block instanceof PistonBaseBlock) {
			return piston(level, pos, state);
		}
		if (block instanceof RedstoneLampBlock) {
			boolean lit = state.getValue(BlockStateProperties.LIT);
			Cause cause = cause(level, pos, DIRS);
			if (cause != null) {
				// Lamps light at once when updated: powered and off = it missed the update.
				return name + DOT + (lit ? "on" + DOT + "powered by " + describe(level, cause)
						: "powered but off: lights on the next block update (BUD)");
			}
			return name + DOT + (lit ? "turning off (4t after its power went)" : "off");
		}
		if (block instanceof DispenserBlock) {
			Cause direct = cause(level, pos, DIRS);
			if (direct != null) {
				return name + DOT + "powered by " + describe(level, direct);
			}
			Cause qc = cause(level, pos.above(), DIRS);
			if (qc != null) {
				return name + DOT + "powered from above (QC) by " + describe(level, qc);
			}
			return name + DOT + "not powered";
		}
		if (block instanceof HopperBlock) {
			Cause cause = cause(level, pos, DIRS);
			if (!state.getValue(BlockStateProperties.ENABLED)) {
				return name + DOT + "locked" + (cause != null ? DOT + "powered by " + describe(level, cause) : "");
			}
			Direction facing = state.getValue(BlockStateProperties.FACING_HOPPER);
			BlockState into = level.getBlockState(pos.relative(facing));
			return name + DOT + "not locked" + DOT + "points " + (into.isAir() ? "at nothing" : "into " + name(into)) + " (" + word(facing) + ")";
		}
		if (state.isSignalSource()) {
			int power = PowerUtil.powerAt(level, pos, state);
			if (block instanceof LeverBlock || block instanceof ButtonBlock) {
				return name + DOT + (power > 0 ? "on" + DOT + "gives 15" : "off");
			}
			return name + DOT + (power > 0 ? "gives " + power : "off");
		}
		if (state.hasProperty(BlockStateProperties.POWERED) || state.hasProperty(BlockStateProperties.TRIGGERED)) {
			// Doors, trapdoors, note blocks, rails, copper bulbs, crafters...
			Cause cause = cause(level, pos, DIRS);
			if (cause != null) {
				return name + DOT + "powered by " + describe(level, cause);
			}
			boolean powered = state.hasProperty(BlockStateProperties.POWERED) && state.getValue(BlockStateProperties.POWERED);
			return name + DOT + (powered ? "powered" : "not powered");
		}
		if (state.isRedstoneConductor(level, pos) && !state.hasBlockEntity()) {
			return solid(level, pos, state);
		}
		return null;
	}

	private static String dust(Level level, BlockPos pos, BlockState state) {
		int power = state.getValue(RedStoneWireBlock.POWER);
		if (power == 0) {
			return "Dust" + DOT + "off";
		}
		// Back along the dust, 1 more power per block, to whatever gives it the power.
		BlockPos cur = pos;
		int p = power;
		for (int steps = 0; steps <= 15; steps++) {
			for (Direction d : DIRS) {
				BlockPos n = cur.relative(d);
				// Dust ignores power that came from dust (also through a block).
				int s = signal(level, n, d, true);
				if (s == p || s == UNKNOWN) {
					Cause cause = new Cause(d, s, from(level, cur, n, d, s, true), n);
					if (steps == 0) {
						return "Dust" + DOT + "power " + power + DOT + "from " + describe(level, cause);
					}
					return "Dust" + DOT + "power " + power + DOT + steps + (steps == 1 ? " block" : " blocks") + " from "
							+ name(level.getBlockState(cause.from)) + (s == UNKNOWN ? "" : " (" + s + ")");
				}
			}
			BlockPos next = strongerDust(level, cur, p + 1);
			if (next == null) {
				break;
			}
			cur = next;
			p++;
		}
		return "Dust" + DOT + "power " + power;
	}

	/** Connected dust next to {@code pos} (also one up or down) with exactly {@code power}. */
	private static @Nullable BlockPos strongerDust(Level level, BlockPos pos, int power) {
		boolean upCut = level.getBlockState(pos.above()).isRedstoneConductor(level, pos.above());
		for (Direction d : Direction.Plane.HORIZONTAL) {
			BlockPos side = pos.relative(d);
			BlockPos[] candidates = {side, upCut ? null : side.above(),
					level.getBlockState(side).isRedstoneConductor(level, side) ? null : side.below()};
			for (BlockPos c : candidates) {
				if (c != null) {
					BlockState cs = level.getBlockState(c);
					if (cs.getBlock() instanceof RedStoneWireBlock && cs.getValue(RedStoneWireBlock.POWER) == power) {
						return c;
					}
				}
			}
		}
		return null;
	}

	private static String repeater(Level level, BlockPos pos, BlockState state) {
		String delay = "delay " + 2 * state.getValue(RepeaterBlock.DELAY) + "t";
		boolean out = state.getValue(BlockStateProperties.POWERED);
		Direction back = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
		if (state.getValue(RepeaterBlock.LOCKED)) {
			for (Direction side : new Direction[]{back.getClockWise(), back.getCounterClockWise()}) {
				BlockPos n = pos.relative(side);
				BlockState ns = level.getBlockState(n);
				if (ns.getBlock() instanceof DiodeBlock && emit(level, n, ns, side, false) > 0) {
					return "Repeater" + DOT + "locked " + (out ? "on" : "off") + DOT + "by " + name(ns) + " (" + word(side) + ")";
				}
			}
			return "Repeater" + DOT + "locked " + (out ? "on" : "off");
		}
		Cause cause = input(level, pos, back);
		if (cause != null) {
			return "Repeater" + DOT + (out ? "on" : "turning on") + DOT + delay + DOT + "powered by " + describe(level, cause);
		}
		return "Repeater" + DOT + (out ? "turning off" : "off") + DOT + delay;
	}

	/** What powers a repeater or comparator from behind (dust counts at its own level). */
	private static @Nullable Cause input(Level level, BlockPos pos, Direction back) {
		BlockPos n = pos.relative(back);
		BlockState ns = level.getBlockState(n);
		int s = signal(level, n, back, false);
		if (ns.getBlock() instanceof RedStoneWireBlock) {
			s = Math.max(s, ns.getValue(RedStoneWireBlock.POWER));
		}
		return s > 0 ? new Cause(back, s, from(level, pos, n, back, s, false), n) : null;
	}

	private static String comparator(Level level, BlockPos pos, BlockState state) {
		String mode = state.getValue(ComparatorBlock.MODE) == ComparatorMode.SUBTRACT ? "subtract" : "compare";
		Direction back = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
		// Containers are read straight behind it, or through one solid block.
		BlockPos b = pos.relative(back);
		BlockState bs = level.getBlockState(b);
		String reads = bs.hasAnalogOutputSignal() ? name(bs) : null;
		if (reads == null && bs.isRedstoneConductor(level, b)) {
			BlockState behind = level.getBlockState(b.relative(back));
			reads = behind.hasAnalogOutputSignal() ? name(behind) : null;
		}
		if (level.isClientSide()) {
			// Clients don't know comparator levels or what is in containers.
			boolean on = state.getValue(BlockStateProperties.POWERED);
			if (reads != null) {
				return "Comparator" + DOT + mode + DOT + (on ? "on" : "off") + DOT + "reads " + reads + " (how full it is)";
			}
			Cause cause = input(level, pos, back);
			return "Comparator" + DOT + mode + DOT + (on ? "on" : "off") + (cause != null ? DOT + "powered by " + describe(level, cause) : "");
		}
		DiodeBlockInvoker diode = (DiodeBlockInvoker) state.getBlock();
		int in = diode.redstonelab$backInput(level, pos, state);
		int side = diode.redstonelab$sideInput(level, pos, state);
		int out = PowerUtil.powerAt(level, pos, state);
		return "Comparator" + DOT + mode + DOT + "back " + in + (reads != null ? " (" + reads + ")" : "") + ", side " + side + " → out " + out;
	}

	/** A torch that is off although its block isn't powered (relighting, or burnt out when it lasts over 2t). */
	public static boolean torchOffUnpowered(Level level, BlockPos pos, BlockState state) {
		return state.getBlock() instanceof RedstoneTorchBlock && !state.getValue(BlockStateProperties.LIT) && !torchPowered(level, pos, state);
	}

	/** The torch's own check: power into the block it's on. */
	private static boolean torchPowered(Level level, BlockPos pos, BlockState state) {
		Direction toBlock = torchBlock(state);
		return signal(level, pos.relative(toBlock), toBlock, false) > 0;
	}

	private static Direction torchBlock(BlockState state) {
		return state.getBlock() instanceof RedstoneWallTorchBlock
				? state.getValue(BlockStateProperties.HORIZONTAL_FACING).getOpposite() : Direction.DOWN;
	}

	private static String torch(Level level, BlockPos pos, BlockState state, boolean longOff) {
		boolean lit = state.getValue(BlockStateProperties.LIT);
		Direction toBlock = torchBlock(state);
		BlockPos on = pos.relative(toBlock);
		BlockState onState = level.getBlockState(on);
		boolean powered = torchPowered(level, pos, state);
		String where = name(onState) + (toBlock == Direction.DOWN ? " under it" : " behind it");
		if (powered) {
			String by = "";
			if (emit(level, on, onState, toBlock, false) > 0) {
				by = " gives power";
			} else {
				Cause cause = strongestDirect(level, on, pos, false);
				by = " is powered" + (cause != null ? " by " + name(level.getBlockState(cause.from)) + num(cause.signal) : "");
			}
			return "Torch" + DOT + (lit ? "turning off (2t)" : "off") + DOT + where + by;
		}
		if (lit) {
			return "Torch" + DOT + "on" + DOT + where + " isn't powered";
		}
		boolean burnt = longOff || !level.isClientSide() && RedstoneTorchBlockInvoker.redstonelab$toggledTooOften(level, pos, false);
		return "Torch" + DOT + (burnt ? "burnt out (switched 8 times in 60t)" : "turning on (2t)");
	}

	private static String piston(Level level, BlockPos pos, BlockState state) {
		String name = name(state);
		Direction facing = state.getValue(BlockStateProperties.FACING);
		boolean out = state.getValue(BlockStateProperties.EXTENDED);
		String info = PistonView.powerInfo(level, pos);
		if ("stuck".equals(info)) {
			PushPreview preview = new PushPreview(level, pos, facing, true);
			preview.resolve();
			String why = preview.failure == PushPreview.Failure.TOO_MANY ? "more than " + PushPreview.MAX_PUSH + " blocks"
					: preview.blocker != null ? name(level.getBlockState(preview.blocker)) + " can't move" : "blocked";
			return name + DOT + "powered, can't push: " + why;
		}
		if ("BUD".equals(info)) {
			return name + DOT + (out ? "out, not powered: retracts on the next block update (BUD)"
					: "powered, not out yet: moves on the next block update (BUD)");
		}
		List<Direction> sides = new ArrayList<>();
		for (Direction d : DIRS) {
			if (d != facing) {
				sides.add(d);
			}
		}
		Cause cause = cause(level, pos, sides.toArray(new Direction[0]));
		if (cause != null) {
			return name + DOT + (out ? "out" : "in") + DOT + "powered by " + describe(level, cause);
		}
		if ("QC".equals(info)) {
			Cause qc = cause(level, pos.above(), Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST);
			return name + DOT + (out ? "out" : "in") + DOT + "powered from above (QC)"
					+ (qc != null ? " by " + name(level.getBlockState(qc.from)) + num(qc.signal) + " next to the block above it" : "");
		}
		return name + DOT + (out ? "out" : "in") + DOT + "not powered";
	}

	private static @Nullable String solid(Level level, BlockPos pos, BlockState state) {
		// Strong: a repeater, comparator, torch under it, lever on it... Weak: dust.
		Cause strong = null;
		Cause weak = null;
		for (Direction d : DIRS) {
			BlockPos n = pos.relative(d);
			BlockState ns = level.getBlockState(n);
			int s = emit(level, n, ns, d, true);
			if (s <= 0) {
				continue;
			}
			Cause c = new Cause(d, s, n, null);
			if (ns.getBlock() instanceof RedStoneWireBlock) {
				weak = weak == null || s > weak.signal ? c : weak;
			} else {
				strong = strong == null || s > strong.signal ? c : strong;
			}
		}
		if (strong != null) {
			return name(state) + DOT + "strongly powered by " + describe(level, strong) + DOT + "powers dust too";
		}
		if (weak != null) {
			return name(state) + DOT + "weakly powered by " + describe(level, weak) + DOT + "powers components, not dust";
		}
		return null;
	}

	// ------------------------------------------------------------------ the rules

	private static @Nullable String rule(Level level, BlockPos pos, BlockState state) {
		Block block = state.getBlock();
		if (block instanceof RedStoneWireBlock) {
			return "Loses 1 power per block. Powers the block under it and what it points into.";
		}
		if (block instanceof RepeaterBlock) {
			return "Waits its delay (2-8t, right-click), then gives 15. A repeater or comparator powering its side locks it.";
		}
		if (block instanceof ComparatorBlock) {
			return (state.getValue(ComparatorBlock.MODE) == ComparatorMode.SUBTRACT ? "Subtract: back minus side."
					: "Compare: back if back ≥ side, else 0.") + " Reads how full a container behind it is.";
		}
		if (block instanceof RedstoneTorchBlock) {
			return "On unless its block is powered; switches 2t later. 8 switches in 60t burn it out.";
		}
		if (block instanceof ObserverBlock) {
			return "When the block it faces changes, pulses 2t out its back.";
		}
		if (block instanceof PistonBaseBlock) {
			return "Pushes up to 12 blocks" + (state.is(Blocks.STICKY_PISTON) ? " and pulls 1 back" : "")
					+ ". Power next to the block above it counts too (QC), but only on a block update.";
		}
		if (block instanceof RedstoneLampBlock) {
			return "Turns on at once, off 4t after its power goes.";
		}
		if (block instanceof DispenserBlock) {
			return "Fires once, 4t after it gets power. Power next to the block above it counts too (QC).";
		}
		if (block instanceof HopperBlock) {
			return "Moves 1 item every 8t into what it points at. Powered = locked.";
		}
		if (block instanceof LeverBlock) {
			return "Gives 15 to its neighbors and strongly powers the block it's on.";
		}
		if (block instanceof ButtonBlock) {
			return "Gives 15 for a moment (stone 20t, wood 30t) and strongly powers the block it's on.";
		}
		if (block instanceof WeightedPressurePlateBlock) {
			return "Gives more power the more things are on it.";
		}
		if (block instanceof PressurePlateBlock) {
			return "Gives 15 while something is on it.";
		}
		if (state.is(Blocks.REDSTONE_BLOCK)) {
			return "Always gives 15 to dust and components next to it, but doesn't power blocks.";
		}
		if (block instanceof TargetBlock) {
			return "Gives power when hit: the closer to the center, the more (up to 15).";
		}
		if (block instanceof DaylightDetectorBlock) {
			return "Power from sunlight. Right-click: from darkness instead.";
		}
		if (block instanceof SculkSensorBlock) {
			return "Gives power when it hears a vibration: the closer, the more.";
		}
		if (block instanceof TrappedChestBlock) {
			return "Gives power for each player who has it open.";
		}
		if (block instanceof NoteBlock) {
			return "Plays a note each time it gets power.";
		}
		if (block instanceof CopperBulbBlock) {
			return "Switches on / off each time it gets power.";
		}
		if (block instanceof CrafterBlock) {
			return "Crafts once each time it gets power.";
		}
		if (state.isRedstoneConductor(level, pos) && !state.isSignalSource() && !state.hasBlockEntity()
				&& !state.hasProperty(BlockStateProperties.POWERED) && !state.hasProperty(BlockStateProperties.TRIGGERED)) {
			return "Strong power (repeater, comparator, torch under it, lever on it) powers dust and components next to it."
					+ " Weak power (dust) only components.";
		}
		return null;
	}

	// ------------------------------------------------------------------ power, like the game checks it

	/**
	 * What powers the block at {@code at} from the given sides: the strongest. {@code from} is the
	 * source; {@code via} the solid block it came through (null when it's right next to it).
	 */
	private record Cause(Direction dir, int signal, BlockPos from, @Nullable BlockPos via) {
	}

	private static @Nullable Cause cause(Level level, BlockPos at, Direction... sides) {
		Cause best = null;
		for (Direction d : sides) {
			BlockPos n = at.relative(d);
			int s = signal(level, n, d, false);
			if (s > 0 && (best == null || s > best.signal)) {
				best = new Cause(d, s, from(level, at, n, d, s, false), n);
			}
		}
		return best;
	}

	/** The block that gives the signal: {@code n} itself, or what powers it when it's a solid block. */
	private static BlockPos from(Level level, BlockPos receiver, BlockPos n, Direction d, int signal, boolean ignoreDust) {
		BlockState ns = level.getBlockState(n);
		if (emit(level, n, ns, d, false) >= signal || !ns.isRedstoneConductor(level, n)) {
			return n;
		}
		Cause direct = strongestDirect(level, n, receiver, ignoreDust);
		return direct != null ? direct.from : n;
	}

	/** The strongest block strongly (directly) powering the solid block at {@code pos}, not counting {@code skip}. */
	private static @Nullable Cause strongestDirect(Level level, BlockPos pos, BlockPos skip, boolean ignoreDust) {
		Cause best = null;
		for (Direction d : DIRS) {
			BlockPos m = pos.relative(d);
			BlockState ms = level.getBlockState(m);
			if (m.equals(skip) || ignoreDust && ms.getBlock() instanceof RedStoneWireBlock) {
				continue;
			}
			int s = emit(level, m, ms, d, true);
			if (s > 0 && (best == null || s > best.signal)) {
				best = new Cause(d, s, m, null);
			}
		}
		return best;
	}

	/** "Repeater (west, 15)", or "Lever (15) through Stone (below)". */
	private static String describe(Level level, Cause cause) {
		String from = name(level.getBlockState(cause.from));
		if (cause.via != null && !cause.via.equals(cause.from)) {
			return from + num(cause.signal) + " through " + name(level.getBlockState(cause.via)) + " (" + word(cause.dir) + ")";
		}
		return from + " (" + word(cause.dir) + (cause.signal == UNKNOWN ? "" : ", " + cause.signal) + ")";
	}

	private static String num(int signal) {
		return signal == UNKNOWN ? "" : " (" + signal + ")";
	}

	/** Like {@link Level#getSignal}: what the block at {@code pos} gives its neighbor on the {@code dir.getOpposite()} side. */
	private static int signal(Level level, BlockPos pos, Direction dir, boolean ignoreDust) {
		BlockState state = level.getBlockState(pos);
		if (ignoreDust && state.getBlock() instanceof RedStoneWireBlock) {
			return 0;
		}
		int s = emit(level, pos, state, dir, false);
		if (state.isRedstoneConductor(level, pos)) {
			for (Direction d : DIRS) {
				BlockPos m = pos.relative(d);
				BlockState ms = level.getBlockState(m);
				if (!(ignoreDust && ms.getBlock() instanceof RedStoneWireBlock)) {
					s = Math.max(s, emit(level, m, ms, d, true));
				}
			}
		}
		return s;
	}

	/** One block's signal toward a side; a lit comparator on the client is {@link #UNKNOWN}. */
	private static int emit(Level level, BlockPos pos, BlockState state, Direction dir, boolean direct) {
		if (level.isClientSide() && state.getBlock() instanceof ComparatorBlock) {
			return state.getValue(BlockStateProperties.POWERED) && state.getValue(BlockStateProperties.HORIZONTAL_FACING) == dir ? UNKNOWN : 0;
		}
		return direct ? state.getDirectSignal(level, pos, dir) : state.getSignal(level, pos, dir);
	}

	// ------------------------------------------------------------------ words

	static String name(BlockState state) {
		Block block = state.getBlock();
		if (block instanceof RedStoneWireBlock) {
			return "Dust";
		}
		if (block instanceof RedstoneTorchBlock) {
			return "Torch";
		}
		if (block instanceof RedstoneLampBlock) {
			return "Lamp";
		}
		return Fmt.pretty(state);
	}

	private static String word(Direction d) {
		return switch (d) {
			case UP -> "above";
			case DOWN -> "below";
			default -> d.getSerializedName();
		};
	}
}
