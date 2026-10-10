package dev.redstonelab.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.RedstoneTorchBlock;
import net.minecraft.world.level.block.state.BlockState;

/** Torch checks for torch meters: its own "is my block powered" (wall torches override it) and burnout. */
@Mixin(RedstoneTorchBlock.class)
public interface RedstoneTorchBlockInvoker {
	@Invoker("hasNeighborSignal")
	boolean redstonelab$isPowered(Level level, BlockPos pos, BlockState state);

	/** The game's burnout check: 8 toggles within 60 game ticks ({@code add} = false: only count). */
	@Invoker("isToggledTooFrequently")
	static boolean redstonelab$toggledTooOften(Level level, BlockPos pos, boolean add) {
		throw new AssertionError();
	}
}
