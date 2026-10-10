package dev.redstonelab.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.input.KeyEvent;

import dev.redstonelab.client.ui.LabKeys;

/** Key combos: a combo that fires keeps its key from the game (e.g. P won't open social interactions). */
@Mixin(KeyboardHandler.class)
public abstract class KeyboardHandlerMixin {
	@Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
	private void redstonelab$keyPress(long window, int action, KeyEvent event, CallbackInfo ci) {
		if (LabKeys.onKey(event.key(), action)) {
			ci.cancel();
		}
	}
}
