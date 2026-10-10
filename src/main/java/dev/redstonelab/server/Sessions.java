package dev.redstonelab.server;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.server.level.ServerPlayer;

public final class Sessions {
	private static final Map<UUID, PlayerSession> SESSIONS = new HashMap<>();

	private Sessions() {
	}

	public static PlayerSession get(ServerPlayer player) {
		return SESSIONS.computeIfAbsent(player.getUUID(), PlayerSession::new);
	}

	public static void remove(UUID uuid) {
		SESSIONS.remove(uuid);
	}

	public static void clear() {
		SESSIONS.clear();
	}
}
