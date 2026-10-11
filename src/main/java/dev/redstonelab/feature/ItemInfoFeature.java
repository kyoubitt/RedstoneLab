package dev.redstonelab.feature;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import dev.redstonelab.core.ItemLife;
import dev.redstonelab.mixin.HopperBlockEntityAccessor;
import dev.redstonelab.mixin.ItemEntityAccessor;
import dev.redstonelab.net.LabNetworking;
import dev.redstonelab.net.LabPayloads;
import dev.redstonelab.server.LabServer;
import dev.redstonelab.server.PlayerSession;
import dev.redstonelab.server.Sessions;

/**
 * Item labels: every tick, for the items near players who turned them on (nearest {@link #MAX}
 * in their range): age, pickup delay, speed, and what the hopper under it will do. The client
 * never gets these numbers from the game.
 */
public final class ItemInfoFeature {
	public static final int MAX = 64;
	public static final int MAX_RANGE = 32;

	/** What a hopper will do with one item: kind + ticks (NEXT) or place in line (QUEUE). */
	record HopperInfo(ItemLife.Hopper kind, int value) {
		static final HopperInfo NONE = new HopperInfo(ItemLife.Hopper.NONE, 0);
	}

	private ItemInfoFeature() {
	}

	public static void tick(MinecraftServer server) {
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			PlayerSession session = Sessions.get(player);
			if (session.itemRange <= 0 || !session.modded || !LabServer.canUse(player)) {
				continue;
			}
			List<ItemEntity> items = collect(player, session.itemRange);
			if (items.isEmpty() && session.itemsSentEmpty) {
				continue;
			}
			session.itemsSentEmpty = items.isEmpty();
			int n = items.size();
			int[] ids = new int[n];
			int[] ages = new int[n];
			int[] pickups = new int[n];
			int[] hoppers = new int[n];
			float[] speeds = new float[n];
			Map<BlockPos, List<ItemEntity>> queues = new HashMap<>();
			for (int i = 0; i < n; i++) {
				ItemEntity item = items.get(i);
				ids[i] = item.getId();
				ages[i] = item.getAge();
				pickups[i] = ((ItemEntityAccessor) item).redstonelab$pickupDelay();
				HopperInfo h = hopper(player.level(), item, queues);
				hoppers[i] = h.kind().ordinal() | h.value() << 4;
				// How far it really moved this tick (resting items keep a little gravity velocity).
				speeds[i] = (float) item.position().distanceTo(new net.minecraft.world.phys.Vec3(item.xo, item.yo, item.zo)) * 20;
			}
			LabNetworking.send(player, new LabPayloads.ItemInfoS2C(ids, ages, pickups, hoppers, speeds));
		}
	}

	/** The nearest items within {@code range} blocks, at most {@link #MAX}. */
	static List<ItemEntity> collect(ServerPlayer player, int range) {
		List<ItemEntity> items = player.level().getEntitiesOfClass(ItemEntity.class, player.getBoundingBox().inflate(range), e -> e.isAlive());
		items.sort(Comparator.comparingDouble(player::distanceToSqr));
		return items.size() > MAX ? items.subList(0, MAX) : items;
	}

	/**
	 * The hopper that will take this item first, from the game's own pickup list
	 * ({@link HopperBlockEntity#getItemsAtAndAbove}, in pickup order) and the hopper's cooldown.
	 * A hopper takes one item entity per move, then waits 8 ticks.
	 */
	static HopperInfo hopper(ServerLevel level, ItemEntity item, Map<BlockPos, List<ItemEntity>> queues) {
		HopperInfo best = HopperInfo.NONE;
		BlockPos at = item.blockPosition();
		for (BlockPos pos : new BlockPos[]{at, at.below(), at.below(2)}) {
			if (!(level.getBlockEntity(pos) instanceof HopperBlockEntity hopper)) {
				continue;
			}
			List<ItemEntity> queue = queues.computeIfAbsent(pos.immutable(), k -> HopperBlockEntity.getItemsAtAndAbove(level, hopper));
			int index = queue.indexOf(item);
			if (index < 0) {
				continue;
			}
			HopperInfo info = status(level, pos, hopper, queue, index);
			if (rank(info) < rank(best)) {
				best = info;
			}
		}
		return best;
	}

	/**
	 * The game takes items two ways: the hopper's own move (first item of its pickup list, when
	 * its cooldown runs out; not with a full block or a container on top), and an item sitting in
	 * the hopper's bowl when that item moves (only while the hopper isn't cooling down). A time is
	 * only given when nothing can change it first: anything pushing into the hopper can reset its
	 * cooldown or fill its slots; in a covered hopper's bowl a newly landed item can jump ahead.
	 */
	private static HopperInfo status(ServerLevel level, BlockPos pos, HopperBlockEntity hopper, List<ItemEntity> queue, int index) {
		if (!level.getBlockState(pos).getValue(HopperBlock.ENABLED)) {
			return new HopperInfo(ItemLife.Hopper.LOCKED, 0);
		}
		ItemEntity item = queue.get(index);
		if (!fits(hopper, item.getItem())) {
			return new HopperInfo(ItemLife.Hopper.FULL, 0);
		}
		int cooldown = ((HopperBlockEntityAccessor) hopper).redstonelab$cooldown();
		// Something pushing in can reset its cooldown (when empty) or fill its slots: no exact time.
		boolean fed = fed(level, pos);
		BlockPos above = pos.above();
		BlockState aboveState = level.getBlockState(above);
		boolean container = HopperBlockEntity.getContainerAt(level, above) != null;
		boolean fullBlock = aboveState.isCollisionShapeFullBlock(level, above) && !aboveState.is(BlockTags.DOES_NOT_BLOCK_HOPPERS);
		if (container || fullBlock) {
			AABB bowl = new AABB(pos);
			if (!item.getBoundingBox().intersects(bowl)) {
				return new HopperInfo(ItemLife.Hopper.BLOCKED, 0);
			}
			if (container || !hopper.isEmpty() || fed) {
				return new HopperInfo(ItemLife.Hopper.BUSY, 0);
			}
			// Empty and nothing feeds it: the bowl item that moves first goes next (an item that
			// lands meanwhile can still jump ahead: no time). After that the hopper is busy.
			int mine = insideTake(item, cooldown);
			for (int i = 0; i < queue.size(); i++) {
				ItemEntity other = queue.get(i);
				if (i != index && other.getBoundingBox().intersects(bowl) && fits(hopper, other.getItem())) {
					int theirs = insideTake(other, cooldown);
					if (theirs < mine || theirs == mine && i < index) {
						return new HopperInfo(ItemLife.Hopper.BUSY, 0);
					}
				}
			}
			return new HopperInfo(ItemLife.Hopper.NEXT_UNTIMED, 0);
		}
		// Items before it that fit get taken first, one per move. One that doesn't fit yet can get
		// in first if the hopper passes an item on just before (it moves out, then takes in).
		int ahead = 0;
		boolean maybe = false;
		for (int i = 0; i < index; i++) {
			boolean fits = fits(hopper, queue.get(i).getItem());
			ahead += fits ? 1 : 0;
			maybe |= !fits && !hopper.isEmpty();
		}
		if (ahead > 0) {
			return new HopperInfo(ItemLife.Hopper.QUEUE, ahead + 1);
		}
		if (fed || maybe) {
			return new HopperInfo(ItemLife.Hopper.NEXT_UNTIMED, 0);
		}
		// It counts down at the start of its next tick and moves when it reaches 0.
		return new HopperInfo(ItemLife.Hopper.NEXT, Math.max(1, cooldown));
	}

	/** Something can push items into this hopper: a hopper, dropper, dispenser or crafter pointing at it. */
	private static boolean fed(ServerLevel level, BlockPos pos) {
		for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) {
			if (d == net.minecraft.core.Direction.DOWN) {
				continue;
			}
			BlockPos n = pos.relative(d);
			BlockState state = level.getBlockState(n);
			net.minecraft.core.Direction out = state.hasProperty(HopperBlock.FACING) ? state.getValue(HopperBlock.FACING)
					: state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING)
					? state.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING) : null;
			if (out != d.getOpposite()) {
				continue;
			}
			// Even an empty one: it can get an item and pass it on before the time is up.
			BlockEntity be = level.getBlockEntity(n);
			if (be instanceof HopperBlockEntity || be instanceof net.minecraft.world.level.block.entity.DispenserBlockEntity
					|| be instanceof net.minecraft.world.level.block.entity.CrafterBlockEntity) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Ticks until an item in the bowl is taken by the bowl check: the item has to move that tick
	 * (it always does in the air or sliding; resting, only when tick count + id is a multiple of 4)
	 * and the hopper's cooldown must have run out by then (it counts down after entities move).
	 */
	private static int insideTake(ItemEntity item, int cooldown) {
		boolean resting = item.onGround() && item.getDeltaMovement().horizontalDistanceSqr() <= 1.0E-5F;
		for (int k = 1; k < 64; k++) {
			boolean ready = cooldown - (k - 1) <= 0;
			boolean moves = !resting || (item.tickCount + k + item.getId()) % 4 == 0;
			if (ready && moves) {
				return k;
			}
		}
		return 64;
	}

	/** At least one of the stack fits in the hopper. */
	private static boolean fits(HopperBlockEntity hopper, ItemStack stack) {
		for (int i = 0; i < hopper.getContainerSize(); i++) {
			ItemStack slot = hopper.getItem(i);
			if (slot.isEmpty() || ItemStack.isSameItemSameComponents(slot, stack) && slot.getCount() < slot.getMaxStackSize()) {
				return true;
			}
		}
		return false;
	}

	/** Which hopper wins when two cover the item: the one that takes it soonest. */
	private static int rank(HopperInfo info) {
		return switch (info.kind()) {
			case NEXT -> info.value();
			case NEXT_UNTIMED -> 9_999;
			case QUEUE -> 10_000 + info.value();
			case FULL, LOCKED, BLOCKED, BUSY -> 100_000;
			case NONE -> 1_000_000;
		};
	}
}
