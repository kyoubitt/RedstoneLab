package dev.redstonelab.feature;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.PushReaction;

/**
 * Works out what a piston would move, following vanilla's PistonStructureResolver step by step without touching
 * the world, and keeps why it fails. Vanilla removes the head before resolving a sticky pull; here the head
 * position is treated as air ({@link #ignored}).
 */
final class PushPreview {
	static final int MAX_PUSH = 12;

	enum Failure {
		NONE,
		TOO_MANY,
		BLOCKED
	}

	private final Level level;
	private final BlockPos pistonPos;
	private final Direction pistonDirection;
	private final Direction pushDirection;
	private final BlockPos startPos;
	private final boolean extending;
	/** Treated as air (the piston head while retracting). */
	private final @Nullable BlockPos ignored;

	final List<BlockPos> toPush = new ArrayList<>();
	final List<BlockPos> toDestroy = new ArrayList<>();
	Failure failure = Failure.NONE;
	/** The block that cannot move, for {@link Failure#BLOCKED}. */
	@Nullable BlockPos blocker;
	/** The first block over the push limit, for {@link Failure#TOO_MANY}. */
	@Nullable BlockPos overflow;

	PushPreview(Level level, BlockPos pistonPos, Direction pistonDirection, boolean extending) {
		this.level = level;
		this.pistonPos = pistonPos;
		this.pistonDirection = pistonDirection;
		this.extending = extending;
		this.pushDirection = extending ? pistonDirection : pistonDirection.getOpposite();
		this.startPos = extending ? pistonPos.relative(pistonDirection) : pistonPos.relative(pistonDirection, 2);
		this.ignored = extending ? null : pistonPos.relative(pistonDirection);
	}

	private BlockState state(BlockPos pos) {
		return pos.equals(this.ignored) ? Blocks.AIR.defaultBlockState() : this.level.getBlockState(pos);
	}

	private boolean pushable(BlockState state, BlockPos pos, Direction dir, boolean allowDestroy, Direction facing) {
		return PistonBaseBlock.isPushable(state, this.level, pos, dir, allowDestroy, facing);
	}

	private boolean blocked(BlockPos pos) {
		this.failure = Failure.BLOCKED;
		this.blocker = pos.immutable();
		return false;
	}

	private boolean tooMany(BlockPos pos) {
		this.failure = Failure.TOO_MANY;
		this.overflow = pos.immutable();
		return false;
	}

	boolean resolve() {
		this.toPush.clear();
		this.toDestroy.clear();
		BlockState start = this.state(this.startPos);
		if (!this.pushable(start, this.startPos, this.pushDirection, false, this.pistonDirection)) {
			if (this.extending && start.getPistonPushReaction() == PushReaction.DESTROY) {
				this.toDestroy.add(this.startPos);
				return true;
			}
			return this.blocked(this.startPos);
		}
		if (!this.addBlockLine(this.startPos, this.pushDirection)) {
			return false;
		}
		for (int i = 0; i < this.toPush.size(); i++) {
			BlockPos pos = this.toPush.get(i);
			if (isSticky(this.state(pos)) && !this.addBranchingBlocks(pos)) {
				return false;
			}
		}
		return true;
	}

	private static boolean isSticky(BlockState state) {
		return state.is(Blocks.SLIME_BLOCK) || state.is(Blocks.HONEY_BLOCK);
	}

	private static boolean canStickToEachOther(BlockState a, BlockState b) {
		if (a.is(Blocks.HONEY_BLOCK) && b.is(Blocks.SLIME_BLOCK)) {
			return false;
		}
		if (a.is(Blocks.SLIME_BLOCK) && b.is(Blocks.HONEY_BLOCK)) {
			return false;
		}
		return isSticky(a) || isSticky(b);
	}

	private boolean addBlockLine(BlockPos fromPos, Direction fromDir) {
		BlockState state = this.state(fromPos);
		if (state.isAir() || !this.pushable(state, fromPos, this.pushDirection, false, fromDir)
				|| fromPos.equals(this.pistonPos) || this.toPush.contains(fromPos)) {
			return true;
		}
		int length = 1;
		if (length + this.toPush.size() > MAX_PUSH) {
			return this.tooMany(fromPos);
		}
		// Blocks stuck to the back of this one come along.
		while (isSticky(state)) {
			BlockPos behind = fromPos.relative(this.pushDirection.getOpposite(), length);
			BlockState previous = state;
			state = this.state(behind);
			if (state.isAir() || !canStickToEachOther(previous, state)
					|| !this.pushable(state, behind, this.pushDirection, false, this.pushDirection.getOpposite()) || behind.equals(this.pistonPos)) {
				break;
			}
			if (++length + this.toPush.size() > MAX_PUSH) {
				return this.tooMany(behind);
			}
		}
		int added = 0;
		for (int k = length - 1; k >= 0; k--) {
			this.toPush.add(fromPos.relative(this.pushDirection.getOpposite(), k));
			added++;
		}
		// Blocks in front get pushed too.
		for (int k = 1; ; k++) {
			BlockPos front = fromPos.relative(this.pushDirection, k);
			int index = this.toPush.indexOf(front);
			if (index > -1) {
				this.reorderListAtCollision(added, index);
				for (int m = 0; m <= index + added; m++) {
					BlockPos pos = this.toPush.get(m);
					if (isSticky(this.state(pos)) && !this.addBranchingBlocks(pos)) {
						return false;
					}
				}
				return true;
			}
			state = this.state(front);
			if (state.isAir()) {
				return true;
			}
			if (!this.pushable(state, front, this.pushDirection, true, this.pushDirection) || front.equals(this.pistonPos)) {
				return this.blocked(front);
			}
			if (state.getPistonPushReaction() == PushReaction.DESTROY) {
				this.toDestroy.add(front);
				return true;
			}
			if (this.toPush.size() >= MAX_PUSH) {
				return this.tooMany(front);
			}
			this.toPush.add(front);
			added++;
		}
	}

	private void reorderListAtCollision(int offsets, int index) {
		List<BlockPos> before = new ArrayList<>(this.toPush.subList(0, index));
		List<BlockPos> added = new ArrayList<>(this.toPush.subList(this.toPush.size() - offsets, this.toPush.size()));
		List<BlockPos> after = new ArrayList<>(this.toPush.subList(index, this.toPush.size() - offsets));
		this.toPush.clear();
		this.toPush.addAll(before);
		this.toPush.addAll(added);
		this.toPush.addAll(after);
	}

	private boolean addBranchingBlocks(BlockPos fromPos) {
		BlockState state = this.state(fromPos);
		for (Direction dir : Direction.values()) {
			if (dir.getAxis() != this.pushDirection.getAxis()) {
				BlockPos side = fromPos.relative(dir);
				if (canStickToEachOther(this.state(side), state) && !this.addBlockLine(side, dir)) {
					return false;
				}
			}
		}
		return true;
	}
}
