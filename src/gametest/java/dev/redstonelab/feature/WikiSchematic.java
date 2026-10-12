package dev.redstonelab.feature;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.ComparatorMode;
import net.minecraft.world.level.block.state.properties.PistonType;
import net.minecraft.world.level.block.state.properties.RedstoneSide;

/**
 * Builds a flat (one level, seen from the top) Minecraft Wiki {@code {{Schematic}}} exactly as
 * written on the wiki: cells separated by "|", "-" starts a new row; column = x (east), row = z
 * (south). Sprite ids as the wiki uses them: arrows point where a part outputs (repeaters,
 * comparators, observers), where a piston / hopper faces, and torches point at the block they
 * hang on. Input / output wool only marks a spot; "!" (powered) is left to the circuit.
 */
final class WikiSchematic {
	/** Where the cells with a label ("input", "output", "T", "Q"...) ended up. */
	final Map<String, List<BlockPos>> marks = new HashMap<>();
	final List<BlockPos> placed = new ArrayList<>();

	private WikiSchematic() {
	}

	/** Builds {@code schematic} with its north-west cell at {@code origin} (relative to the test). */
	static WikiSchematic build(GameTestHelper helper, BlockPos origin, String schematic) {
		WikiSchematic s = new WikiSchematic();
		ServerLevel level = helper.getLevel();
		String body = schematic.replaceAll("\\s+", "");
		if (body.startsWith("|")) {
			body = body.substring(1);
		}
		int x = 0;
		int z = 0;
		List<BlockPos> dust = new ArrayList<>();
		for (String cell : body.split("\\|", -1)) {
			if (cell.equals("-")) {
				x = 0;
				z++;
				continue;
			}
			BlockPos pos = origin.offset(x, 0, z);
			for (String part : cell.split("\\+")) {
				String id = part.replace("$", "");
				String bare = id.replace("!", "");
				if (bare.equals("input") || bare.equals("output") || bare.equals("duplex") || bare.length() == 1 && Character.isUpperCase(bare.charAt(0))) {
					s.marks.computeIfAbsent(bare, k -> new ArrayList<>()).add(pos);
					continue;
				}
				BlockState state = state(id);
				if (state == null) {
					continue;
				}
				level.setBlock(helper.absolutePos(pos), state, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
				s.placed.add(pos);
				if (state.is(Blocks.REDSTONE_WIRE)) {
					dust.add(pos);
				}
			}
			x++;
		}
		// Dust connects like a player placed it.
		for (BlockPos p : dust) {
			BlockPos abs = helper.absolutePos(p);
			level.setBlock(abs, dust(level, abs), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
		}
		return s;
	}

	/** Dust shaped by its neighbours, as a player would place it (alone it is a cross, not a dot). */
	static BlockState dust(ServerLevel level, BlockPos abs) {
		BlockState cross = Blocks.REDSTONE_WIRE.defaultBlockState()
				.setValue(RedStoneWireBlock.NORTH, RedstoneSide.SIDE).setValue(RedStoneWireBlock.SOUTH, RedstoneSide.SIDE)
				.setValue(RedStoneWireBlock.EAST, RedstoneSide.SIDE).setValue(RedStoneWireBlock.WEST, RedstoneSide.SIDE);
		return Block.updateFromNeighbourShapes(cross, level, abs);
	}

	/** Lets every placed block react to its neighbours once (power settles, like after building). */
	void settle(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		for (BlockPos p : this.placed) {
			BlockPos abs = helper.absolutePos(p);
			level.getBlockState(abs).handleNeighborChanged(level, abs, Blocks.AIR, null, false);
		}
	}

	BlockPos mark(String label) {
		return this.marks.get(label).get(0);
	}

	private static Direction dir(String id) {
		int dash = id.indexOf('-');
		if (dash < 0) {
			return null;
		}
		for (char c : id.substring(dash + 1).toCharArray()) {
			switch (c) {
				case 'n': return Direction.NORTH;
				case 's': return Direction.SOUTH;
				case 'e': return Direction.EAST;
				case 'w': return Direction.WEST;
				case 'u': return Direction.UP;
				case 'd': return Direction.DOWN;
				default: break;
			}
		}
		return null;
	}

	private static int number(String id) {
		String digits = id.replaceAll("[^0-9]", "");
		return digits.isEmpty() ? 1 : Integer.parseInt(digits);
	}

	/** The block for a wiki sprite id, or null for empty / text cells. */
	static BlockState state(String id) {
		String kind = id.replace("!", "");
		kind = kind.contains("-") ? kind.substring(0, kind.indexOf('-')) : kind;
		Direction d = dir(id);
		switch (kind) {
			case "SB": case "AB": case "MB": case "GB": case "gt":
				return kind.equals("GB") ? Blocks.GLASS.defaultBlockState() : Blocks.STONE.defaultBlockState();
			case "BR": return Blocks.REDSTONE_BLOCK.defaultBlockState();
			case "SL": return Blocks.SLIME_BLOCK.defaultBlockState();
			case "honey": return Blocks.HONEY_BLOCK.defaultBlockState();
			case "obsidian": return Blocks.OBSIDIAN.defaultBlockState();
			case "rd": return Blocks.REDSTONE_WIRE.defaultBlockState();
			case "rr": case "lr":
				return Blocks.REPEATER.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, d.getOpposite())
						.setValue(BlockStateProperties.DELAY, number(id));
			case "rc": case "rs":
				return Blocks.COMPARATOR.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, d.getOpposite())
						.setValue(BlockStateProperties.MODE_COMPARATOR, kind.equals("rs") ? ComparatorMode.SUBTRACT : ComparatorMode.COMPARE);
			case "rt":
				return d == null ? Blocks.REDSTONE_TORCH.defaultBlockState()
						: Blocks.REDSTONE_WALL_TORCH.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, d.getOpposite());
			case "obs": return Blocks.OBSERVER.defaultBlockState().setValue(BlockStateProperties.FACING, d.getOpposite());
			case "ho": return Blocks.HOPPER.defaultBlockState().setValue(BlockStateProperties.FACING_HOPPER, d == null ? Direction.DOWN : d);
			case "sp": case "pi":
				return (kind.equals("sp") ? Blocks.STICKY_PISTON : Blocks.PISTON).defaultBlockState().setValue(BlockStateProperties.FACING, d)
						.setValue(BlockStateProperties.EXTENDED, id.endsWith("!"));
			case "se": case "pe":
				return Blocks.PISTON_HEAD.defaultBlockState().setValue(BlockStateProperties.FACING, d)
						.setValue(BlockStateProperties.PISTON_TYPE, kind.equals("se") ? PistonType.STICKY : PistonType.DEFAULT);
			case "lamp": return Blocks.REDSTONE_LAMP.defaultBlockState();
			default: return null;
		}
	}
}
