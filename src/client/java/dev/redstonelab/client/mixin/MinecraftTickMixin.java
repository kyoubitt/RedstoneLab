package dev.redstonelab.client.mixin;

import org.spongepowered.asm.mixin.Mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import org.spongepowered.asm.mixin.injection.At;

import net.minecraft.client.Minecraft;

import dev.redstonelab.client.ui.WorldMarkers;

@Mixin(Minecraft.class)
public abstract class MinecraftTickMixin {
	/** Game frozen (vanilla /tick freeze): still show the newest meter outlines and piston previews. */
	@ModifyExpressionValue(method = "runTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/TickRateManager;runsNormally()Z"))
	private boolean redstonelab$refreshOutlines(boolean runsNormally) {
		return runsNormally || WorldMarkers.needsRefresh();
	}
}
