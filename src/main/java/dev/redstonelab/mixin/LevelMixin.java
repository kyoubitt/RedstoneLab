package dev.redstonelab.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import dev.redstonelab.capture.Capture;
import dev.redstonelab.capture.PowerWatch;

/**
 * Block changes and container changes near a block with a meter on it.
 */
@Mixin(Level.class)
public abstract class LevelMixin {
	@Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z", at = @At("RETURN"))
	private void redstonelab$setBlock(BlockPos pos, BlockState state, int flags, int maxUpdateDepth, CallbackInfoReturnable<Boolean> cir) {
		if (Capture.watching && cir.getReturnValueZ()) {
			PowerWatch.onBlockChanged((Level) (Object) this, pos);
		}
	}

	@Inject(method = "blockEntityChanged", at = @At("HEAD"), require = 0)
	private void redstonelab$blockEntityChanged(BlockPos pos, CallbackInfo ci) {
		if (Capture.watching) {
			PowerWatch.onBlockEntityChanged((Level) (Object) this, pos);
		}
	}
}
