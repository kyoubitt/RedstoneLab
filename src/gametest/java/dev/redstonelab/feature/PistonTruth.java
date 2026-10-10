package dev.redstonelab.feature;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import dev.redstonelab.net.LabPayloads.MarkerData;

/**
 * Checks piston previews against what pistons really do. Call {@link #step} once per game tick
 * (between ticks). Every still piston in the box gets a preview, built the same way the player
 * sees it. When a piston then really moves, the blocks the game moved (the new moving-piston
 * blocks) must be exactly the numbered blocks of the preview from the tick before, and the
 * "breaks" blocks must be gone. A piston that moves although its preview said ✖ is wrong too.
 */
final class PistonTruth {
	/** One preview: what the piston will do next, as shown. */
	private record Prediction(boolean ok, boolean extending, String label, List<BlockPos> push, Map<BlockPos, BlockState> states,
			List<BlockPos> destroy) {
	}

	private final BlockPos min;
	private final BlockPos max;
	private final Set<PistonMovingBlockEntity> seen = Collections.newSetFromMap(new IdentityHashMap<>());
	/** Previews made at the last step (pistons that were still then). */
	private Map<BlockPos, Prediction> lastTick = new HashMap<>();
	/** What the player sees: the last preview of a still piston, kept while it moves. */
	private final Map<BlockPos, Prediction> shown = new HashMap<>();

	int moves;
	int checked;
	int unchecked;
	int uncheckedPulls;
	int stickyRetracts;
	int stickyPulled;
	int wrong;
	int shownChecked;
	int shownWrong;
	final List<String> problems = new ArrayList<>();
	final List<String> shownProblems = new ArrayList<>();

	PistonTruth(BlockPos min, BlockPos max) {
		this.min = min;
		this.max = max;
	}

	/** Forget everything (the build was replaced). */
	void reset() {
		this.seen.clear();
		this.lastTick = new HashMap<>();
		this.shown.clear();
	}

	void step(Level level, String where) {
		// Moving-piston blocks that appeared since the last step.
		List<PistonMovingBlockEntity> fresh = new ArrayList<>();
		Set<PistonMovingBlockEntity> now = Collections.newSetFromMap(new IdentityHashMap<>());
		for (BlockPos p : BlockPos.betweenClosed(this.min, this.max)) {
			if (level.getBlockState(p).is(Blocks.MOVING_PISTON) && level.getBlockEntity(p) instanceof PistonMovingBlockEntity be) {
				now.add(be);
				if (!this.seen.contains(be)) {
					fresh.add(be);
				}
			}
		}
		this.seen.clear();
		this.seen.addAll(now);

		// Which pistons moved, grouped by (direction, push or pull): blocks of pistons that move the
		// same way in the same tick can't be told apart, so their previews are added up.
		Map<String, Map<BlockPos, BlockState>> expected = new TreeMap<>();
		Map<String, Map<BlockPos, BlockState>> actual = new TreeMap<>();
		Map<String, List<BlockPos>> firedIn = new TreeMap<>();
		Map<String, Boolean> checkable = new HashMap<>();
		Map<BlockPos, String> groupOf = new LinkedHashMap<>();
		java.util.Set<String> stickyKeys = new java.util.HashSet<>();
		for (PistonMovingBlockEntity be : fresh) {
			String key = be.getDirection() + (be.isExtending() ? " push" : " pull");
			if (!be.isSourcePiston()) {
				actual.computeIfAbsent(key, k -> new TreeMap<>()).put(be.getBlockPos(), be.getMovedState());
				continue;
			}
			BlockPos piston = be.isExtending() ? be.getBlockPos().relative(be.getDirection().getOpposite()) : be.getBlockPos();
			this.moves++;
			firedIn.computeIfAbsent(key, k -> new ArrayList<>()).add(piston);
			groupOf.put(piston, key);
			Prediction p = this.lastTick.get(piston);
			if (p == null || p.extending() != be.isExtending()) {
				// It was moving at the last step, so there was no fresh preview.
				this.unchecked++;
				if (!be.isExtending()) {
					this.uncheckedPulls++;
					if (be.getMovedState().is(Blocks.STICKY_PISTON)) {
						stickyKeys.add(key);
						this.stickyRetracts++;
					}
				}
				checkable.put(key, false);
				continue;
			}
			checkable.putIfAbsent(key, true);
			if (!p.ok()) {
				this.wrong++;
				this.problem(this.problems, where + ": " + piston.toShortString() + " moved although the preview said '" + p.label() + "'");
				checkable.put(key, false);
				continue;
			}
			Direction move = be.isExtending() ? be.getDirection() : be.getDirection().getOpposite();
			Map<BlockPos, BlockState> exp = expected.computeIfAbsent(key, k -> new TreeMap<>());
			for (BlockPos b : p.push()) {
				exp.put(b.relative(move), p.states().get(b));
			}
			for (BlockPos d : p.destroy()) {
				if (level.getBlockState(d) == p.states().get(d)) {
					this.wrong++;
					this.problem(this.problems, where + ": " + piston.toShortString() + " preview said " + d.toShortString() + " breaks, it didn't");
				}
			}
		}
		for (String k : stickyKeys) {
			if (!actual.getOrDefault(k, Map.of()).isEmpty()) {
				this.stickyPulled++;
			}
		}
		for (Map.Entry<String, List<BlockPos>> group : firedIn.entrySet()) {
			String key = group.getKey();
			if (!checkable.getOrDefault(key, false)) {
				continue;
			}
			this.checked += group.getValue().size();
			Map<BlockPos, BlockState> exp = expected.getOrDefault(key, Map.of());
			Map<BlockPos, BlockState> act = actual.getOrDefault(key, Map.of());
			if (!exp.equals(act)) {
				this.wrong += group.getValue().size();
				this.problem(this.problems, where + ": " + group.getValue() + " " + key + ": preview " + names(exp) + ", game moved " + names(act));
			}
		}
		// The preview the player saw (kept from the last still tick) for pistons that moved alone.
		for (Map.Entry<BlockPos, String> e : groupOf.entrySet()) {
			Prediction p = this.shown.get(e.getKey());
			String key = e.getValue();
			if (p == null || firedIn.get(key).size() != 1 || p.extending() != key.endsWith("push")) {
				continue;
			}
			this.shownChecked++;
			Direction move = Direction.valueOf(key.substring(0, key.indexOf(' ')).toUpperCase(java.util.Locale.ROOT));
			move = p.extending() ? move : move.getOpposite();
			Map<BlockPos, BlockState> exp = new TreeMap<>();
			for (BlockPos b : p.push()) {
				exp.put(b.relative(move), p.states().get(b));
			}
			Map<BlockPos, BlockState> act = actual.getOrDefault(key, Map.of());
			if (!p.ok() || !exp.equals(act)) {
				this.shownWrong++;
				this.problem(this.shownProblems, where + ": " + e.getKey().toShortString() + " showed '" + p.label() + "' " + names(exp) + ", game moved " + names(act));
			}
		}

		// New previews for every still piston.
		Map<BlockPos, Prediction> next = new HashMap<>();
		for (BlockPos p : BlockPos.betweenClosed(this.min, this.max)) {
			BlockState state = level.getBlockState(p);
			if (!(state.getBlock() instanceof PistonBaseBlock) || PistonView.isMoving(level, p)) {
				continue;
			}
			BlockPos pos = p.immutable();
			Prediction pred = predict(level, pos, state);
			next.put(pos, pred);
			this.shown.put(pos, pred);
		}
		this.shown.keySet().removeIf(pos -> !(level.getBlockState(pos).getBlock() instanceof PistonBaseBlock) && !level.getBlockState(pos).is(Blocks.MOVING_PISTON));
		this.lastTick = next;
	}

	/** The preview, exactly as built for the player, read back from its markers. */
	static Prediction predict(Level level, BlockPos pos, BlockState state) {
		PistonView.View view = new PistonView.View(pos);
		List<MarkerData> markers = new ArrayList<>();
		PistonView.build(level, view, markers, false);
		TreeMap<Integer, BlockPos> numbered = new TreeMap<>();
		List<BlockPos> destroy = new ArrayList<>();
		Map<BlockPos, BlockState> states = new HashMap<>();
		for (MarkerData m : markers) {
			if (m.text().matches("\\d+")) {
				numbered.put(Integer.parseInt(m.text()), m.pos());
			} else if (m.text().equals("breaks")) {
				destroy.add(m.pos());
			} else {
				continue;
			}
			states.put(m.pos(), level.getBlockState(m.pos()));
		}
		return new Prediction(view.headLabel.startsWith("✔"), !state.getValue(BlockStateProperties.EXTENDED), view.headLabel,
				new ArrayList<>(numbered.values()), states, destroy);
	}

	private void problem(List<String> list, String text) {
		if (list.size() < 5) {
			list.add(text);
		}
	}

	private static String names(Map<BlockPos, BlockState> blocks) {
		List<String> out = new ArrayList<>();
		blocks.forEach((p, s) -> out.add(p.toShortString() + " " + s.getBlock().getName().getString()));
		return out.toString();
	}

	String summary() {
		return this.moves + " piston moves, " + this.checked + " checked against the preview from the tick before (" + this.wrong
				+ " wrong, " + this.unchecked + " moved again before it was still, " + this.uncheckedPulls + " of them retracting; sticky: " + this.stickyRetracts + ", of which pulled blocks: " + this.stickyPulled + "), " + this.shownChecked
				+ " checked against the preview on screen (" + this.shownWrong + " wrong)";
	}
}
