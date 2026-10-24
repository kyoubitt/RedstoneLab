package dev.redstonelab.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockEventData;
import net.minecraft.world.level.block.Block;

import dev.redstonelab.capture.Capture;
import dev.redstonelab.capture.PowerWatch;
import dev.redstonelab.core.MeterEvent;
import dev.redstonelab.core.TickPhase;

/** Scheduled ticks and block events at a metered block, and which part of the tick is running. */
@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin {
	// ------------------------------------------------------------------ tick phases

	@Inject(method = "tick", at = @At("HEAD"))
	private void redstonelab$tickStart(CallbackInfo ci) {
		TickPhase.current = TickPhase.TICK_START;
	}

	@Inject(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/ticks/LevelTicks;tick(JILjava/util/function/BiConsumer;)V", ordinal = 0))
	private void redstonelab$blockTicks(CallbackInfo ci) {
		TickPhase.current = TickPhase.BLOCK_TICKS;
	}

	@Inject(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/ticks/LevelTicks;tick(JILjava/util/function/BiConsumer;)V", ordinal = 1))
	private void redstonelab$fluidTicks(CallbackInfo ci) {
		TickPhase.current = TickPhase.FLUID_TICKS;
	}

	@Inject(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerChunkCache;tick(Ljava/util/function/BooleanSupplier;Z)V"))
	private void redstonelab$chunks(CallbackInfo ci) {
		TickPhase.current = TickPhase.CHUNKS;
	}

	@Inject(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;runBlockEvents()V"))
	private void redstonelab$blockEvents(CallbackInfo ci) {
		TickPhase.current = TickPhase.BLOCK_EVENTS;
	}

	@Inject(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/entity/EntityTickList;forEach(Ljava/util/function/Consumer;)V"))
	private void redstonelab$entities(CallbackInfo ci) {
		TickPhase.current = TickPhase.ENTITIES;
	}

	@Inject(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;tickBlockEntities()V"))
	private void redstonelab$blockEntities(CallbackInfo ci) {
		TickPhase.current = TickPhase.BLOCK_ENTITIES;
	}

	@Inject(method = "tick", at = @At("RETURN"))
	private void redstonelab$tickEnd(CallbackInfo ci) {
		TickPhase.current = TickPhase.PLAYERS;
	}

	// ------------------------------------------------------------------ events

	@Inject(method = "tickBlock", at = @At("HEAD"), require = 0)
	private void redstonelab$tickBlock(BlockPos pos, Block block, CallbackInfo ci) {
		if (Capture.watching) {
			PowerWatch.onEvent((ServerLevel) (Object) this, pos, MeterEvent.SCHEDULED_TICK);
		}
	}

	@Inject(method = "doBlockEvent", at = @At("HEAD"), require = 0)
	private void redstonelab$doBlockEvent(BlockEventData event, CallbackInfoReturnable<Boolean> cir) {
		if (Capture.watching) {
			PowerWatch.onEvent((ServerLevel) (Object) this, event.pos(), MeterEvent.BLOCK_EVENT);
		}
	}

	/** Only events the game accepted: a piston that can't move (or no longer should) is skipped. */
	@Inject(method = "doBlockEvent", at = @At("RETURN"), require = 0)
	private void redstonelab$blockEventDone(BlockEventData event, CallbackInfoReturnable<Boolean> cir) {
		if (!cir.getReturnValueZ()) {
			return;
		}
		if (Capture.watching) {
			PowerWatch.onBlockEvent((ServerLevel) (Object) this, event.pos(), event.paramA());
		}
	}
}
