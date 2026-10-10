package dev.redstonelab.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ComparatorBlockEntity;

import dev.redstonelab.capture.Capture;
import dev.redstonelab.capture.PowerWatch;

/**
 * Comparator output lives in the block entity, not the block state, so setBlock alone does
 * not see it change.
 */
@Mixin(ComparatorBlockEntity.class)
public abstract class ComparatorBlockEntityMixin {
	@Inject(method = "setOutputSignal", at = @At("RETURN"), require = 0)
	private void redstonelab$outputChanged(int signal, CallbackInfo ci) {
		if (!Capture.watching) {
			return;
		}
		BlockEntity self = (BlockEntity) (Object) this;
		if (self.getLevel() != null) {
			PowerWatch.onBlockChanged(self.getLevel(), self.getBlockPos());
		}
	}
}
