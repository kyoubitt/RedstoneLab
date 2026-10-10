package dev.redstonelab.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

import dev.redstonelab.capture.Capture;
import dev.redstonelab.capture.PowerWatch;

/**
 * A block's own change, right when the new state is stored and before it updates its
 * neighbors (onPlace / removal), so it is listed before what it causes.
 */
@Mixin(LevelChunk.class)
public abstract class LevelChunkMixin {
	@Shadow
	@Final
	Level level;

	@Inject(method = "setBlockState", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/level/chunk/LevelChunkSection;setBlockState(IIILnet/minecraft/world/level/block/state/BlockState;)Lnet/minecraft/world/level/block/state/BlockState;",
			shift = At.Shift.AFTER))
	private void redstonelab$stateStored(BlockPos pos, BlockState state, int flags, CallbackInfoReturnable<BlockState> cir) {
		// Blocks with a block entity are read at the end of setBlock instead (the entity is not set up yet here).
		if (Capture.watching && !state.hasBlockEntity()) {
			PowerWatch.onStateStored(this.level, pos);
		}
	}
}
