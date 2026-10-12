package dev.redstonelab.feature;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.RedstoneTorchBlock;
import net.minecraft.world.level.block.RepeaterBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

import dev.redstonelab.capture.PowerWatch;
import dev.redstonelab.config.ServerConfig;
import dev.redstonelab.core.ContainerMath;
import dev.redstonelab.core.MeterEvent;
import dev.redstonelab.core.PulseMeter;
import dev.redstonelab.core.Reading;
import dev.redstonelab.core.RunStats;
import dev.redstonelab.core.Ticks;
import dev.redstonelab.core.Sparkline;
import dev.redstonelab.display.LabBossBar;
import dev.redstonelab.display.MarkerService;
import dev.redstonelab.net.LabNetworking;
import dev.redstonelab.net.LabPayloads;
import dev.redstonelab.server.LabServer;
import dev.redstonelab.server.Sessions;
import dev.redstonelab.util.Fmt;
import dev.redstonelab.util.Msg;
import dev.redstonelab.util.PowerUtil;

/**
 * Meters, like Redstone Multimeter: put a meter on a block (M key or the Meter Tool)
 * and it gets a coloured outline with a letter, plus a row on the timeline HUD showing when
 * it was on and what happened to it each tick (event marks). Vanilla clients get a boss bar
 * per meter instead of the HUD.
 *
 * <p>Power changes are seen the moment they happen, so a pulse that turns on and off
 * inside one tick (0-tick pulse) still shows up. Nothing is sampled while the game is
 * frozen with {@code /tick freeze}; {@code /tick step} advances one tick at a time.</p>
 */
public final class MeterFeature {
	public static final String MARKER_CHANNEL = "meters";
	public static final String PROBE_KEY = "redstonelab_probe";
	private static final String LABELS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
	/** Most meters one player can have (one per letter). */
	public static final int MAX_METERS = LABELS.length();
	public static final int[] COLORS = colors();

	/** 8 hand-picked colours, then evenly spread hues for the rest. */
	private static int[] colors() {
		int[] base = {0xFFFF5555, 0xFF55AAFF, 0xFF55FF55, 0xFFFFFF55, 0xFFDD55FF, 0xFFFF88CC, 0xFFFFFFFF, 0xFFFFAA00};
		int[] colors = new int[MAX_METERS];
		for (int i = 0; i < colors.length; i++) {
			colors[i] = i < base.length ? base[i] : 0xFF000000 | java.awt.Color.HSBtoRGB(((i - base.length) * 0.618034f) % 1f, 0.55f, 1f);
		}
		return colors;
	}

	static final class Meter implements PowerWatch.Listener {
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
		final State owner;
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

		Meter(State owner, ResourceKey<Level> dimension, BlockPos pos, int index, int reading) {
			this.owner = owner;
			this.dimension = dimension;
			this.pos = pos;
			this.label = String.valueOf(LABELS.charAt(index));
			this.color = COLORS[index];
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
		final java.util.ArrayDeque<long[]> recent = new java.util.ArrayDeque<>();

		/** Notes the event for the "inspect a tick" list (consecutive repeats are skipped). */
		private void record(MeterEvent event) {
			if (this.order.size() < 32 && (this.order.isEmpty() || (this.order.get(this.order.size() - 1) & 31) != event.ordinal())) {
				int packed = nextSeq() << 8 | dev.redstonelab.core.TickPhase.current.ordinal() << 5 | event.ordinal();
				this.order.add(packed);
				if (this.recent.size() >= 64) {
					this.recent.removeFirst();
				}
				ServerLevel level = LabServer.level(this.dimension);
				this.recent.addLast(new long[]{level == null ? 0 : level.getGameTime(), packed});
			}
		}

		private void flag(MeterEvent event) {
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
				if (from.is(Blocks.MOVING_PISTON) || to.is(Blocks.MOVING_PISTON) || MOVING.contains(change.pos())) {
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
			this.rises[this.riseCount++ % this.rises.length] = time;
		}

		/** Recent rising edges (ring), for the order line. */
		final long[] rises = new long[8];
		int riseCount;

		/** One line for the HUD: "Observer · on 2t · off 4t", "Hopper · 12 items · signal 1". */
		String status() {
			StringBuilder sb = new StringBuilder(this.name.isEmpty() ? this.blockName : this.name + " \u00b7 " + this.blockName);
			if (this.comparator) {
				// Only reads the world (never changes the meter), so it can't disturb sampling.
				ServerLevel level = LabServer.level(this.dimension);
				String info = level == null ? "" : comparatorInfo(level, this.pos, this.power);
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
				if (level != null && loadedAround(level, this.pos) && PowerUtil.chestBlocked(level, this.pos)) {
					// Vanilla: a chest that can't open gives comparators 0.
					sb.append(" \u00b7 blocked");
				}
				appendRate(sb, "fires", this.fireGaps, this.lastFire);
				// A dropper firing is its items going out: say it once.
				boolean firing = !this.fireGaps.isEmpty() && this.recent(this.fireGaps, this.lastFire);
				boolean in = !this.inGaps.isEmpty() && this.recent(this.inGaps, this.lastIn);
				boolean out = !firing && !this.outGaps.isEmpty() && this.recent(this.outGaps, this.lastOut);
				String inText = Ticks.exact(this.inGaps.values());
				String outText = Ticks.exact(this.outGaps.values());
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
				if (this.wire && this.power > 0) {
					// Dust: the level matters (how far the signal still reaches).
					sb.append(" \u00b7 power ").append(this.power);
				}
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

		/**
		 * What the block's state says that the bars don't: repeater delay and lock, signal strength
		 * of target blocks / daylight detectors / sculk / weighted plates, a burnt-out torch, and
		 * for plain blocks strong or weak power (strong: dust next to it turns on too).
		 */
		private String blockInfo() {
			ServerLevel level = LabServer.level(this.dimension);
			if (level == null || !loadedAround(level, this.pos)) {
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
						|| dev.redstonelab.mixin.RedstoneTorchBlockInvoker.redstonelab$toggledTooOften(level, this.pos, false));
				return burnt ? "burnt out" : "";
			}
			if (this.power > 0 && state.isRedstoneConductor(level, this.pos) && !state.isSignalSource() && !state.hasBlockEntity()
					&& !state.hasProperty(BlockStateProperties.LIT) && !state.hasProperty(BlockStateProperties.POWERED)
					&& !state.hasProperty(BlockStateProperties.OPEN) && !(state.getBlock() instanceof PistonBaseBlock)) {
				// Strong: powered by anything but dust (dust ignores power that came from dust).
				boolean strong = false;
				for (Direction d : Direction.values()) {
					BlockPos n = this.pos.relative(d);
					BlockState ns = level.getBlockState(n);
					strong |= !(ns.getBlock() instanceof RedStoneWireBlock) && ns.getDirectSignal(level, n, d) > 0;
				}
				return (strong ? "strong " : "weak ") + this.power;
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
			sb.append(" \u00b7 ").append(what).append(" every ").append(Ticks.exact(gaps.values()));
		}

		private static String range(RunStats r) {
			return Ticks.exact(r.values());
		}


		/** Records the time of an event and the gap since the last one. */
		private long addGap(RunStats gaps, long last, long time) {
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
					return "last: " + MeterEvent.of(flags).get(0).displayName() + ", " + ago(age);
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
		net.minecraft.world.level.block.Block kindBlock = Blocks.AIR;

		/** Name and kind (piston, comparator, container...) of the block under the meter. */
		void readKind(ServerLevel level) {
			BlockState placed = level.getBlockState(this.pos);
			if (!placed.isAir() && !placed.is(Blocks.MOVING_PISTON)) {
				this.kindBlock = placed.getBlock();
			}
			this.blockName = Fmt.pretty(placed);
			this.piston = placed.getBlock() instanceof PistonBaseBlock;
			this.comparator = placed.getBlock() instanceof net.minecraft.world.level.block.ComparatorBlock;
			this.wire = placed.is(Blocks.REDSTONE_WIRE);
			this.fires = placed.hasProperty(BlockStateProperties.TRIGGERED);
			this.itemCount = itemCount(level, this.pos);
			this.nextLevel = this.itemCount > 0 ? nextLevel(level, this.pos, this.power) : "";
			this.contents = contents(level, this.pos);
			this.signalBased = this.itemCount >= 0 && !this.fires && !(placed.getBlock() instanceof net.minecraft.world.level.block.HopperBlock);
		}
	}

	static final class State {
		final List<Meter> meters = new ArrayList<>();
		/** Saved groups; the active one mirrors {@link #meters}. */
		MeterStore.Groups groups = new MeterStore.Groups();
		long lastSampleTime = Long.MIN_VALUE;
		boolean dirty = true;
		int statsCountdown;
		long lastClick = -1000;
		/** A piston moved a meter: save soon (not on every move, flying machines move a lot). */
		boolean moved;
	}

	private static final Map<UUID, State> STATES = new HashMap<>();

	private MeterFeature() {
	}

	private static State state(ServerPlayer player) {
		return STATES.computeIfAbsent(player.getUUID(), k -> new State());
	}

	private static String ago(int ticks) {
		return ticks == 0 ? "now" : ticks + "t ago";
	}

	// ------------------------------------------------------------------ meter tool

	public static ItemStack createTool() {
		Item item = Items.BLAZE_ROD;
		Identifier id = Identifier.tryParse(ServerConfig.get().probeItem);
		if (id != null && BuiltInRegistries.ITEM.containsKey(id)) {
			item = BuiltInRegistries.ITEM.getValue(id);
		}
		ItemStack stack = new ItemStack(item);
		CompoundTag tag = new CompoundTag();
		tag.putBoolean(PROBE_KEY, true);
		stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
		stack.set(DataComponents.CUSTOM_NAME, Component.literal("Meter Tool")
				.withStyle(s -> s.withItalic(false).withColor(ChatFormatting.GOLD)));
		stack.set(DataComponents.LORE, new ItemLore(List.of(
				lore("Right-click a block: add / remove a meter"),
				lore("Sneak + right-click: remove all meters"),
				lore("Tip: right-click a piston with an empty hand"))));
		return stack;
	}

	private static Component lore(String s) {
		return Component.literal(s).withStyle(st -> st.withItalic(false).withColor(ChatFormatting.GRAY));
	}

	public static boolean isTool(ItemStack stack) {
		if (stack.isEmpty()) {
			return false;
		}
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		return data != null && data.copyTag().contains(PROBE_KEY);
	}

	public static InteractionResult onUseBlock(Player player, Level level, InteractionHand hand, BlockHitResult hit) {
		if (!isTool(player.getItemInHand(hand))) {
			return InteractionResult.PASS;
		}
		if (!(player instanceof ServerPlayer serverPlayer) || !(level instanceof ServerLevel serverLevel)) {
			// Client side (modded client): swallow the click so the block is not used; the server handles it.
			return InteractionResult.SUCCESS;
		}
		if (hand != InteractionHand.MAIN_HAND) {
			return InteractionResult.SUCCESS;
		}
		if (!LabServer.canUse(serverPlayer)) {
			Msg.actionBar(serverPlayer, Component.literal("Meters not allowed here").withStyle(ChatFormatting.RED));
			return InteractionResult.FAIL;
		}
		BlockPos pos = hit.getBlockPos();
		// The click was cancelled; vanilla resends the clicked block, also resend the other half of doors etc.
		serverPlayer.connection.send(new ClientboundBlockUpdatePacket(serverLevel, pos.above()));
		serverPlayer.connection.send(new ClientboundBlockUpdatePacket(serverLevel, pos.below()));
		state(serverPlayer).lastClick = serverLevel.getGameTime();
		if (player.isShiftKeyDown()) {
			clear(serverPlayer);
		} else {
			toggle(serverPlayer, serverLevel, pos);
		}
		return InteractionResult.SUCCESS;
	}

	// ------------------------------------------------------------------ meters

	/** Adds a meter on the block, or removes it if there is one. */
	public static void toggle(ServerPlayer player, ServerLevel level, BlockPos pos) {
		State state = state(player);
		for (Meter m : new ArrayList<>(state.meters)) {
			if (m.dimension == level.dimension() && m.pos.equals(pos)) {
				remove(player, state, m);
				save(player, state);
				Msg.actionBar(player, Component.literal("Removed " + m.label).withStyle(ChatFormatting.GRAY));
				return;
			}
		}
		int max = Math.min(ServerConfig.get().maxMeters, MAX_METERS);
		if (state.meters.size() >= max) {
			Msg.actionBar(player, Component.literal("Max " + max + " meters").withStyle(ChatFormatting.RED));
			return;
		}
		Meter m = addMeter(state, level, pos);
		save(player, state);
		Msg.actionBar(player, Msg.colored(m.label + ": " + m.name(level), m.color)
				.append(Component.literal("  " + m.onOff()).withStyle(ChatFormatting.WHITE)));
	}

	/** Removes the meter on {@code pos}, or the newest meter when {@code pos} is null. */
	public static void removeOne(ServerPlayer player, @org.jetbrains.annotations.Nullable BlockPos pos) {
		State state = STATES.get(player.getUUID());
		Meter target = null;
		if (state != null && !state.meters.isEmpty()) {
			if (pos == null) {
				target = state.meters.get(state.meters.size() - 1);
			} else {
				for (Meter m : state.meters) {
					if (m.dimension == player.level().dimension() && m.pos.equals(pos)) {
						target = m;
					}
				}
			}
		}
		if (target == null) {
			Msg.actionBar(player, Component.literal(pos == null ? "No meters" : "No meter there").withStyle(ChatFormatting.GRAY));
			return;
		}
		remove(player, state, target);
		save(player, state);
		Msg.actionBar(player, Component.literal("Removed " + target.label).withStyle(ChatFormatting.GRAY));
	}

	public static void clear(ServerPlayer player) {
		State state = STATES.get(player.getUUID());
		int n = state == null ? 0 : state.meters.size();
		if (state != null) {
			for (Meter m : new ArrayList<>(state.meters)) {
				remove(player, state, m);
			}
			save(player, state);
		}
		Msg.actionBar(player, Component.literal(n > 0 ? "Removed " + n + " meters" : "No meters").withStyle(ChatFormatting.GRAY));
	}

	private static Meter addMeter(State state, ServerLevel level, BlockPos pos) {
		Meter m = new Meter(state, level.dimension(), pos.immutable(), freeIndex(state), PowerUtil.read(level, pos, level.getBlockState(pos)));
		m.readKind(level);
		if (!m.active && !m.powered) {
			m.offUnpoweredSince = level.getGameTime();
		}
		state.meters.add(m);
		PowerWatch.add(level, pos, m);
		state.dirty = true;
		return m;
	}

	/**
	 * "3 at 83": the next comparator level and the item count that gives it. Only when that is
	 * exact: the signal is the usual fullness one (not a crafter, bookshelf, jukebox, shelf or
	 * blocked chest) and every stack has the same stack size (64, 16 or 1), so items count alike.
	 */
	private static String nextLevel(ServerLevel level, BlockPos pos, int signal) {
		net.minecraft.world.Container container = loadedAround(level, pos) ? PowerUtil.container(level, pos) : null;
		if (container == null || signal != net.minecraft.world.inventory.AbstractContainerMenu.getRedstoneSignalFromContainer(container)
				|| level.getBlockState(pos).getBlock() instanceof net.minecraft.world.level.block.CrafterBlock
				|| level.getBlockState(pos).getBlock() instanceof net.minecraft.world.level.block.ChiseledBookShelfBlock
				|| level.getBlockState(pos).getBlock() instanceof net.minecraft.world.level.block.JukeboxBlock
				|| level.getBlockState(pos).getBlock() instanceof net.minecraft.world.level.block.ShelfBlock) {
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

	/**
	 * Comparators: "subtract · back 15 · side 14 · out 1", inputs read the same way the game
	 * does. Empty for other blocks.
	 */
	static String comparatorInfo(ServerLevel level, BlockPos pos, int out) {
		// The inputs are read up to 2 blocks away: don't make the game load chunks for that.
		if (!level.hasChunkAt(pos.offset(-2, 0, -2)) || !level.hasChunkAt(pos.offset(2, 0, 2))
				|| !level.hasChunkAt(pos.offset(-2, 0, 2)) || !level.hasChunkAt(pos.offset(2, 0, -2))) {
			return "";
		}
		BlockState state = level.getBlockState(pos);
		if (!(state.getBlock() instanceof net.minecraft.world.level.block.ComparatorBlock)) {
			return "";
		}
		dev.redstonelab.mixin.DiodeBlockInvoker diode = (dev.redstonelab.mixin.DiodeBlockInvoker) state.getBlock();
		int back = diode.redstonelab$backInput(level, pos, state);
		int side = diode.redstonelab$sideInput(level, pos, state);
		boolean subtract = state.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.MODE_COMPARATOR)
				== net.minecraft.world.level.block.state.properties.ComparatorMode.SUBTRACT;
		// The output follows the inputs 2 ticks later, so show the three values as they are now.
		return (subtract ? "subtract" : "compare") + " \u00b7 back " + back + " \u00b7 side " + side + " \u00b7 out " + out;
	}

	/**
	 * What is inside: "10 Stone", "10 Stone + 3 Dirt", or "20 items, 4 kinds" (empty string
	 * when empty or not a container).
	 */
	static String contents(ServerLevel level, BlockPos pos) {
		net.minecraft.world.Container container = loadedAround(level, pos) ? PowerUtil.container(level, pos) : null;
		if (container == null) {
			return "";
		}
		Map<Item, Integer> kinds = new java.util.LinkedHashMap<>();
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

	/**
	 * A metered block entity changed (a hopper, dropper or chest gained or lost items): note
	 * item in / out right away, so the tick list shows it in the part of the tick it happened
	 * (dropper firing: block ticks; hoppers: block entities). Several changes in one tick are
	 * all noted (a hopper taking an item in and passing one on).
	 */
	public static void onContainerChanged(ServerLevel level, BlockPos pos) {
		for (State state : STATES.values()) {
			for (Meter m : state.meters) {
				if (m.itemCount < 0 || m.dimension != level.dimension() || !m.pos.equals(pos)) {
					continue;
				}
				int items = itemCount(level, pos);
				if (items >= 0 && items != m.itemCount) {
					m.flag(items > m.itemCount ? MeterEvent.ITEM_IN : MeterEvent.ITEM_OUT);
					m.itemCount = items;
					m.itemsChanged = true;
				}
			}
		}
	}

	/** The block and everything next to it is loaded: reading it never makes the game load a chunk. */
	private static boolean loadedAround(ServerLevel level, BlockPos pos) {
		return level.hasChunksAt(pos.offset(-1, 0, -1), pos.offset(1, 0, 1));
	}

	/** Items inside a hopper, chest (both halves), dropper..., or -1 if the block is not a container. */
	private static int itemCount(ServerLevel level, BlockPos pos) {
		net.minecraft.world.Container container = loadedAround(level, pos) ? PowerUtil.container(level, pos) : null;
		if (container == null) {
			return -1;
		}
		int items = 0;
		for (int i = 0; i < container.getContainerSize(); i++) {
			items += container.getItem(i).getCount();
		}
		return items;
	}

	// ------------------------------------------------------------------ groups

	/** Copies the current meters into the active group (in memory only). */
	private static void snapshot(State state) {
		List<MeterStore.Saved> list = new ArrayList<>();
		for (Meter m : state.meters) {
			list.add(new MeterStore.Saved(m.dimension.identifier().toString(), m.pos, m.expectOn, m.expectOff, m.name));
		}
		state.groups.groups.put(state.groups.active, list);
	}

	private static void save(ServerPlayer player, State state) {
		snapshot(state);
		MinecraftServer server = LabServer.server();
		if (server != null) {
			MeterStore.save(server, player.getUUID(), state.groups);
		}
	}

	/** Places the meters of the active group. */
	private static void restore(MinecraftServer server, State state) {
		int max = Math.min(ServerConfig.get().maxMeters, MAX_METERS);
		for (MeterStore.Saved saved : state.groups.groups.getOrDefault(state.groups.active, List.of())) {
			if (state.meters.size() >= max) {
				break;
			}
			for (ServerLevel level : server.getAllLevels()) {
				if (level.dimension().identifier().toString().equals(saved.dimension())) {
					Meter m = addMeter(state, level, saved.pos());
					m.expectOn = saved.expectOn();
					m.expectOff = saved.expectOff();
					m.name = saved.name();
				}
			}
		}
	}

	/** Loads the player's saved groups when they join. */
	public static void onJoin(ServerPlayer player) {
		MinecraftServer server = LabServer.server();
		if (server == null) {
			return;
		}
		State state = state(player);
		if (!state.meters.isEmpty()) {
			return;
		}
		state.groups = MeterStore.load(server, player.getUUID());
		restore(server, state);
	}

	/** Saves the current meters and switches to another group (made if it doesn't exist). */
	public static void switchGroup(ServerPlayer player, String name) {
		MinecraftServer server = LabServer.server();
		if (server == null) {
			return;
		}
		State state = state(player);
		save(player, state);
		for (Meter m : new ArrayList<>(state.meters)) {
			remove(player, state, m);
		}
		boolean isNew = !state.groups.groups.containsKey(name);
		state.groups.active = name;
		state.groups.groups.putIfAbsent(name, new ArrayList<>());
		restore(server, state);
		save(player, state);
		Msg.send(player, Component.literal(isNew ? "New meter group \"" + name + "\" (empty)" : "Switched to meter group \"" + name + "\" ("
				+ state.meters.size() + " meters)").withStyle(ChatFormatting.GOLD));
	}

	public static void deleteGroup(ServerPlayer player, String name) {
		State state = state(player);
		if (name.equals(state.groups.active)) {
			Msg.send(player, Component.literal("Group in use: switch first").withStyle(ChatFormatting.RED));
			return;
		}
		if (state.groups.groups.remove(name) == null) {
			Msg.send(player, Component.literal("No group called \"" + name + "\"").withStyle(ChatFormatting.RED));
			return;
		}
		save(player, state);
		Msg.send(player, Component.literal("Deleted \"" + name + "\"").withStyle(ChatFormatting.GRAY));
	}

	/** Chat list of the groups; click one to switch. */
	public static void listGroups(ServerPlayer player) {
		State state = state(player);
		snapshot(state);
		Msg.send(player, Component.literal("Groups (click to switch, /rlab group <name> = new):").withStyle(ChatFormatting.GOLD));
		for (Map.Entry<String, List<MeterStore.Saved>> g : state.groups.groups.entrySet()) {
			boolean active = g.getKey().equals(state.groups.active);
			MutableComponent line = Component.literal(active ? " \u25b6 " : "   ")
					.append(active ? Component.literal(g.getKey()).withStyle(ChatFormatting.GREEN)
							: Msg.button(g.getKey(), "/rlab group " + g.getKey(), "Switch to this group"))
					.append(Component.literal("  " + g.getValue().size() + " meters").withStyle(ChatFormatting.GRAY));
			Msg.sendRaw(player, line);
		}
	}

	/** Gives another (online) player a copy of one of your groups. */
	public static void shareGroup(ServerPlayer player, String name, ServerPlayer target) {
		State state = state(player);
		snapshot(state);
		List<MeterStore.Saved> list = state.groups.groups.get(name);
		if (list == null) {
			Msg.send(player, Component.literal("No group called \"" + name + "\"").withStyle(ChatFormatting.RED));
			return;
		}
		if (target == player) {
			Msg.send(player, Component.literal("That's you!").withStyle(ChatFormatting.RED));
			return;
		}
		State other = state(target);
		String copyName = name;
		for (int i = 2; other.groups.groups.containsKey(copyName); i++) {
			copyName = name + "-" + i;
		}
		other.groups.groups.put(copyName, new ArrayList<>(list));
		save(target, other);
		Msg.send(player, Component.literal("Sent \"" + name + "\" to " + target.getName().getString()).withStyle(ChatFormatting.GREEN));
		Msg.send(target, Component.literal(player.getName().getString() + " gave you the meter group \"" + copyName + "\" ("
				+ list.size() + " meters) ").withStyle(ChatFormatting.GOLD)
				.append(Msg.button("use it", "/rlab group " + copyName, "Switch to this group")));
	}

	public static List<String> groupNames(ServerPlayer player) {
		return new ArrayList<>(state(player).groups.groups.keySet());
	}

	private static void remove(ServerPlayer player, State state, Meter m) {
		state.meters.remove(m);
		PowerWatch.remove(m.dimension, m.pos, m);
		m.bossBar.hide(player);
		state.dirty = true;
	}

	private static int freeIndex(State state) {
		for (int i = 0; i < MAX_METERS; i++) {
			boolean used = false;
			for (Meter m : state.meters) {
				if (m.color == COLORS[i]) {
					used = true;
					break;
				}
			}
			if (!used) {
				return i;
			}
		}
		return 0;
	}

	/** Chat view: on/off history of the last 40 ticks, pulse lengths and recent events. */
	public static void list(ServerPlayer player) {
		State state = STATES.get(player.getUUID());
		if (state == null || state.meters.isEmpty()) {
			Msg.send(player, Component.literal("No meters. Look at a block and press M (or /rlab tool)"));
			return;
		}
		Msg.send(player, Component.literal("Meters (last 40t, newest right)").withStyle(ChatFormatting.GOLD));
		String order = orderLine(state);
		if (!order.isEmpty()) {
			Msg.sendRaw(player, Component.literal(" Order: " + order).withStyle(ChatFormatting.YELLOW));
		}
		for (Meter m : state.meters) {
			Msg.sendRaw(player, Component.empty()
					.append(Msg.colored(m.label + " ", m.color))
					.append(Msg.pos(m.pos)).append(" ")
					.append(Msg.colored(Sparkline.render(m.history, 40), m.color))
					.append(Component.literal(" " + m.onOff()).withStyle(m.active ? ChatFormatting.RED : ChatFormatting.GRAY)));
			MutableComponent info = Component.literal("   " + m.status()).withStyle(ChatFormatting.GRAY);
			String last = m.lastEvent(40);
			if (last != null) {
				info.append(Component.literal("  · " + last).withStyle(ChatFormatting.DARK_AQUA));
			}
			Msg.sendRaw(player, info);
		}
	}

	public static int count(ServerPlayer player) {
		State state = STATES.get(player.getUUID());
		return state == null ? 0 : state.meters.size();
	}

	// ------------------------------------------------------------------ tick

	public static void tick(MinecraftServer server) {
		for (Map.Entry<UUID, State> e : STATES.entrySet()) {
			ServerPlayer player = server.getPlayerList().getPlayer(e.getKey());
			if (player == null) {
				continue;
			}
			State state = e.getValue();
			if (state.meters.isEmpty()) {
				if (state.dirty) {
					state.dirty = false;
					sendLayout(player, state);
					MarkerService.clear(player, MARKER_CHANNEL);
				}
				continue;
			}
			ServerLevel level = server.getLevel(state.meters.get(0).dimension);
			long time = level != null ? level.getGameTime() : server.overworld().getGameTime();
			boolean advanced = time != state.lastSampleTime;
			if (advanced) {
				state.lastSampleTime = time;
				sample(player, state, time);
			}
			if (state.dirty) {
				showOutlines(player, state);
			}
			if (state.dirty || --state.statsCountdown <= 0) {
				state.statsCountdown = 10;
				state.dirty = false;
				sendLayout(player, state);
			}
			if (state.moved && server.getTickCount() % 100 == 0) {
				state.moved = false;
				save(player, state);
			}
			if (Sessions.get(player).rendersHud()) {
				// the client mod draws the timeline; drop bars shown before its handshake arrived
				for (Meter m : state.meters) {
					m.bossBar.hide(player);
				}
			} else if (advanced && server.getTickCount() % 2 == 0) {
				updateBossBars(player, state);
			}
		}
		if (server.getTickCount() % 20 == 0) {
			showToolHint(server);
		}
		// Every meter sent its events: start counting again (see nextSeq).
		boolean pending = false;
		for (State state : STATES.values()) {
			for (Meter m : state.meters) {
				pending |= !m.order.isEmpty();
			}
		}
		if (!pending) {
			seq = 0;
		}
	}

	/** Blocks a piston is moving right now: them turning into air is a move, not "broken". */
	private static final java.util.Set<BlockPos> MOVING = new java.util.HashSet<>();

	public static void beginMove(List<BlockPos> blocks) {
		MOVING.addAll(blocks);
	}

	/**
	 * A piston moved these blocks one step in {@code direction}: meters on them go along (like
	 * Redstone Multimeter). Runs right after the piston started moving them.
	 */
	public static void onBlocksMoved(ServerLevel level, List<BlockPos> moved, net.minecraft.core.Direction direction) {
		MOVING.clear();
		java.util.Set<BlockPos> from = new java.util.HashSet<>(moved);
		java.util.Set<BlockPos> to = new java.util.HashSet<>();
		for (BlockPos p : moved) {
			to.add(p.relative(direction));
		}
		MinecraftServer server = LabServer.server();
		for (Map.Entry<UUID, State> e : STATES.entrySet()) {
			State state = e.getValue();
			List<Meter> moving = new ArrayList<>();
			for (Meter m : state.meters) {
				if (m.dimension == level.dimension() && from.contains(m.pos)) {
					moving.add(m);
				}
			}
			if (moving.isEmpty()) {
				continue;
			}
			// A meter left on a block the move destroyed would end up on the same spot: drop it.
			ServerPlayer player = server == null ? null : server.getPlayerList().getPlayer(e.getKey());
			for (Meter m : new ArrayList<>(state.meters)) {
				if (m.dimension == level.dimension() && to.contains(m.pos) && !from.contains(m.pos) && player != null) {
					remove(player, state, m);
				}
			}
			for (Meter m : moving) {
				PowerWatch.remove(m.dimension, m.pos, m);
			}
			for (Meter m : moving) {
				m.pos = m.pos.relative(direction);
				PowerWatch.add(level, m.pos, m);
				int reading = PowerWatch.readingAt(m.dimension, m.pos);
				if (reading >= 0) {
					m.apply(level.getGameTime(), reading);
				}
			}
			state.dirty = true;
			state.moved = true;
		}
	}

	private static void sample(ServerPlayer player, State state, long time) {
		int n = state.meters.size();
		byte[] end = new byte[n];
		byte[] bits = new byte[n];
		int[] events = new int[n];
		int[][] order = new int[n][];
		for (int i = 0; i < n; i++) {
			Meter m = state.meters.get(i);
			end[i] = (byte) m.power;
			// End-of-tick state (bits 1, 2) + what turned on during the tick (bits 4, 8).
			bits[i] = (byte) (m.tickState | m.stateBits());
			// Item meters: item in / out is normally noted the moment it happens
			// (onContainerChanged); this only catches changes the game didn't announce.
			ServerLevel meterLevel = LabServer.level(m.dimension);
			int items = meterLevel == null ? -1 : itemCount(meterLevel, m.pos);
			if (items >= 0 && m.itemCount >= 0 && items != m.itemCount) {
				m.flag(items > m.itemCount ? MeterEvent.ITEM_IN : MeterEvent.ITEM_OUT);
			}
			if (items >= 0 && (items != m.itemCount || m.itemsChanged || m.nextLevel.isEmpty() || time % 20 == 0)) {
				m.nextLevel = items > 0 ? nextLevel(meterLevel, m.pos, m.power) : "";
				m.contents = contents(meterLevel, m.pos);
			}
			m.itemCount = items;
			m.itemsChanged = false;
			m.now = time;
			if (items >= 0) {
				if (MeterEvent.ITEM_IN.in(m.events)) {
					m.lastIn = m.addGap(m.inGaps, m.lastIn, time);
				}
				if (MeterEvent.ITEM_OUT.in(m.events)) {
					m.lastOut = m.addGap(m.outGaps, m.lastOut, time);
				}
				// A scheduled tick on a dispenser / dropper is it firing.
				if (MeterEvent.SCHEDULED_TICK.in(m.events)) {
					m.lastFire = m.addGap(m.fireGaps, m.lastFire, time);
				}
			}
			events[i] = m.events;
			if (m.kindChanged && meterLevel != null && meterLevel.hasChunkAt(m.pos)) {
				m.kindChanged = false;
				m.readKind(meterLevel);
				m.extendGaps.clear();
				m.outLengths.clear();
				m.inGaps.clear();
				m.outGaps.clear();
				m.fireGaps.clear();
				state.dirty = true;
			}
			if ((m.events & (MeterEvent.MOVED.bit() | MeterEvent.PLACED.bit() | MeterEvent.BROKEN.bit())) != 0) {
				ServerLevel level = player.level().dimension() == m.dimension ? player.level() : null;
				BlockState now = level != null ? level.getBlockState(m.pos) : null;
				// Keep the last real name while a block is being moved or is gone for a moment.
				if (now != null && !now.isAir() && !now.is(Blocks.MOVING_PISTON) && !m.blockName.equals(m.name(level))) {
					m.blockName = m.name(level);
					state.dirty = true;
				}
			}
			// History for chat / boss bars: active ticks with their level (a 0-tick pulse shows its peak).
			boolean wasActive = m.active || (m.tickState & 4) != 0;
			m.push(wasActive ? Math.max(1, m.tickMax) : 0, m.events);
			m.tickMin = m.power;
			m.tickMax = m.power;
			m.tickState = 0;
			m.events = 0;
			order[i] = m.order.stream().mapToInt(Integer::intValue).toArray();
			m.order.clear();
			if (m.alert != null) {
				Msg.actionBar(player, Component.literal("\u2716 " + m.alert).withStyle(ChatFormatting.RED));
				m.alert = null;
				state.statsCountdown = 0;
			}
		}
		if (Sessions.get(player).rendersHud()) {
			LabNetworking.send(player, new LabPayloads.ScopeSampleS2C(time, end, bits, events, order));
		}
	}

	/**
	 * Sequence numbers for the "inspect a tick" list. They keep counting until every meter has
	 * sent its events, so things a player did between two ticks sort before the next tick.
	 */
	private static int seq;

	private static int nextSeq() {
		return Math.min(++seq, 1 << 20);
	}

	/**
	 * Sets the expected on / off length of a meter (by letter). Without numbers it takes the
	 * meter's latest pulse as the expected one. Counting starts over.
	 */
	public static void expect(ServerPlayer player, String label, @org.jetbrains.annotations.Nullable Integer on,
	                          @org.jetbrains.annotations.Nullable Integer off, boolean clear) {
		State state = STATES.get(player.getUUID());
		Meter meter = null;
		if (state != null) {
			for (Meter m : state.meters) {
				if (m.label.equalsIgnoreCase(label)) {
					meter = m;
				}
			}
		}
		if (meter == null) {
			Msg.send(player, Component.literal("No meter " + label).withStyle(ChatFormatting.RED));
			return;
		}
		if (clear) {
			meter.expectOn = -1;
			meter.expectOff = -1;
		} else if (on == null) {
			if (meter.pulses.widths().isEmpty()) {
				Msg.send(player, Component.literal(meter.label + " has no pulse yet. Run it first, or give the numbers: /rlab meter expect "
						+ meter.label + " <on> [off]").withStyle(ChatFormatting.RED));
				return;
			}
			meter.expectOn = (int) meter.pulses.widths().last();
			meter.expectOff = meter.pulses.gaps().isEmpty() ? -1 : (int) meter.pulses.gaps().last();
		} else {
			meter.expectOn = on;
			meter.expectOff = off == null ? -1 : off;
		}
		meter.okCount = 0;
		meter.wrongCount = 0;
		meter.lastWrong = "";
		state.dirty = true;
		save(player, state);
		Msg.send(player, Component.literal(clear ? meter.label + ": not checked anymore"
				: meter.label + ": expect on " + meter.expectOn + "t" + (meter.expectOff >= 0 ? ", off " + meter.expectOff + "t" : "")
				+ " (wrong = red)").withStyle(ChatFormatting.GOLD));
	}

	/** Gives a meter (by letter) a name shown in front of its text; an empty name removes it. */
	public static void rename(ServerPlayer player, String label, String name) {
		State state = STATES.get(player.getUUID());
		Meter meter = null;
		if (state != null) {
			for (Meter m : state.meters) {
				if (m.label.equalsIgnoreCase(label)) {
					meter = m;
				}
			}
		}
		if (meter == null) {
			Msg.send(player, Component.literal("No meter " + label).withStyle(ChatFormatting.RED));
			return;
		}
		meter.name = name.length() > 24 ? name.substring(0, 24) : name;
		state.dirty = true;
		save(player, state);
		Msg.actionBar(player, Msg.colored(meter.label + (meter.name.isEmpty() ? ": name removed" : " is now \"" + meter.name + "\""), meter.color));
	}

	/** For the game tests: the HUD text of one meter. */
	static String statusForTest(ServerPlayer player, String label) {
		State state = STATES.get(player.getUUID());
		if (state != null) {
			for (Meter m : state.meters) {
				if (m.label.equals(label)) {
					return m.status();
				}
			}
		}
		return "";
	}

	private static Meter meterForTest(ServerPlayer player, String label) {
		State state = STATES.get(player.getUUID());
		if (state != null) {
			for (Meter m : state.meters) {
				if (m.label.equals(label)) {
					return m;
				}
			}
		}
		throw new IllegalArgumentException("no meter " + label);
	}

	/** For the game tests: the meter's boss bar (players without the client mod) is up. */
	static boolean bossBarForTest(ServerPlayer player, String label) {
		return meterForTest(player, label).bossBar.isShown();
	}

	/** For the game tests: the remembered turn-on times. */
	static String risesForTest(ServerPlayer player, String label) {
		Meter m = meterForTest(player, label);
		return java.util.Arrays.toString(m.rises) + " count " + m.riseCount;
	}

	/** For the game tests: the pulse lengths text ("on 2t · off 4t"). */
	static String pulsesForTest(ServerPlayer player, String label) {
		return meterForTest(player, label).pulses.summary();
	}

	/** For the game tests: the meter's current reading (see {@link Reading}). */
	static int readingForTest(ServerPlayer player, String label) {
		Meter m = meterForTest(player, label);
		return Reading.of(m.power, m.active, m.powered);
	}

	static BlockPos posForTest(ServerPlayer player, String label) {
		return meterForTest(player, label).pos;
	}

	/** For the game tests: recent events as "TURNED_ON@BLOCK_TICKS", oldest first. */
	static List<String> eventsForTest(ServerPlayer player, String label) {
		List<String> list = new ArrayList<>();
		for (long[] e : meterForTest(player, label).recent) {
			int packed = (int) e[1];
			list.add(MeterEvent.values()[packed & 31] + "@" + dev.redstonelab.core.TickPhase.byOrdinal((packed >>> 5) & 7));
		}
		return list;
	}

	/** For the game tests: sequence number (order within the tick) of the newest {@code event}, or -1. */
	static int seqForTest(ServerPlayer player, String label, MeterEvent event) {
		int seqNo = -1;
		for (long[] e : meterForTest(player, label).recent) {
			if (((int) e[1] & 31) == event.ordinal()) {
				seqNo = (int) e[1] >>> 8;
			}
		}
		return seqNo;
	}

	/** For the game tests: game time of the newest {@code event} at the meter, or -1. */
	static long eventTimeForTest(ServerPlayer player, String label, MeterEvent event) {
		long time = -1;
		for (long[] e : meterForTest(player, label).recent) {
			if (((int) e[1] & 31) == event.ordinal()) {
				time = e[0];
			}
		}
		return time;
	}

	/** For the game tests: the order line at game time {@code now}. */
	static String orderForTest(ServerPlayer player, long now) {
		State state = STATES.get(player.getUUID());
		return state == null ? "" : orderLine(state, now);
	}

	public static List<String> labels(ServerPlayer player) {
		State state = STATES.get(player.getUUID());
		List<String> list = new ArrayList<>();
		if (state != null) {
			for (Meter m : state.meters) {
				list.add(m.label);
			}
		}
		return list;
	}

	private static void sendLayout(ServerPlayer player, State state) {
		if (!Sessions.get(player).rendersHud()) {
			return;
		}
		List<LabPayloads.ScopeChannel> channels = new ArrayList<>();
		List<String> stats = new ArrayList<>();
		for (Meter m : state.meters) {
			channels.add(new LabPayloads.ScopeChannel(m.pos, m.label, m.color, m.signalBased));
			stats.add(m.status());
		}
		LabNetworking.send(player, new LabPayloads.ScopeConfigS2C(channels, stats, ServerConfig.get().meterHistoryTicks, state.groups.active,
				orderLine(state)));
	}

	/**
	 * "A C → 2t → B D → 2t → E F": the order the meters turn on in, measured from the first
	 * meter (put A at the start of your circuit). Same tick = same group.
	 */
	static String orderLine(State state) {
		long now = Long.MIN_VALUE;
		for (Meter m : state.meters) {
			now = Math.max(now, m.now);
		}
		return orderLine(state, now);
	}

	static String orderLine(State state, long now) {
		List<dev.redstonelab.core.OrderLine.Input> inputs = new ArrayList<>();
		for (Meter m : state.meters) {
			long[] rises = new long[m.rises.length];
			java.util.Arrays.fill(rises, Long.MIN_VALUE);
			System.arraycopy(m.rises, 0, rises, 0, Math.min(m.riseCount, m.rises.length));
			inputs.add(new dev.redstonelab.core.OrderLine.Input(m.label, rises));
		}
		return dev.redstonelab.core.OrderLine.compute(inputs, now);
	}


	private static void updateBossBars(ServerPlayer player, State state) {
		for (Meter m : state.meters) {
			MutableComponent name = Component.empty()
					.append(Msg.colored(m.label + " ", m.color))
					.append(Component.literal(Sparkline.render(m.history, 24) + " ").withStyle(ChatFormatting.WHITE))
					.append(Component.literal(m.onOff() + "  ").withStyle(m.active ? ChatFormatting.RED : ChatFormatting.GRAY))
					.append(Component.literal(m.pulses.summary()).withStyle(ChatFormatting.GRAY));
			String last = m.lastEvent(20);
			if (last != null) {
				name.append(Component.literal("  · " + last).withStyle(ChatFormatting.DARK_AQUA));
			}
			m.bossBar.update(player, name, m.active ? Math.max(1, m.power) / 15f : 0f);
		}
	}

	/** Coloured box + letter around every meter. */
	private static void showOutlines(ServerPlayer player, State state) {
		List<LabPayloads.MarkerData> markers = new ArrayList<>();
		for (Meter m : state.meters) {
			if (m.dimension == player.level().dimension()) {
				markers.add(new LabPayloads.MarkerData(m.pos, 1.15f, m.label, m.color,
						(byte) (LabPayloads.STYLE_LABEL | LabPayloads.STYLE_OUTLINE)));
			}
		}
		MarkerService.set(player, MARKER_CHANNEL, markers);
	}

	/** Holding the tool: show what it does (unless the player just clicked). */
	private static void showToolHint(MinecraftServer server) {
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (!isTool(player.getMainHandItem())) {
				continue;
			}
			State state = STATES.get(player.getUUID());
			if (state != null && player.level().getGameTime() - state.lastClick < 60) {
				continue;
			}
			Msg.actionBar(player, Component.literal("Right-click: meter on/off  •  Sneak: remove all")
					.withStyle(ChatFormatting.GRAY));
		}
	}

	/** The client lost its entities or HUD state (respawn, dimension change): send everything again. */
	public static void onClientReset(ServerPlayer player) {
		State state = STATES.get(player.getUUID());
		if (state != null) {
			state.dirty = true;
		}
	}

	public static void onLeave(ServerPlayer player) {
		State state = STATES.remove(player.getUUID());
		if (state != null) {
			save(player, state);
			for (Meter m : state.meters) {
				PowerWatch.remove(m.dimension, m.pos, m);
				m.bossBar.hide(player);
			}
		}
	}
}
