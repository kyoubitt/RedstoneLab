package dev.redstonelab.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.world.TickRateManager;

import dev.redstonelab.client.ClientState;
import dev.redstonelab.client.SlowWorld;
import dev.redstonelab.client.WorldTicks;

/**
 * In slow motion the client still ticks 20 times a second (the player moves normally), but the
 * world on screen (pistons moving, mobs, items, day time) only advances as often as the game does:
 * at 5 ticks a second, every 4th client tick. Same switch the game uses when frozen, so players
 * keep moving and everything else waits; in between, the world is drawn sliding smoothly. Only
 * when and how the client's copy of the world is drawn changes: the game's piston logic and
 * progress steps stay vanilla (so Lithium's piston shape cache holds).
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
		boolean client = ((Object) this).getClass() == TickRateManager.class;
		if (client && this.isFrozen && ClientState.serverHasMod) {
			// Frozen, RedstoneLab on the server: step when the server's step tick is done, so its
			// piston moves are already here and slide (the game's own step message comes before them).
			this.runGameElements = WorldTicks.take();
		}
		if (client && this.isFrozen && this.runGameElements) {
			// Stepping one tick: it slides into place instead of popping.
			WorldTicks.onStep();
		}
		if (!this.redstonelab$paced()) {
			this.redstonelab$due = 0;
			if (client && !this.isFrozen) {
				// Normal speed: the world ticks every client tick; signals only steer the clock.
				WorldTicks.clearPending();
			}
			return;
		}
		if (WorldTicks.synced()) {
			// RedstoneLab on the server: one step per server tick, in step with its block updates.
			if (!WorldTicks.take()) {
				this.runGameElements = false;
			}
			return;
		}
		this.redstonelab$due += this.tickrate / 20;
		if (this.redstonelab$due >= 1) {
			this.redstonelab$due -= 1;
		} else {
			this.runGameElements = false;
		}
	}

	@Inject(method = "setFrozen", at = @At("HEAD"))
	private void redstonelab$freezeChanged(boolean frozen, CallbackInfo ci) {
		if (((Object) this).getClass() == TickRateManager.class && frozen != this.isFrozen) {
			WorldTicks.clearPending();
		}
	}

	@Override
	public boolean redstonelab$slow() {
		// Only the client's own copy (the server's is a ServerTickRateManager).
		return ((Object) this).getClass() == TickRateManager.class && !this.isFrozen && this.tickrate < 20;
	}

	/**
	 * The client's world moves on its own pace, not every client tick: slow motion. (At normal
	 * speed it ticks every client tick like the game does; smooth pistons keep the client's tick
	 * clock in step with the server's instead, see {@link dev.redstonelab.client.TickClock}.)
	 */
	@Unique
	private boolean redstonelab$paced() {
		return this.redstonelab$slow();
	}

	@Override
	public boolean redstonelab$smooth() {
		return this.redstonelab$paced() || ((Object) this).getClass() == TickRateManager.class && this.isFrozen && WorldTicks.stepping(this.tickrate);
	}

	@Override
	public void redstonelab$hold() {
		if (this.redstonelab$paced() || this.isFrozen && ((Object) this).getClass() == TickRateManager.class) {
			this.runGameElements = false;
		}
	}

	@Override
	public float redstonelab$partial(float clientPartial) {
		if (this.isFrozen) {
			return WorldTicks.stepPartial(this.tickrate);
		}
		if (WorldTicks.synced()) {
			return WorldTicks.partial(this.tickrate);
		}
		return Math.min(1, this.redstonelab$due + clientPartial * this.tickrate / 20);
	}
}
