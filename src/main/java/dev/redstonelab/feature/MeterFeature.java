package dev.redstonelab.feature;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.resources.Identifier;
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
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

import dev.redstonelab.capture.PowerWatch;
import dev.redstonelab.config.ServerConfig;
import dev.redstonelab.core.MeterEvent;
import dev.redstonelab.core.OrderLine;
import dev.redstonelab.core.Reading;
import dev.redstonelab.core.Sparkline;
import dev.redstonelab.core.TickPhase;
import dev.redstonelab.display.MarkerService;
import dev.redstonelab.net.LabNetworking;
import dev.redstonelab.net.LabPayloads;
import dev.redstonelab.server.LabServer;
import dev.redstonelab.server.Sessions;
import dev.redstonelab.util.Msg;
import dev.redstonelab.util.PowerUtil;

/**
 * Meters, like Redstone Multimeter: M on a block (or the Meter Tool) gives it an outline with a letter and a row
 * on the timeline. Vanilla clients get a boss bar per meter instead.
 *
 * Power changes are seen as they happen, so a pulse that starts and ends inside one tick still shows.
 */
public final class MeterFeature {
	public static final String MARKER_CHANNEL = "meters";
	public static final String PROBE_KEY = "redstonelab_probe";
	static final String LABELS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
	/** Most meters one player can have (one per letter). */
	public static final int MAX_METERS = LABELS.length();
	public static final int[] COLORS = colors();

	/** 8 hand-picked colours, then evenly spread hues for the rest. */
	private static int[] colors() {
		int[] base = {0xFFFF5555, 0xFF55AAFF, 0xFF55FF55, 0xFFFFFF55, 0xFFDD55FF, 0xFFFF88CC, 0xFFFFFFFF, 0xFFFFAA00};
		int[] colors = new int[MAX_METERS];
		for (int i = 0; i < colors.length; i++) {
			colors[i] = i < base.length ? base[i] : 0xFF000000 | Color.HSBtoRGB(((i - base.length) * 0.618034f) % 1f, 0.55f, 1f);
		}
		return colors;
	}

	private static final Map<UUID, MeterState> STATES = new HashMap<>();

	private MeterFeature() {
	}

	private static MeterState state(ServerPlayer player) {
		return STATES.computeIfAbsent(player.getUUID(), k -> new MeterState());
	}

	static String ago(int ticks) {
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
		MeterState state = state(player);
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
	public static void removeOne(ServerPlayer player, @Nullable BlockPos pos) {
		MeterState state = STATES.get(player.getUUID());
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
		MeterState state = STATES.get(player.getUUID());
		int n = state == null ? 0 : state.meters.size();
		if (state != null) {
			for (Meter m : new ArrayList<>(state.meters)) {
				remove(player, state, m);
			}
			save(player, state);
		}
		Msg.actionBar(player, Component.literal(n > 0 ? "Removed " + n + " meters" : "No meters").withStyle(ChatFormatting.GRAY));
	}

	private static Meter addMeter(MeterState state, ServerLevel level, BlockPos pos) {
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
	 * A metered block entity gained or lost items: note item in / out right away, so the tick list shows the part of
	 * the tick it happened in (dropper: block ticks, hopper: block entities). All changes in one tick are noted.
	 */
	public static void onContainerChanged(ServerLevel level, BlockPos pos) {
		for (MeterState state : STATES.values()) {
			for (Meter m : state.meters) {
				if (m.itemCount < 0 || m.dimension != level.dimension() || !m.pos.equals(pos)) {
					continue;
				}
				int items = ContainerInfo.itemCount(level, pos);
				if (items >= 0 && items != m.itemCount) {
					m.flag(items > m.itemCount ? MeterEvent.ITEM_IN : MeterEvent.ITEM_OUT);
					m.itemCount = items;
					m.itemsChanged = true;
				}
			}
		}
	}

	// ------------------------------------------------------------------ groups

	/** Copies the current meters into the active group (in memory only). */
	private static void snapshot(MeterState state) {
		List<MeterStore.Saved> list = new ArrayList<>();
		for (Meter m : state.meters) {
			list.add(new MeterStore.Saved(m.dimension.identifier().toString(), m.pos, m.expectOn, m.expectOff, m.name));
		}
		state.groups.groups.put(state.groups.active, list);
	}

	private static void save(ServerPlayer player, MeterState state) {
		snapshot(state);
		MinecraftServer server = LabServer.server();
		if (server != null) {
			MeterStore.save(server, player.getUUID(), state.groups);
		}
	}

	/** Places the meters of the active group. */
	private static void restore(MinecraftServer server, MeterState state) {
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
		MeterState state = state(player);
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
		MeterState state = state(player);
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
		MeterState state = state(player);
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
		MeterState state = state(player);
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
		MeterState state = state(player);
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
		MeterState other = state(target);
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

	private static void remove(ServerPlayer player, MeterState state, Meter m) {
		state.meters.remove(m);
		PowerWatch.remove(m.dimension, m.pos, m);
		m.bossBar.hide(player);
		state.dirty = true;
	}

	private static int freeIndex(MeterState state) {
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
		MeterState state = STATES.get(player.getUUID());
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
		MeterState state = STATES.get(player.getUUID());
		return state == null ? 0 : state.meters.size();
	}

	// ------------------------------------------------------------------ tick

	public static void tick(MinecraftServer server) {
		for (Map.Entry<UUID, MeterState> e : STATES.entrySet()) {
			ServerPlayer player = server.getPlayerList().getPlayer(e.getKey());
			if (player == null) {
				continue;
			}
			MeterState state = e.getValue();
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
		for (MeterState state : STATES.values()) {
			for (Meter m : state.meters) {
				pending |= !m.order.isEmpty();
			}
		}
		if (!pending) {
			seq = 0;
		}
	}

	/** Blocks a piston is moving right now: them turning into air is a move, not "broken". */
	static final Set<BlockPos> MOVING = new HashSet<>();

	public static void beginMove(List<BlockPos> blocks) {
		MOVING.addAll(blocks);
	}

	/** A piston moved these blocks one step: meters on them go along. Runs right after the piston started the move. */
	public static void onBlocksMoved(ServerLevel level, List<BlockPos> moved, Direction direction) {
		MOVING.clear();
		Set<BlockPos> from = new HashSet<>(moved);
		Set<BlockPos> to = new HashSet<>();
		for (BlockPos p : moved) {
			to.add(p.relative(direction));
		}
		MinecraftServer server = LabServer.server();
		for (Map.Entry<UUID, MeterState> e : STATES.entrySet()) {
			MeterState state = e.getValue();
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

	private static void sample(ServerPlayer player, MeterState state, long time) {
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
			int items = meterLevel == null ? -1 : ContainerInfo.itemCount(meterLevel, m.pos);
			if (items >= 0 && m.itemCount >= 0 && items != m.itemCount) {
				m.flag(items > m.itemCount ? MeterEvent.ITEM_IN : MeterEvent.ITEM_OUT);
			}
			if (items >= 0 && (items != m.itemCount || m.itemsChanged || m.nextLevel.isEmpty() || time % 20 == 0)) {
				m.nextLevel = items > 0 ? ContainerInfo.nextLevel(meterLevel, m.pos, m.power) : "";
				m.contents = ContainerInfo.contents(meterLevel, m.pos);
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
	 * Sequence numbers for the tick list. They keep counting until every meter has sent its events, so what a
	 * player did between two ticks sorts before the next tick.
	 */
	private static int seq;

	static int nextSeq() {
		return Math.min(++seq, 1 << 20);
	}

	/** Sets the expected on / off length of a meter. Without numbers, takes its latest pulse. Counting starts over. */
	public static void expect(ServerPlayer player, String label, @Nullable Integer on,
	                          @Nullable Integer off, boolean clear) {
		MeterState state = STATES.get(player.getUUID());
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
		MeterState state = STATES.get(player.getUUID());
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
		MeterState state = STATES.get(player.getUUID());
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
		MeterState state = STATES.get(player.getUUID());
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
		return Arrays.toString(m.rises) + " count " + m.riseCount;
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
			list.add(MeterEvent.values()[packed & 31] + "@" + TickPhase.byOrdinal((packed >>> 5) & 7));
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
		MeterState state = STATES.get(player.getUUID());
		return state == null ? "" : orderLine(state, now);
	}

	public static List<String> labels(ServerPlayer player) {
		MeterState state = STATES.get(player.getUUID());
		List<String> list = new ArrayList<>();
		if (state != null) {
			for (Meter m : state.meters) {
				list.add(m.label);
			}
		}
		return list;
	}

	private static void sendLayout(ServerPlayer player, MeterState state) {
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

	/** The order line "A C -> 2t -> B D": order the meters turn on, measured from the first meter. Same tick = same group. */
	static String orderLine(MeterState state) {
		long now = Long.MIN_VALUE;
		for (Meter m : state.meters) {
			now = Math.max(now, m.now);
		}
		return orderLine(state, now);
	}

	static String orderLine(MeterState state, long now) {
		List<OrderLine.Input> inputs = new ArrayList<>();
		for (Meter m : state.meters) {
			long[] rises = new long[m.rises.length];
			Arrays.fill(rises, Long.MIN_VALUE);
			System.arraycopy(m.rises, 0, rises, 0, Math.min(m.riseCount, m.rises.length));
			inputs.add(new OrderLine.Input(m.label, rises, m.riseSeqs.clone()));
		}
		return OrderLine.compute(inputs, now);
	}

	private static void updateBossBars(ServerPlayer player, MeterState state) {
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
	private static void showOutlines(ServerPlayer player, MeterState state) {
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
			MeterState state = STATES.get(player.getUUID());
			if (state != null && player.level().getGameTime() - state.lastClick < 60) {
				continue;
			}
			Msg.actionBar(player, Component.literal("Right-click: meter on/off  •  Sneak: remove all")
					.withStyle(ChatFormatting.GRAY));
		}
	}

	/** The client lost its entities or HUD state (respawn, dimension change): send everything again. */
	public static void onClientReset(ServerPlayer player) {
		MeterState state = STATES.get(player.getUUID());
		if (state != null) {
			state.dirty = true;
		}
	}

	public static void onLeave(ServerPlayer player) {
		MeterState state = STATES.remove(player.getUUID());
		if (state != null) {
			save(player, state);
			for (Meter m : state.meters) {
				PowerWatch.remove(m.dimension, m.pos, m);
				m.bossBar.hide(player);
			}
		}
	}
}
