package dev.redstonelab.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;

import dev.redstonelab.client.ui.LabConfig;

/**
 * Like G4mespeed: in slow motion (and fast forward) the world's sounds play lower (higher), to
 * match the game's speed. The player's own sounds, music and menus stay normal.
 */
@Mixin(SoundEngine.class)
public abstract class SoundEngineMixin {
	@Inject(method = "calculatePitch", at = @At("RETURN"), cancellable = true)
	private void redstonelab$pitchFollowsSpeed(SoundInstance sound, CallbackInfoReturnable<Float> cir) {
		Minecraft mc = Minecraft.getInstance();
		if (!LabConfig.SOUND_PITCH.value || mc.level == null || mc.level.tickRateManager().isFrozen()) {
			return;
		}
		float rate = mc.level.tickRateManager().tickrate();
		SoundSource source = sound.getSource();
		boolean world = source == SoundSource.BLOCKS || source == SoundSource.HOSTILE || source == SoundSource.NEUTRAL
				|| source == SoundSource.RECORDS || source == SoundSource.WEATHER || source == SoundSource.AMBIENT;
		if (world && Math.abs(rate - 20) > 0.01f) {
			cir.setReturnValue(Mth.clamp(cir.getReturnValueF() * rate / 20, 0.5f, 2.0f));
		}
	}
}
