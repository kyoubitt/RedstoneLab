package dev.redstonelab.client.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;

import dev.redstonelab.client.SlowWorld;
import dev.redstonelab.client.TickClock;

/**
 * In slow motion the world (pistons, mobs, items) is drawn sliding from its last tick to its
 * next one over several client ticks, instead of jumping. The player and camera keep the normal
 * timing (they ask with {@code runsNormally} = true).
 */
@Mixin(DeltaTracker.Timer.class)
public abstract class DeltaTrackerTimerMixin implements TickClock {
	@Shadow
	private float deltaTickResidual;
	@Shadow
	private float pausedDeltaTickResidual;
	@Shadow
	private boolean paused;
	@Shadow
	private long lastMs;
	@Shadow
	@Final
	private float msPerTick;

	/** Client ticks the current frame runs (they run after this frame's packets, the server's signal among them). */
	@Unique
	private int redstonelab$ticksThisFrame;

	@Inject(method = "advanceTime", at = @At("RETURN"))
	private void redstonelab$countTicks(long ms, boolean advanceGameTime, CallbackInfoReturnable<Integer> cir) {
		this.redstonelab$ticksThisFrame = cir.getReturnValueI();
	}

	@Override
	public void redstonelab$alignToServerTick() {
		if (this.paused) {
			return;
		}
		float tick = this.msPerTick;
		// When the client's next tick comes after this signal: in this frame (right after it), or later.
		float untilNext = this.redstonelab$ticksThisFrame > 0 ? 0
				: (1 - this.deltaTickResidual) * tick - (Util.getMillis() - this.lastMs);
		// Aim for half a tick after the server's tick; move a tenth of the way each time (gentle,
		// at most 1/20 of a tick: the player never notices), so the two clocks lock and stay locked.
		float error = untilNext - tick / 2;
		this.deltaTickResidual = Mth.clamp(this.deltaTickResidual + 0.1f * error / tick, 0f, 0.999f);
	}

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
		if (world.redstonelab$smooth()) {
			cir.setReturnValue(world.redstonelab$partial(this.paused ? this.pausedDeltaTickResidual : this.deltaTickResidual));
		}
	}
}
