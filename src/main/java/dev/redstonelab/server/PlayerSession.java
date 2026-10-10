package dev.redstonelab.server;

import java.util.UUID;

import dev.redstonelab.net.LabPayloads;

/**
 * Per-player state that is not owned by one specific feature.
 */
public final class PlayerSession {
	public final UUID uuid;
	/** True once the client completed the handshake (client has the mod installed). */
	public boolean modded;
	public int capabilities;
	public String clientVersion = "";

	public PlayerSession(UUID uuid) {
		this.uuid = uuid;
	}

	/** Client draws in-world labels/outlines itself; no display entities needed. */
	public boolean rendersWorld() {
		return this.modded && (this.capabilities & LabPayloads.CAP_WORLD) != 0;
	}

	/** Client draws the meter timeline HUD. */
	public boolean rendersHud() {
		return this.modded && (this.capabilities & LabPayloads.CAP_HUD) != 0;
	}

}
