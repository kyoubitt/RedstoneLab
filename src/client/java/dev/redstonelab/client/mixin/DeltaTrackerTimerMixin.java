package dev.redstonelab.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;

import dev.redstonelab.client.SlowWorld;

/**
 * In slow motion the world (pistons, mobs, items) is drawn sliding from its last tick to its
 * next one over several client ticks, instead of jumping. The player and camera keep the normal
 * timing (they ask with {@code runsNormally} = true).
 */
@Mixin(DeltaTracker.Timer.class)
public abstract class DeltaTrackerTimerMixin {
	@Shadow
	private float deltaTickResidual;
	@Shadow
	private float pausedDeltaTickResidual;
	@Shadow
	private boolean paused;

	@Inject(method = "getGameTimeDeltaPartialTick", at = @At("HEAD"), cancellable = true)
	private void redstonelab$smoothSlowWorld(boolean runsNormally, CallbackInfoReturnable<Float> cir) {
		if (runsNormally) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc == null || mc.level == null || mc.getDeltaTracker() != (Object) this) {
			return;
		}
		SlowWorld world = (SlowWorld) mc.level.tickRateManager();
		if (world.redstonelab$slow()) {
			cir.setReturnValue(world.redstonelab$partial(this.paused ? this.pausedDeltaTickResidual : this.deltaTickResidual));
		}
	}
}
