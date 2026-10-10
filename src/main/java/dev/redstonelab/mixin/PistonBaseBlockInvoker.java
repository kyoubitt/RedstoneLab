package dev.redstonelab.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.SignalGetter;
import net.minecraft.world.level.block.piston.PistonBaseBlock;

/** The piston's own "am I powered" check (includes QC), for piston meters. */
@Mixin(PistonBaseBlock.class)
public interface PistonBaseBlockInvoker {
	@Invoker("getNeighborSignal")
	boolean redstonelab$isPowered(SignalGetter level, BlockPos pos, Direction facing);
}
