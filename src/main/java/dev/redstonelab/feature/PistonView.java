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
import net.minecraft.world.level.block.ComparatorBlock;
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
 * What a piston will do next (shared by the server and the client): push order on the blocks,
 * ✔ / ✖ and why, QC / BUD. Pistorder-style.
 */
public final class PistonView {
	private static final int PUSH = 0xFF55FF55;
	private static final int DESTROY = 0xFFFF5555;
	private static final int BLOCKER = 0xFFFF00FF;
	private static final int LANDS = 0xFF55FFFF;

	/** One shown piston. */
	public static final class View {
		public final BlockPos pos;
		/** What was last shown; kept while the piston is mid-move (the preview would be wrong then). */
		public List<MarkerData> markers = List.of();
		/** Set by {@link #head}: the first line of the piston's label ("✔ push 3"). */
		public String headLabel = "";
		boolean headOk;

		public View(BlockPos pos) {
			this.pos = pos;
		}
	}

	private PistonView() {
	}

	/** The piston or its head is a "moving piston" right now. */
	public static boolean isMoving(Level level, BlockPos pos) {
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
	public static Component build(Level level, View p, List<MarkerData> markers, boolean landing) {
		Component move = buildMove(level, p, markers, landing);
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
	public static @Nullable String powerInfo(Level level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		if (!(state.getBlock() instanceof PistonBaseBlock)) {
			return null;
		}
		Direction facing = state.getValue(BlockStateProperties.FACING);
		boolean direct = hasSignal(level, pos, Direction.DOWN);
		for (Direction d : Direction.values()) {
			if (d != facing && hasSignal(level, pos.relative(d), d)) {
				direct = true;
			}
		}
		boolean qc = false;
		BlockPos above = pos.above();
		for (Direction d : Direction.values()) {
			if (d != Direction.DOWN && hasSignal(level, above.relative(d), d)) {
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

	/**
	 * {@link Level#hasSignal}, but also right on the client: the server never sends a
	 * comparator's output strength, so there it is read from the comparator being lit (which is
	 * exactly "output above 0").
	 */
	private static boolean hasSignal(Level level, BlockPos pos, Direction dir) {
		return level.isClientSide() ? clientSignal(level, pos, dir) : level.hasSignal(pos, dir);
	}

	/** The client's version of {@link Level#hasSignal} (package-private for the tests). */
	static boolean clientSignal(Level level, BlockPos pos, Direction dir) {
		BlockState state = level.getBlockState(pos);
		if (emits(level, pos, state, dir, false)) {
			return true;
		}
		if (state.isRedstoneConductor(level, pos)) {
			for (Direction d : Direction.values()) {
				BlockPos n = pos.relative(d);
				if (emits(level, n, level.getBlockState(n), d, true)) {
					return true;
				}
			}
		}
		return false;
	}

	private static boolean emits(Level level, BlockPos pos, BlockState state, Direction dir, boolean direct) {
		if (state.getBlock() instanceof ComparatorBlock) {
			return state.getValue(BlockStateProperties.POWERED) && state.getValue(BlockStateProperties.HORIZONTAL_FACING) == dir;
		}
		return (direct ? state.getDirectSignal(level, pos, dir) : state.getSignal(level, pos, dir)) > 0;
	}

	private static Component buildMove(Level level, View s, List<MarkerData> markers, boolean landing) {
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
		if (ok && landing) {
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
	private static Component head(List<MarkerData> markers, View p, boolean ok, String label, String text) {
		String mark = ok ? "\u2714 " : "\u2716 ";
		p.headLabel = mark + label;
		p.headOk = ok;
		return Component.literal(mark + text).withStyle(ok ? ChatFormatting.GREEN : ChatFormatting.RED);
	}
}
