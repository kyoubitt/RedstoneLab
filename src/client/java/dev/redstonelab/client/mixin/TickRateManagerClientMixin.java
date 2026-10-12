package dev.redstonelab.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.world.TickRateManager;

import dev.redstonelab.client.SlowWorld;

/**
 * In slow motion the client still ticks 20 times a second (the player moves normally), but the
 * world on screen (pistons moving, mobs, items, day time) only advances as often as the game does:
 * at 5 ticks a second, every 4th client tick. Same switch the game uses when frozen, so players
 * keep moving and everything else waits; in between, the world is drawn sliding smoothly.
 */
@Mixin(TickRateManager.class)
public abstract class TickRateManagerClientMixin implements SlowWorld {
	@Shadow
	protected float tickrate;
	@Shadow
	protected boolean runGameElements;
	@Shadow
	protected boolean isFrozen;

	/** How far the world is towards its next tick: grows by rate / 20 each client tick. */
	@Unique
	private float redstonelab$due;

	@Inject(method = "tick", at = @At("TAIL"))
	private void redstonelab$worldAtGameSpeed(CallbackInfo ci) {
		if (!this.redstonelab$slow()) {
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

	@Override
	public boolean redstonelab$slow() {
		// Only the client's own copy (the server's is a ServerTickRateManager).
		return ((Object) this).getClass() == TickRateManager.class && !this.isFrozen && this.tickrate < 20;
	}

	@Override
	public void redstonelab$hold() {
		if (this.redstonelab$slow()) {
			this.runGameElements = false;
		}
	}

	@Override
	public float redstonelab$partial(float clientPartial) {
		return Math.min(1, this.redstonelab$due + clientPartial * this.tickrate / 20);
	}
}
