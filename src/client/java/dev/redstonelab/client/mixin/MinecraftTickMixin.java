package dev.redstonelab.client.mixin;

import org.spongepowered.asm.mixin.Mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.client.Minecraft;

import dev.redstonelab.client.SlowWorld;
import dev.redstonelab.client.ui.WorldMarkers;

/**
 * Slow motion (/tick rate below 20) doesn't slow the player: the client keeps 20 ticks a second.
 * The world on screen still runs at the game's speed, see {@link TickRateManagerClientMixin}.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftTickMixin {
	@Inject(method = "getTickTargetMillis", at = @At("HEAD"), cancellable = true)
	private void redstonelab$playerFullSpeed(float normal, CallbackInfoReturnable<Float> cir) {
		cir.setReturnValue(normal);
	}

	/** Frozen or slow: still show the newest meter outlines and piston previews (not stale ones). */
	@ModifyExpressionValue(method = "runTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/TickRateManager;runsNormally()Z"))
	private boolean redstonelab$refreshOutlines(boolean runsNormally) {
		return runsNormally || WorldMarkers.needsRefresh();
	}

	/** Between client ticks the slow world waits, so it's drawn with its own smooth timing. */
	@Inject(method = "tick", at = @At("TAIL"))
	private void redstonelab$holdSlowWorld(CallbackInfo ci) {
		Minecraft mc = (Minecraft) (Object) this;
		if (mc.level != null) {
			((SlowWorld) mc.level.tickRateManager()).redstonelab$hold();
		}
	}
}
