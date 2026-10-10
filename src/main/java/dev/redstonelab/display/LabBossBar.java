package dev.redstonelab.display;

import java.util.UUID;

import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBossEventPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;

/**
 * A boss bar that exists only as packets for one player (vanilla fallback for the
 * oscilloscope / pulse meter summary). Nothing is registered on the server, so nothing can
 * be left behind.
 */
public final class LabBossBar extends BossEvent {
	private boolean shown;
	private Component lastName = Component.empty();
	private float lastProgress = -1;

	public LabBossBar(Component name, BossBarColor color) {
		super(UUID.randomUUID(), name, color, BossBarOverlay.PROGRESS);
	}

	public void update(ServerPlayer player, Component name, float progress) {
		this.setName(name);
		this.setProgress(Math.max(0f, Math.min(1f, progress)));
		if (!this.shown) {
			player.connection.send(ClientboundBossEventPacket.createAddPacket(this));
			this.shown = true;
		} else {
			if (!name.equals(this.lastName)) {
				player.connection.send(ClientboundBossEventPacket.createUpdateNamePacket(this));
			}
			if (this.getProgress() != this.lastProgress) {
				player.connection.send(ClientboundBossEventPacket.createUpdateProgressPacket(this));
			}
		}
		this.lastName = name;
		this.lastProgress = this.getProgress();
	}

	public void hide(ServerPlayer player) {
		if (this.shown) {
			player.connection.send(ClientboundBossEventPacket.createRemovePacket(this.getId()));
			this.shown = false;
		}
	}

	public static BossBarColor colorFor(int channel) {
		BossBarColor[] colors = {BossBarColor.RED, BossBarColor.BLUE, BossBarColor.GREEN, BossBarColor.YELLOW,
				BossBarColor.PURPLE, BossBarColor.PINK, BossBarColor.WHITE, BossBarColor.YELLOW};
		return colors[Math.floorMod(channel, colors.length)];
	}
}
