package dev.redstonelab.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;

import dev.redstonelab.client.ClientPistons;

/** Piston moves arrive from the server in the order the game ran them: the firing order. */
@Mixin(PistonBaseBlock.class)
public abstract class PistonBaseBlockClientMixin {
	@Inject(method = "triggerEvent", at = @At("HEAD"))
	private void redstonelab$extending(BlockState state, Level level, BlockPos pos, int type, int data, CallbackInfoReturnable<Boolean> cir) {
		if (type == 0 && level.isClientSide()) {
			ClientPistons.onClientExtend(pos);
		}
	}
}
