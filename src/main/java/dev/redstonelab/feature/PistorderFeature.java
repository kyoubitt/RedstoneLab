package dev.redstonelab.feature;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.piston.PistonStructureResolver;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.BlockHitResult;

import dev.redstonelab.display.MarkerService;
import dev.redstonelab.net.LabPayloads;
import dev.redstonelab.net.LabPayloads.MarkerData;
import dev.redstonelab.server.LabServer;
import dev.redstonelab.util.Fmt;
import dev.redstonelab.util.Msg;

/**
 * Piston preview, like Pistorder: right-click a piston with an empty hand and it shows what it
 * will do next: "✔ push 3" with the move order on the blocks, or ✖ and why (the block in the
 * way, or too many), plus QC / BUD when its power is unusual. Click again to hide it; several
 * pistons can be shown; sneaking does nothing; the P key hides them all. Works for vanilla
 * clients too (display entities).
 */
public final class PistorderFeature {
	public static final String CHANNEL = "pistorder";
	private static final int PUSH = 0xFF55FF55;
	private static final int DESTROY = 0xFFFF5555;
	private static final int BLOCKER = 0xFFFF00FF;
	private static final int LANDS = 0xFF55FFFF;

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

	/** One shown piston. */
	private static final class Preview {
		final BlockPos pos;
		/** What was last shown; kept while the piston is mid-move (the preview would be wrong then). */
		List<MarkerData> markers = List.of();
		/** Set by {@link #head}: the first line of the piston's label. */
		String headLabel = "";
		boolean headOk;

		Preview(BlockPos pos) {
			this.pos = pos;
		}
	}

	private static final class State {
		ResourceKey<Level> dimension;
		final Map<BlockPos, Preview> pistons = new LinkedHashMap<>();
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
		Preview p = new Preview(pos.immutable());
		state.pistons.put(p.pos, p);
		Component text = build(level, p, new ArrayList<>(), options(player));
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

	/** Keeps the shown previews up to date (blocks change, pistons fire). */
	public static void tick(MinecraftServer server) {
		if (STATES.isEmpty() || server.getTickCount() % 2 != 0) {
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
		for (java.util.Iterator<Preview> it = state.pistons.values().iterator(); it.hasNext(); ) {
			Preview p = it.next();
			if (level.hasChunkAt(p.pos) && !isMoving(level, p.pos)) {
				if (!(level.getBlockState(p.pos).getBlock() instanceof PistonBaseBlock)) {
					it.remove();
					continue;
				}
				List<MarkerData> fresh = new ArrayList<>();
				build(level, p, fresh, options(player));
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

	/** For the game tests: the piston's label ("✔ push 1"). */
	static String labelForTest(ServerPlayer player, BlockPos pos) {
		State state = STATES.get(player.getUUID());
		Preview p = state == null ? null : state.pistons.get(pos);
		return p == null ? "" : p.headLabel;
	}

	// ------------------------------------------------------------------ markers

	/** The piston or its head is a "moving piston" right now. */
	private static boolean isMoving(ServerLevel level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		if (state.is(Blocks.MOVING_PISTON)) {
			return true;
		}
		return state.getBlock() instanceof PistonBaseBlock
				&& level.getBlockState(pos.relative(state.getValue(BlockStateProperties.FACING))).is(Blocks.MOVING_PISTON);
	}

	/**
	 * Adds the markers for the piston and returns the action bar text. Everything about the
	 * piston itself is one label behind it, so it never covers the numbered blocks in front.
	 */
	private static Component build(ServerLevel level, Preview p, List<MarkerData> markers, Options o) {
		Component move = buildMove(level, p, markers, o);
		StringBuilder label = new StringBuilder(p.headLabel);
		String power = powerInfo(level, p.pos);
		if (power != null) {
			label.append("\n\u26a1 ").append(power);
		}
		int color = p.headOk ? PUSH : DESTROY;
		markers.add(new MarkerData(p.pos, 0.5f, "", color, LabPayloads.STYLE_OUTLINE));
		Direction facing = level.getBlockState(p.pos).getValue(BlockStateProperties.FACING);
		markers.add(new MarkerData(p.pos.relative(facing.getOpposite()), 0.5f, label.toString(), color, LabPayloads.STYLE_LABEL));
		if (power == null) {
			return move;
		}
		String why = switch (power) {
			case "QC" -> "QC: powered from above";
			case "BUD" -> "BUD: moves on the next block update";
			default -> "powered but can't push";
		};
		return Component.empty().append(move).append(Component.literal(" \u00b7 " + why).withStyle(ChatFormatting.YELLOW));
	}

	/**
	 * Unusual power, checked like vanilla: "QC" (only powered through the block above it),
	 * "BUD" (power doesn't match its state, so the next block update moves it), "stuck"
	 * (powered but it can't push). Null when nothing is unusual.
	 */
	static @Nullable String powerInfo(ServerLevel level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		if (!(state.getBlock() instanceof PistonBaseBlock)) {
			return null;
		}
		Direction facing = state.getValue(BlockStateProperties.FACING);
		boolean direct = level.hasSignal(pos, Direction.DOWN);
		for (Direction d : Direction.values()) {
			if (d != facing && level.hasSignal(pos.relative(d), d)) {
				direct = true;
			}
		}
		boolean qc = false;
		BlockPos above = pos.above();
		for (Direction d : Direction.values()) {
			if (d != Direction.DOWN && level.hasSignal(above.relative(d), d)) {
				qc = true;
			}
		}
		boolean powered = direct || qc;
		boolean extended = state.getValue(BlockStateProperties.EXTENDED);
		if (powered && !extended) {
			// Vanilla only fires a powered piston if it can actually push.
			return new PistonStructureResolver(level, pos, facing, true).resolve() ? "BUD" : "stuck";
		}
		if (!powered && extended) {
			return "BUD";
		}
		return qc && !direct ? "QC" : null;
	}

	private static Component buildMove(ServerLevel level, Preview s, List<MarkerData> markers, Options o) {
		BlockPos pos = s.pos;
		BlockState state = level.getBlockState(pos);
		Direction facing = state.getValue(BlockStateProperties.FACING);
		boolean extended = state.getValue(BlockStateProperties.EXTENDED);
		boolean sticky = state.is(Blocks.STICKY_PISTON);
		byte box = (byte) (LabPayloads.STYLE_LABEL | LabPayloads.STYLE_OUTLINE);

		if (extended && !sticky) {
			return head(markers, s, true, "retract", "retracts (normal pistons don't pull)");
		}
		if (extended) {
			// Vanilla only pulls when the block in front is a normal movable block.
			BlockPos front = pos.relative(facing, 2);
			BlockState frontState = level.getBlockState(front);
			if (frontState.isAir()) {
				return head(markers, s, true, "retract", "retracts, nothing to pull");
			}
			if (!PistonBaseBlock.isPushable(frontState, level, front, facing.getOpposite(), false, facing)
					|| frontState.getPistonPushReaction() != PushReaction.NORMAL && !(frontState.getBlock() instanceof PistonBaseBlock)) {
				markers.add(new MarkerData(front, 0.5f, "stays", BLOCKER, box));
				return head(markers, s, true, "retract", "retracts, leaves " + Fmt.pretty(frontState));
			}
		}

		boolean extending = !extended;
		PushPreview preview = new PushPreview(level, pos, facing, extending);
		boolean ok = preview.resolve();
		if (extending) {
			// The real resolver is the final word (other mods can change the push limit).
			PistonStructureResolver vanilla = new PistonStructureResolver(level, pos, facing, true);
			if (vanilla.resolve() != ok) {
				ok = !ok;
				preview.toPush.clear();
				preview.toPush.addAll(vanilla.getToPush());
				preview.toDestroy.clear();
				preview.toDestroy.addAll(vanilla.getToDestroy());
			}
		}
		List<BlockPos> toPush = preview.toPush;
		List<BlockPos> toDestroy = preview.toDestroy;
		for (int i = 0; i < toPush.size(); i++) {
			markers.add(new MarkerData(toPush.get(i), 0.5f, Integer.toString(i + 1), ok ? PUSH : DESTROY, box));
		}
		for (BlockPos p : toDestroy) {
			markers.add(new MarkerData(p, 0.5f, "breaks", DESTROY, box));
		}
		if (ok && o.landing) {
			// Where the moving blocks end up: outline the spaces that become filled.
			Direction moveDir = extending ? facing : facing.getOpposite();
			Set<BlockPos> moving = new HashSet<>(toPush);
			for (BlockPos p : toPush) {
				BlockPos dest = p.relative(moveDir);
				if (!moving.contains(dest)) {
					markers.add(new MarkerData(dest, 0.5f, "", LANDS, LabPayloads.STYLE_OUTLINE));
				}
			}
		}
		String verb = extending ? "push" : "pull";
		int n = toPush.size();
		String breaks = toDestroy.isEmpty() ? "" : ", break " + toDestroy.size();
		if (ok) {
			if (n == 0 && toDestroy.isEmpty()) {
				return head(markers, s, true, extending ? "extend" : "retract", extending ? "extends, nothing to push" : "retracts, nothing to pull");
			}
			return head(markers, s, true, verb + " " + n + breaks, (extending ? "pushes " : "pulls ") + n + "/" + PushPreview.MAX_PUSH + breaks);
		}
		String result = extending ? " (won't extend)" : " (retracts without them)";
		if (preview.failure == PushPreview.Failure.TOO_MANY) {
			if (preview.overflow != null) {
				markers.add(new MarkerData(preview.overflow, 0.5f, "13th", BLOCKER, box));
			}
			return head(markers, s, false, "too many", "too many blocks, max " + PushPreview.MAX_PUSH + result);
		}
		BlockPos blocker = preview.blocker;
		if (blocker != null) {
			markers.add(new MarkerData(blocker, 0.5f, "stuck", BLOCKER, box));
			return head(markers, s, false, "blocked", "blocked by " + Fmt.pretty(level.getBlockState(blocker)) + result);
		}
		return head(markers, s, false, "can't " + verb, "can't " + verb + result);
	}

	/** Sets the first line of the piston's label ("✔ push 3") and returns the action bar text. */
	private static Component head(List<MarkerData> markers, Preview p, boolean ok, String label, String text) {
		String mark = ok ? "\u2714 " : "\u2716 ";
		p.headLabel = mark + label;
		p.headOk = ok;
		return Component.literal(mark + text).withStyle(ok ? ChatFormatting.GREEN : ChatFormatting.RED);
	}
}
