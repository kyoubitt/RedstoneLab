package dev.redstonelab.feature;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ComparatorBlock;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.RedstoneTorchBlock;
import net.minecraft.world.level.block.RepeaterBlock;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import dev.redstonelab.capture.PowerWatch;
import dev.redstonelab.config.ServerConfig;
import dev.redstonelab.core.MeterEvent;
import dev.redstonelab.core.PulseMeter;
import dev.redstonelab.core.Reading;
import dev.redstonelab.core.RunStats;
import dev.redstonelab.core.TickPhase;
import dev.redstonelab.core.Ticks;
import dev.redstonelab.display.LabBossBar;
import dev.redstonelab.mixin.RedstoneTorchBlockInvoker;
import dev.redstonelab.server.LabServer;
import dev.redstonelab.util.Fmt;
import dev.redstonelab.util.PowerUtil;

/** One metered block: what it did each tick and the text about it. */
final class Meter implements PowerWatch.Listener {
	final ResourceKey<Level> dimension;
	/** Changes when a piston moves the block (the meter goes with it). */
	BlockPos pos;
	final String label;
	final int color;
	final PulseMeter pulses;
	/** Since when it is off and unpowered (a torch: burnt out after 2t), MAX_VALUE = it isn't. */
	long offUnpoweredSince = Long.MAX_VALUE;
	/** Power level 0-15 (what the block gives or gets). */
	int power;
	/** Active: lamp lit, piston extended, repeater output on... Pulse lengths are measured on this. */
	boolean active;
	/** Gets a redstone signal (repeater input, piston incl. QC...). */
	boolean powered;
	/** This tick: bit 4 = turned on, bit 8 = got powered (so a 0-tick pulse still shows). */
	int tickState;
	int tickMin;
	int tickMax;
	/** {@link MeterEvent} flags seen during the current tick. */
	int events;
	/** Power per tick (oldest first) and the event flags of that tick. */
	final int[] history;
	final int[] eventHistory;
	int historyFill;
	final LabBossBar bossBar;
	final MeterState owner;
	/** Block name, refreshed while sampling. */
	String blockName = "";
	/** The player's own name for the meter ("clock", "output"), or empty. */
	String name = "";
	/** Items inside, or -1 if the block is not a container. */
	int itemCount = -1;
	/** The items changed since the last sample (contents text needs a refresh). */
	boolean itemsChanged;
	/** Container: what is inside, "10 Stone + 3 Dirt", refreshed while sampling. */
	String contents = "";
	/** Container: "3 at 83" (next comparator level), refreshed while sampling. */
	String nextLevel = "";
	/** Container: ticks between items going in / out and between dispenser/dropper shots. */
	final RunStats inGaps = new RunStats(10);
	final RunStats outGaps = new RunStats(10);
	final RunStats fireGaps = new RunStats(10);
	long lastIn = Long.MIN_VALUE;
	long lastOut = Long.MIN_VALUE;
	long lastFire = Long.MIN_VALUE;
	long now;

	Meter(MeterState owner, ResourceKey<Level> dimension, BlockPos pos, int index, int reading) {
		this.owner = owner;
		this.dimension = dimension;
		this.pos = pos;
		this.label = String.valueOf(MeterFeature.LABELS.charAt(index));
		this.color = MeterFeature.COLORS[index];
		this.pulses = new PulseMeter(ServerConfig.get().pulseWindow);
		this.power = Reading.level(reading);
		this.active = Reading.active(reading);
		this.powered = Reading.powered(reading);
		this.tickMin = this.power;
		this.tickMax = this.power;
		this.tickState = 0;
		this.history = new int[ServerConfig.get().meterHistoryTicks];
		this.eventHistory = new int[this.history.length];
		this.bossBar = new LabBossBar(Component.literal(this.label), LabBossBar.colorFor(index));
		this.pulses.update(Long.MIN_VALUE, this.active ? 15 : 0);
	}

	/** 1 = active, 2 = powered. */
	int stateBits() {
		return (this.active ? 1 : 0) | (this.powered ? 2 : 0);
	}

	/** Expected on / off length in ticks, -1 = not checked. */
	int expectOn = -1;
	int expectOff = -1;
	int okCount;
	int wrongCount;
	String lastWrong = "";
	/** A wrong pulse happened this tick: tell the player once. */
	String alert;
	/** What happened this tick, in order: (sequence number << 8) | (tick phase << 5) | event ordinal. */
	final List<Integer> order = new ArrayList<>();
	/** The last 64 of those, for the game tests. */
	final ArrayDeque<long[]> recent = new ArrayDeque<>();

	/** Notes the event for the "inspect a tick" list (consecutive repeats are skipped). */
	private void record(MeterEvent event) {
		if (this.order.size() < 32 && (this.order.isEmpty() || (this.order.get(this.order.size() - 1) & 31) != event.ordinal())) {
			int packed = MeterFeature.nextSeq() << 8 | TickPhase.current.ordinal() << 5 | event.ordinal();
			this.order.add(packed);
			if (this.recent.size() >= 64) {
				this.recent.removeFirst();
			}
			ServerLevel level = LabServer.level(this.dimension);
			this.recent.addLast(new long[]{level == null ? 0 : level.getGameTime(), packed});
		}
	}

	void flag(MeterEvent event) {
		this.events |= event.bit();
		this.record(event);
	}

	/** Compares a finished on or off length with the expected one. */
	private void check(boolean onLength, long ticks) {
		int expected = onLength ? this.expectOn : this.expectOff;
		if (expected < 0) {
			return;
		}
		if (ticks == expected) {
			this.okCount++;
			return;
		}
		this.wrongCount++;
		this.lastWrong = (onLength ? "on " : "off ") + ticks + "t, want " + expected + "t";
		this.alert = this.label + ": " + this.lastWrong;
		this.flag(MeterEvent.WRONG_LENGTH);
	}

	@Override
	public void onWatchEvent(MeterEvent event) {
		this.flag(event);
	}

	/** Pistons: when they really move, which can be later than their power changes (QC, BUD). */
	final RunStats extendGaps = new RunStats(8);
	final RunStats outLengths = new RunStats(8);
	long lastExtend = Long.MIN_VALUE;
	boolean piston;
	boolean comparator;
	/** Dropper, dispenser, crafter: on = fires (crafts). */
	boolean fires;
	/** Chest-like container: on = comparator signal above 0. */
	boolean signalBased;
	boolean wire;

	@Override
	public void onWatchBlockEvent(int type, long gameTime) {
		if (!this.piston) {
			return;
		}
		if (type == 0) {
			this.lastExtend = this.addGap(this.extendGaps, this.lastExtend, gameTime);
		} else if (this.lastExtend != Long.MIN_VALUE && gameTime >= this.lastExtend) {
			this.outLengths.add(gameTime - this.lastExtend);
		}
	}

	@Override
	public void onWatchChange(PowerWatch.Change change) {
		if (change.stateChanged()) {
			BlockState from = change.oldState();
			BlockState to = change.newState();
			if (from.is(Blocks.MOVING_PISTON) || to.is(Blocks.MOVING_PISTON) || MeterFeature.MOVING.contains(change.pos())) {
				this.flag(MeterEvent.MOVED);
			} else if (from.isAir() && !to.isAir()) {
				this.flag(MeterEvent.PLACED);
			} else if (!from.isAir() && to.isAir()) {
				this.flag(MeterEvent.BROKEN);
			}
			// A piston passing through (retracting, being pushed) is not a new block.
			if (!to.isAir() && !to.is(Blocks.MOVING_PISTON) && to.getBlock() != this.kindBlock) {
				this.kindChanged = true;
			}
		}
		if (change.readingChanged()) {
			this.apply(change.gameTime(), change.newReading());
		}
	}

	/** Takes a new reading: flags what changed and measures the pulses (on = active). */
	void apply(long time, int reading) {
		boolean nowPowered = Reading.powered(reading);
		boolean nowActive = Reading.active(reading);
		int level = Reading.level(reading);
		if (nowPowered != this.powered) {
			this.flag(nowPowered ? MeterEvent.POWERED : MeterEvent.UNPOWERED);
		}
		if (nowActive && !this.active) {
			this.flag(MeterEvent.TURNED_ON);
			this.onRise(time);
		} else if (!nowActive && this.active) {
			this.flag(MeterEvent.TURNED_OFF);
		} else if (level != this.power && nowActive) {
			this.flag(MeterEvent.POWER_CHANGED);
		}
		if (nowActive || nowPowered) {
		this.offUnpoweredSince = Long.MAX_VALUE;
	} else if (this.offUnpoweredSince == Long.MAX_VALUE) {
		this.offUnpoweredSince = time;
	}
	boolean edge = nowActive != this.active;
		this.tickState |= (nowActive && !this.active ? 4 : 0) | (nowPowered && !this.powered ? 8 : 0);
		this.power = level;
		this.active = nowActive;
		this.powered = nowPowered;
		this.tickMin = Math.min(this.tickMin, this.power);
		this.tickMax = Math.max(this.tickMax, this.power);
		if (!edge) {
			return;
		}
		int dir = this.pulses.update(time, nowActive ? 15 : 0);
		if (dir < 0 && this.pulses.lastOnLength() >= 0) {
			this.check(true, this.pulses.lastOnLength());
		} else if (dir > 0 && this.pulses.lastOffLength() >= 0 && !this.pulses.lastWasPause()) {
			// a pause (the circuit stopped) is not a wrong pulse
			this.check(false, this.pulses.lastOffLength());
		}
	}

	private void onRise(long time) {
		int i = this.riseCount++ % this.rises.length;
		this.rises[i] = time;
		this.riseSeqs[i] = MeterFeature.nextSeq();
	}

	/** Recent rising edges (ring), for the order line, and their place in the game's order inside the tick. */
	final long[] rises = new long[8];
	final int[] riseSeqs = new int[8];
	int riseCount;

	/** One line for the HUD: "Observer · on 2t · off 4t", "Hopper · 12 items · signal 1". */
	String status() {
		StringBuilder sb = new StringBuilder(this.name.isEmpty() ? this.blockName : this.name + " \u00b7 " + this.blockName);
		if (this.comparator) {
			// Only reads the world (never changes the meter), so it can't disturb sampling.
			ServerLevel level = LabServer.level(this.dimension);
			String info = level == null ? "" : ContainerInfo.comparatorInfo(level, this.pos, this.power);
			if (!info.isEmpty()) {
				sb.append(" \u00b7 ").append(info);
			}
		}
		if (this.itemCount >= 0) {
			// Containers: items, comparator level and how fast items move.
			if (this.itemCount == 0) {
				sb.append(" \u00b7 empty");
			} else {
				sb.append(" \u00b7 ").append(this.contents.isEmpty() ? this.itemCount + (this.itemCount == 1 ? " item" : " items") : this.contents);
				sb.append(" \u00b7 signal ").append(this.power);
				if (!this.nextLevel.isEmpty()) {
					sb.append(" (").append(this.nextLevel).append(")");
				}
			}
			ServerLevel level = LabServer.level(this.dimension);
			if (level != null && ContainerInfo.loadedAround(level, this.pos) && PowerUtil.chestBlocked(level, this.pos)) {
				// Vanilla: a chest that can't open gives comparators 0.
				sb.append(" \u00b7 blocked");
			}
			appendRate(sb, "fires", this.fireGaps, this.lastFire);
			// A dropper firing is its items going out: say it once.
			boolean firing = !this.fireGaps.isEmpty() && this.recent(this.fireGaps, this.lastFire);
			boolean in = !this.inGaps.isEmpty() && this.recent(this.inGaps, this.lastIn);
			boolean out = !firing && !this.outGaps.isEmpty() && this.recent(this.outGaps, this.lastOut);
			String inText = Ticks.lengths(this.inGaps.values());
			String outText = Ticks.lengths(this.outGaps.values());
			if (in && out && inText.equals(outText)) {
				// A hopper passing items on: "in / out every 8t".
				sb.append(" \u00b7 in / out every ").append(inText);
			} else if (in && out) {
				sb.append(" \u00b7 in / out every ").append(inText).append(" / ").append(outText);
			} else {
				appendRate(sb, "in", this.inGaps, this.lastIn);
				if (!firing) {
					appendRate(sb, "out", this.outGaps, this.lastOut);
				}
			}
			return sb.toString();
		}
		if (this.piston && !this.extendGaps.isEmpty() && this.recent(this.extendGaps, this.lastExtend)) {
			// Pistons: what they really did, from the game's own piston events.
			sb.append(" \u00b7 moves every ").append(range(this.extendGaps));
			if (!this.outLengths.isEmpty()) {
				sb.append(" \u00b7 out ").append(range(this.outLengths));
			}
		} else {
			// Dust: no "power 15" (texture packs show the level on the dust itself).
			String info = this.blockInfo();
			if (!info.isEmpty()) {
				sb.append(" \u00b7 ").append(info);
			}
			String pulses = this.pulses.summary();
			if (!pulses.isEmpty()) {
				sb.append(" \u00b7 ").append(pulses);
			}
		}
		if (this.expectOn >= 0 || this.expectOff >= 0) {
			sb.append(" \u00b7 ").append(this.wrongCount == 0 ? "\u2714 " + this.okCount + " ok"
					: "\u2716 " + this.wrongCount + " wrong (" + this.lastWrong + ")");
		}
		return sb.toString();
	}

	/** State details the bars don't show: repeater delay and lock, target / daylight / sculk / plate strength, burnt-out torch. */
	private String blockInfo() {
		ServerLevel level = LabServer.level(this.dimension);
		if (level == null || !ContainerInfo.loadedAround(level, this.pos)) {
			return "";
		}
		BlockState state = level.getBlockState(this.pos);
		if (state.getBlock() instanceof RepeaterBlock) {
			return "delay " + 2 * state.getValue(RepeaterBlock.DELAY) + "t" + (state.getValue(RepeaterBlock.LOCKED) ? " \u00b7 locked" : "");
		}
		if (state.hasProperty(BlockStateProperties.POWER) && !(state.getBlock() instanceof RedStoneWireBlock)) {
			// Target block, daylight detector, sculk sensors, weighted plates: the level matters.
			int level15 = state.getValue(BlockStateProperties.POWER);
			return level15 > 0 ? "power " + level15 : "";
		}
		if (state.hasAnalogOutputSignal()) {
			// Composter, cauldron, cake, lectern, beehive, respawn anchor...: what a comparator reads.
			return "signal " + PowerUtil.comparatorSignal(level, this.pos, state);
		}
		if (state.getBlock() instanceof RedstoneTorchBlock) {
			// Burnt out (8 toggles in 60t): off although its block isn't powered. A working torch
			// relights 2t after its power goes; the game's toggle list can't be used for long
			// (any torch ticking anywhere drops entries older than 60t, the torch stays out 160t).
			boolean burnt = !state.getValue(RedstoneTorchBlock.LIT) && !this.powered
					&& (level.getGameTime() - this.offUnpoweredSince >= 2
					|| RedstoneTorchBlockInvoker.redstonelab$toggledTooOften(level, this.pos, false));
			return burnt ? "burnt out" : "";
		}
		return "";
	}

	/** It happened within 2 of its own periods (at least 10 s): a slow clock still counts as running. */
	private boolean recent(RunStats gaps, long last) {
		return last != Long.MIN_VALUE && this.now - last <= Math.max(200, 2 * gaps.max());
	}

	/** " · in every 8t" while it keeps happening (hidden after 10 s without it). */
	private void appendRate(StringBuilder sb, String what, RunStats gaps, long last) {
		if (gaps.isEmpty() || !this.recent(gaps, last)) {
			return;
		}
		sb.append(" \u00b7 ").append(what).append(" every ").append(Ticks.lengths(gaps.values()));
	}

	private static String range(RunStats r) {
		return Ticks.lengths(r.values());
	}

	/** Records the time of an event and the gap since the last one. */
	long addGap(RunStats gaps, long last, long time) {
		if (last != Long.MIN_VALUE && time > last) {
			gaps.add(time - last);
		}
		return time;
	}

	void push(int value, int events) {
		System.arraycopy(this.history, 1, this.history, 0, this.history.length - 1);
		System.arraycopy(this.eventHistory, 1, this.eventHistory, 0, this.eventHistory.length - 1);
		this.history[this.history.length - 1] = value;
		this.eventHistory[this.eventHistory.length - 1] = events;
		this.historyFill = Math.min(this.historyFill + 1, this.history.length);
	}

	/** "turned on 3 ticks ago" for the newest event within {@code window} ticks, or null. */
	String lastEvent(int window) {
		for (int age = 0; age < Math.min(window, this.historyFill); age++) {
			// Updates and level changes happen all the time: not worth a mention.
			int flags = this.eventHistory[this.eventHistory.length - 1 - age]
					& ~(MeterEvent.BLOCK_UPDATE.bit() | MeterEvent.SHAPE_UPDATE.bit() | MeterEvent.POWER_CHANGED.bit());
			if (flags != 0) {
				// Lowest ordinal first: power changes and moves matter more than updates.
				return "last: " + MeterEvent.of(flags).get(0).displayName() + ", " + MeterFeature.ago(age);
			}
		}
		return null;
	}

	/** "ON 15", "OFF · powered", "OFF". */
	String onOff() {
		String text = this.active ? "ON" + (this.power > 0 ? " " + this.power : "") : "OFF";
		// Containers: on = comparator signal, being powered is a different thing, don't mix them.
		if (this.signalBased || this.powered == this.active) {
			return text;
		}
		return text + (this.powered ? " \u00b7 powered" : " \u00b7 not powered");
	}

	String name(ServerLevel level) {
		return Fmt.pretty(level.getBlockState(this.pos));
	}

	/** A different block is here now (replaced, or placed where the meter was): what to show depends on it. */
	boolean kindChanged;
	/** The block the kind was read from. */
	Block kindBlock = Blocks.AIR;

	/** Name and kind (piston, comparator, container...) of the block under the meter. */
	void readKind(ServerLevel level) {
		BlockState placed = level.getBlockState(this.pos);
		if (!placed.isAir() && !placed.is(Blocks.MOVING_PISTON)) {
			this.kindBlock = placed.getBlock();
		}
		this.blockName = Fmt.pretty(placed);
		this.piston = placed.getBlock() instanceof PistonBaseBlock;
		this.comparator = placed.getBlock() instanceof ComparatorBlock;
		this.wire = placed.is(Blocks.REDSTONE_WIRE);
		this.fires = placed.hasProperty(BlockStateProperties.TRIGGERED);
		this.itemCount = ContainerInfo.itemCount(level, this.pos);
		this.nextLevel = this.itemCount > 0 ? ContainerInfo.nextLevel(level, this.pos, this.power) : "";
		this.contents = ContainerInfo.contents(level, this.pos);
		this.signalBased = this.itemCount >= 0 && !this.fires && !(placed.getBlock() instanceof HopperBlock);
	}
}
