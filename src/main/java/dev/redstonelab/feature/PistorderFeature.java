package dev.redstonelab.feature;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.phys.BlockHitResult;

import dev.redstonelab.display.MarkerService;
import dev.redstonelab.net.LabPayloads.MarkerData;
import dev.redstonelab.server.LabServer;
import dev.redstonelab.util.Msg;

/**
 * Piston preview, like Pistorder: right-click a piston with an empty hand and it shows what it
 * will do next: "✔ push 3/12" with the move order on the blocks, or ✖ and why (the block in the
 * way, or too many), plus QC / BUD when its power is unusual. Click again to hide it; several
 * pistons can be shown; sneaking does nothing; the P key hides them all. Works for vanilla
 * clients too (display entities).
 */
public final class PistorderFeature {
	public static final String CHANNEL = "pistorder";

	/** What each player wants to see (set from the client's Pistorder settings or /rlab piston). */
	public static final class Options {
		public boolean landing = false;
	}

	private static final Map<UUID, Options> OPTIONS = new HashMap<>();

	public static Options options(ServerPlayer player) {
		return OPTIONS.computeIfAbsent(player.getUUID(), k -> new Options());
	}

	public static void resetOptions(ServerPlayer player) {
		OPTIONS.remove(player.getUUID());
	}

	/** Changes one option ("landing"); returns false for an unknown name. */
	public static boolean setOption(ServerPlayer player, String name, boolean value) {
		if (!name.equals("landing")) {
			return false;
		}
		options(player).landing = value;
		State state = STATES.get(player.getUUID());
		if (state != null) {
			refresh(player, state);
		}
		return true;
	}

	private static final class State {
		ResourceKey<Level> dimension;
		final Map<BlockPos, PistonView.View> pistons = new LinkedHashMap<>();
	}

	private static final Map<UUID, State> STATES = new HashMap<>();

	private PistorderFeature() {
	}

	/**
	 * Like Pistorder: right-click a piston with an empty hand to show what it will do, click it
	 * again to hide it. Nothing happens while sneaking.
	 */
	public static InteractionResult onUseBlock(Player player, Level level, InteractionHand hand, BlockHitResult hit) {
		if (hand != InteractionHand.MAIN_HAND || !player.getMainHandItem().isEmpty() || player.isShiftKeyDown()
				|| !(player instanceof ServerPlayer serverPlayer) || !(level instanceof ServerLevel serverLevel)) {
			return InteractionResult.PASS;
		}
		BlockPos pos = hit.getBlockPos();
		if (!(level.getBlockState(pos).getBlock() instanceof PistonBaseBlock) || !LabServer.canUse(serverPlayer)) {
			return InteractionResult.PASS;
		}
		if (dev.redstonelab.server.Sessions.get(serverPlayer).modded) {
			// Players with the client mod see the preview client-side (works on any server).
			return InteractionResult.PASS;
		}
		toggle(serverPlayer, serverLevel, pos);
		return InteractionResult.SUCCESS;
	}

	/** Shows the piston's preview, or hides it if it is shown. */
	public static void toggle(ServerPlayer player, ServerLevel level, BlockPos pos) {
		State state = STATES.computeIfAbsent(player.getUUID(), k -> new State());
		if (state.dimension != level.dimension()) {
			state.pistons.clear();
			state.dimension = level.dimension();
		}
		if (state.pistons.remove(pos) != null) {
			refresh(player, state);
			Msg.actionBar(player, Component.literal("Piston preview hidden").withStyle(ChatFormatting.GRAY));
			return;
		}
		PistonView.View p = new PistonView.View(pos.immutable());
		state.pistons.put(p.pos, p);
		// Mid-move the preview would be wrong: it fills in when the blocks land.
		Component text = PistonView.isMoving(level, pos) ? Component.literal("Piston preview shown")
				: PistonView.build(level, p, new ArrayList<>(), options(player).landing);
		refresh(player, state);
		Msg.actionBar(player, text);
	}

	/** Hides all piston previews (the P key, /rlab piston clear). */
	public static void clear(ServerPlayer player) {
		if (STATES.remove(player.getUUID()) != null) {
			MarkerService.clear(player, CHANNEL);
		}
	}

	public static void onLeave(ServerPlayer player) {
		STATES.remove(player.getUUID());
	}

	/** A piston started extending: remember it for the firing order of everyone showing it. */
	static void onExtend(ServerLevel level, BlockPos pos, long time, long seq) {
		for (State state : STATES.values()) {
			PistonView.View view = state.dimension == level.dimension() ? state.pistons.get(pos) : null;
			if (view != null) {
				view.fires.add(time, seq);
			}
		}
	}

	/** Keeps the shown previews up to date (blocks change, pistons fire). */
	public static void tick(MinecraftServer server) {
		// Every tick: on a fast clock a piston may be still for only one tick at a time.
		if (STATES.isEmpty()) {
			return;
		}
		for (Map.Entry<UUID, State> e : new ArrayList<>(STATES.entrySet())) {
			ServerPlayer player = server.getPlayerList().getPlayer(e.getKey());
			if (player != null) {
				refresh(player, e.getValue());
			}
		}
	}

	private static void refresh(ServerPlayer player, State state) {
		ServerLevel level = player.level();
		if (state.dimension != level.dimension() || state.pistons.isEmpty()) {
			// Like Pistorder: previews go away when you change dimension.
			clear(player);
			return;
		}
		List<MarkerData> markers = new ArrayList<>();
		PistonView.applyOrder(state.pistons.values(), level.getGameTime(), true);
		for (java.util.Iterator<PistonView.View> it = state.pistons.values().iterator(); it.hasNext(); ) {
			PistonView.View p = it.next();
			if (level.hasChunkAt(p.pos) && !PistonView.isMoving(level, p.pos)) {
				if (!(level.getBlockState(p.pos).getBlock() instanceof PistonBaseBlock)) {
					it.remove();
					continue;
				}
				List<MarkerData> fresh = new ArrayList<>();
				PistonView.build(level, p, fresh, options(player).landing);
				p.markers = fresh;
			}
			markers.addAll(p.markers);
		}
		if (state.pistons.isEmpty()) {
			clear(player);
			return;
		}
		MarkerService.set(player, CHANNEL, markers);
	}

	/** For the game tests: is this piston's preview shown? */
	static boolean shownForTest(ServerPlayer player, BlockPos pos) {
		State state = STATES.get(player.getUUID());
		return state != null && state.pistons.containsKey(pos);
	}

	/** For the game tests: the piston's place in the firing order ("#2 · +3t"). */
	static String orderForTest(ServerPlayer player, BlockPos pos) {
		State state = STATES.get(player.getUUID());
		PistonView.View p = state == null ? null : state.pistons.get(pos);
		return p == null ? "" : p.order;
	}

	/** For the game tests: the piston's label ("✔ push 1/12"). */
	static String labelForTest(ServerPlayer player, BlockPos pos) {
		State state = STATES.get(player.getUUID());
		PistonView.View p = state == null ? null : state.pistons.get(pos);
		return p == null ? "" : p.headLabel;
	}
}
