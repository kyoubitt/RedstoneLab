package dev.redstonelab.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.redstone.Orientation;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.BlockHitResult;

import dev.redstonelab.capture.Capture;
import dev.redstonelab.capture.PowerWatch;
import dev.redstonelab.core.MeterEvent;

/**
 * Block updates and shape updates reaching a block with a meter on it.
 */
@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class BlockStateBaseMixin {
	@Inject(method = "handleNeighborChanged", at = @At("HEAD"), require = 0)
	private void redstonelab$neighborChanged(Level level, BlockPos pos, Block neighborBlock, @Nullable Orientation orientation,
	                                         boolean movedByPiston, CallbackInfo ci) {
		if (Capture.watching) {
			PowerWatch.onEvent(level, pos, MeterEvent.BLOCK_UPDATE);
		}
	}

	/*
	 * updateShape(LevelReader, ScheduledTickAccess, BlockPos pos, Direction, BlockPos neighborPos, BlockState, RandomSource).
	 * Arguments are taken by type with MixinExtras @Local so the handler does not depend on the exact parameter list.
	 */
	@Inject(method = "updateShape", at = @At("HEAD"), require = 0)
	private void redstonelab$updateShape(CallbackInfoReturnable<BlockState> cir,
	                                     @Local(argsOnly = true) LevelReader level,
	                                     @Local(argsOnly = true, ordinal = 0) BlockPos pos) {
		if (Capture.watching && level instanceof Level l) {
			PowerWatch.onEvent(l, pos, MeterEvent.SHAPE_UPDATE);
		}
	}

	/** A player right-clicked the block (lever, button, repeater, door...). */
	@Inject(method = "useWithoutItem", at = @At("HEAD"), require = 0)
	private void redstonelab$use(Level level, Player player, BlockHitResult hit, CallbackInfoReturnable<InteractionResult> cir) {
		if (Capture.watching) {
			PowerWatch.onEvent(level, hit.getBlockPos(), MeterEvent.CLICKED);
		}
	}
}
