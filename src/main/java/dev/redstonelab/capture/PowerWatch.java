package dev.redstonelab.capture;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import dev.redstonelab.core.MeterEvent;
import dev.redstonelab.core.Reading;
import dev.redstonelab.feature.MeterFeature;
import dev.redstonelab.util.PowerUtil;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

/**
 * Watches the blocks that have a meter: power and state changes as they happen, plus the game events that reach them
 * (scheduled ticks, block events, block and shape updates).
 *
 * Every setBlock, comparator output change and block entity change inside a 5x5x5 cube around a watched
 * block re-checks that block; anything farther away is rejected by one hash lookup.
 */
public final class PowerWatch {
	public interface Listener {
		void onWatchChange(Change change);

		/** A game event reached the watched block. Runs inside the game logic, keep it cheap. */
		default void onWatchEvent(MeterEvent event) {
		}

		/** A block event ran at the watched block (pistons: 0 = extend, 1 / 2 = retract). */
		default void onWatchBlockEvent(int type, long gameTime) {
		}
	}

	/** One detected change at a watched position. Readings are packed, see {@link Reading}. */
	public record Change(BlockPos pos, long gameTime, int oldReading, int newReading, BlockState oldState, BlockState newState) {
		public boolean readingChanged() {
			return this.oldReading != this.newReading;
		}

		public boolean stateChanged() {
			return this.oldState != this.newState;
		}
	}

	private static final int REACH = 2;

	private static final class Watch {
		final BlockPos pos;
		final List<Listener> listeners = new ArrayList<>(2);
		int reading;
		BlockState state;

		Watch(BlockPos pos) {
			this.pos = pos;
		}
	}

	private static final class DimWatches {
		final Long2ObjectOpenHashMap<Watch> watches = new Long2ObjectOpenHashMap<>();
		final Long2IntOpenHashMap near = new Long2IntOpenHashMap();
	}

	private static final Map<ResourceKey<Level>, DimWatches> DIMS = new HashMap<>();
	private static final List<Change> PENDING = new ArrayList<>();
	private static final List<Listener> PENDING_TARGETS = new ArrayList<>();
	private static boolean dispatching;

	private PowerWatch() {
	}

	// ------------------------------------------------------------------ management

	public static void add(ServerLevel level, BlockPos pos, Listener listener) {
		DimWatches dim = DIMS.computeIfAbsent(level.dimension(), k -> new DimWatches());
		long key = pos.asLong();
		Watch watch = dim.watches.get(key);
		if (watch == null) {
			watch = new Watch(pos.immutable());
			watch.state = level.getBlockState(pos);
			watch.reading = PowerUtil.read(level, pos, watch.state);
			dim.watches.put(key, watch);
			for (int dx = -REACH; dx <= REACH; dx++) {
				for (int dy = -REACH; dy <= REACH; dy++) {
					for (int dz = -REACH; dz <= REACH; dz++) {
						dim.near.addTo(BlockPos.asLong(pos.getX() + dx, pos.getY() + dy, pos.getZ() + dz), 1);
					}
				}
			}
		}
		if (!watch.listeners.contains(listener)) {
			watch.listeners.add(listener);
		}
		Capture.watching = true;
	}

	public static void remove(ResourceKey<Level> dimension, BlockPos pos, Listener listener) {
		DimWatches dim = DIMS.get(dimension);
		if (dim == null) {
			return;
		}
		Watch watch = dim.watches.get(pos.asLong());
		if (watch != null && watch.listeners.remove(listener) && watch.listeners.isEmpty()) {
			drop(dim, watch);
		}
		refresh();
	}

	/** Current reading of a watched block, or -1 if nothing watches it. */
	public static int readingAt(ResourceKey<Level> dimension, BlockPos pos) {
		DimWatches dim = DIMS.get(dimension);
		Watch w = dim == null ? null : dim.watches.get(pos.asLong());
		return w == null ? -1 : w.reading;
	}

	public static void clearAll() {
		DIMS.clear();
		refresh();
	}

	private static void drop(DimWatches dim, Watch watch) {
		dim.watches.remove(watch.pos.asLong());
		for (int dx = -REACH; dx <= REACH; dx++) {
			for (int dy = -REACH; dy <= REACH; dy++) {
				for (int dz = -REACH; dz <= REACH; dz++) {
					long k = BlockPos.asLong(watch.pos.getX() + dx, watch.pos.getY() + dy, watch.pos.getZ() + dz);
					if (dim.near.addTo(k, -1) <= 1) {
						dim.near.remove(k);
					}
				}
			}
		}
	}

	/** Recomputes {@link Capture#watching}. */
	public static void refresh() {
		DIMS.values().removeIf(d -> d.watches.isEmpty());
		Capture.watching = !DIMS.isEmpty();
	}

	// ------------------------------------------------------------------ hot path

	/** Called when a scheduled tick, block event, block update or shape update runs at {@code pos}. */
	public static void onEvent(Level level, BlockPos pos, MeterEvent event) {
		if (!(level instanceof ServerLevel serverLevel)) {
			return;
		}
		DimWatches dim = DIMS.get(serverLevel.dimension());
		Watch w = dim == null ? null : dim.watches.get(pos.asLong());
		if (w != null) {
			for (Listener l : w.listeners) {
				l.onWatchEvent(event);
			}
		}
	}

	/** Called when a block event (a piston starting to move, a note block playing...) runs at {@code pos}. */
	public static void onBlockEvent(ServerLevel level, BlockPos pos, int type) {
		DimWatches dim = DIMS.get(level.dimension());
		Watch w = dim == null ? null : dim.watches.get(pos.asLong());
		if (w != null) {
			long time = level.getGameTime();
			for (Listener l : w.listeners) {
				l.onWatchBlockEvent(type, time);
			}
		}
	}

	/** Called after a block state or comparator output changed at {@code pos}. */
	public static void onBlockChanged(Level level, BlockPos pos) {
		if (!(level instanceof ServerLevel serverLevel)) {
			return;
		}
		DimWatches dim = DIMS.get(serverLevel.dimension());
		if (dim == null || !dim.near.containsKey(pos.asLong())) {
			return;
		}
		// The block that changed comes first: it is the cause of what changes around it.
		Watch self = dim.watches.get(pos.asLong());
		if (self != null) {
			evaluate(serverLevel, self);
		}
		int x = pos.getX(), y = pos.getY(), z = pos.getZ();
		for (Watch w : dim.watches.values()) {
			if (w != self && Math.abs(w.pos.getX() - x) <= REACH && Math.abs(w.pos.getY() - y) <= REACH && Math.abs(w.pos.getZ() - z) <= REACH) {
				evaluate(serverLevel, w);
			}
		}
		dispatch();
	}

	/**
	 * Called when a block's new state is stored, before it updates its neighbors, so its own change is recorded
	 * before anything it causes.
	 */
	public static void onStateStored(Level level, BlockPos pos) {
		if (!(level instanceof ServerLevel serverLevel)) {
			return;
		}
		DimWatches dim = DIMS.get(serverLevel.dimension());
		Watch w = dim == null ? null : dim.watches.get(pos.asLong());
		if (w != null) {
			evaluate(serverLevel, w);
			dispatch();
		}
	}

	/** Called when a block entity at {@code pos} was marked changed (container contents). */
	public static void onBlockEntityChanged(Level level, BlockPos pos) {
		if (!(level instanceof ServerLevel serverLevel)) {
			return;
		}
		DimWatches dim = DIMS.get(serverLevel.dimension());
		if (dim == null) {
			return;
		}
		Watch w = dim.watches.get(pos.asLong());
		if (w != null) {
			MeterFeature.onContainerChanged(serverLevel, pos);
			evaluate(serverLevel, w);
			dispatch();
		}
	}

	private static void evaluate(ServerLevel level, Watch w) {
		// The reading looks up to 2 blocks around (QC, torches): never make the game load chunks for it.
		if (!level.hasChunksAt(w.pos.offset(-REACH, 0, -REACH), w.pos.offset(REACH, 0, REACH))) {
			return;
		}
		BlockState state = level.getBlockState(w.pos);
		int reading = PowerUtil.read(level, w.pos, state);
		if (reading == w.reading && state == w.state) {
			return;
		}
		Change change = new Change(w.pos, level.getGameTime(), w.reading, reading, w.state, state);
		w.reading = reading;
		w.state = state;
		for (Listener l : w.listeners) {
			PENDING.add(change);
			PENDING_TARGETS.add(l);
		}
	}

	private static void dispatch() {
		if (dispatching || PENDING.isEmpty()) {
			return;
		}
		dispatching = true;
		try {
			for (int i = 0; i < PENDING.size(); i++) {
				PENDING_TARGETS.get(i).onWatchChange(PENDING.get(i));
			}
		} finally {
			PENDING.clear();
			PENDING_TARGETS.clear();
			dispatching = false;
		}
	}
}
