package dev.redstonelab.mixin;

import java.util.ArrayList;
import java.util.List;

import com.llamalad7.mixinextras.sugar.Local;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.piston.PistonStructureResolver;
import net.minecraft.world.level.block.state.BlockState;

import dev.redstonelab.capture.Capture;
import dev.redstonelab.feature.MeterFeature;
import dev.redstonelab.feature.PistonFires;

/** Meters move along with the blocks a piston pushes or pulls (like Redstone Multimeter); piston firing order. */
@Mixin(PistonBaseBlock.class)
public abstract class PistonBaseBlockMixin {
	@Unique
	private static List<BlockPos> redstonelab$moving;
	@Unique
	private static Direction redstonelab$direction;

	@Inject(method = "moveBlocks", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/level/block/piston/PistonStructureResolver;getToPush()Ljava/util/List;"))
	private void redstonelab$beforeMove(Level level, BlockPos pos, Direction facing, boolean extending, CallbackInfoReturnable<Boolean> cir,
	                                    @Local PistonStructureResolver resolver) {
		if (Capture.watching && level instanceof ServerLevel) {
			redstonelab$moving = new ArrayList<>(resolver.getToPush());
			redstonelab$direction = resolver.getPushDirection();
			MeterFeature.beginMove(redstonelab$moving);
		}
	}

	@Inject(method = "moveBlocks", at = @At("RETURN"))
	private void redstonelab$afterMove(Level level, BlockPos pos, Direction facing, boolean extending, CallbackInfoReturnable<Boolean> cir) {
		List<BlockPos> moving = redstonelab$moving;
		if (moving == null || !(level instanceof ServerLevel serverLevel)) {
			return;
		}
		redstonelab$moving = null;
		// Always called (also when the piston could not move) so the "moving" list is cleared.
		MeterFeature.onBlocksMoved(serverLevel, cir.getReturnValueZ() ? moving : List.of(), redstonelab$direction);
	}

	/** A piston started extending (on the server): for the firing order of shown pistons. */
	@Inject(method = "triggerEvent", at = @At("RETURN"))
	private void redstonelab$extended(BlockState state, Level level, BlockPos pos, int type, int data, CallbackInfoReturnable<Boolean> cir) {
		if (type == 0 && cir.getReturnValueZ() && level instanceof ServerLevel serverLevel) {
			PistonFires.onExtend(serverLevel, pos);
		}
	}
}
