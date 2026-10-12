package dev.redstonelab.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.world.TickRateManager;

/**
 * In slow motion the client still ticks 20 times a second (the player moves normally), but the
 * world on screen (pistons moving, mobs, items, day time) only advances as often as the game does:
 * at 5 ticks a second, every 4th client tick. Same switch the game uses when frozen, so players
 * keep moving and everything else waits.
 */
@Mixin(TickRateManager.class)
public abstract class TickRateManagerClientMixin {
	@Shadow
	protected float tickrate;
	@Shadow
	protected boolean runGameElements;
	@Shadow
	protected boolean isFrozen;

	/** Game ticks owed to the world: grows by rate / 20 each client tick. */
	@Unique
	private float redstonelab$due;

	@Inject(method = "tick", at = @At("TAIL"))
	private void redstonelab$worldAtGameSpeed(CallbackInfo ci) {
		// Only the client's own copy (the server's is a ServerTickRateManager).
		if (((Object) this).getClass() != TickRateManager.class || this.isFrozen || this.tickrate >= 20) {
			this.redstonelab$due = 0;
			return;
		}
		this.redstonelab$due += this.tickrate / 20;
		if (this.redstonelab$due >= 1) {
			this.redstonelab$due -= 1;
		} else {
			this.runGameElements = false;
		}
	}
}
