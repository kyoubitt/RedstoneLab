package dev.redstonelab.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.RedstoneTorchBlock;
import net.minecraft.world.level.block.state.BlockState;

/** The torch's own "is my block powered" check (wall torches override it), for torch meters. */
@Mixin(RedstoneTorchBlock.class)
public interface RedstoneTorchBlockInvoker {
	@Invoker("hasNeighborSignal")
	boolean redstonelab$isPowered(Level level, BlockPos pos, BlockState state);
}
