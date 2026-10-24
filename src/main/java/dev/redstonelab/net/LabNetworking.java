package dev.redstonelab.net;

import java.util.Set;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import dev.redstonelab.RedstoneLab;
import dev.redstonelab.display.MarkerService;
import dev.redstonelab.feature.PistorderFeature;
import dev.redstonelab.server.LabServer;
import dev.redstonelab.server.PlayerSession;
import dev.redstonelab.server.Sessions;

/** Server networking. Every send goes through {@link #send}, which skips clients without the channel (vanilla). */
public final class LabNetworking {
	private LabNetworking() {
	}

	public static void registerServerReceivers() {
		ServerPlayNetworking.registerGlobalReceiver(LabPayloads.HelloC2S.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			PlayerSession session = Sessions.get(player);
			session.modded = payload.protocol() == LabPayloads.PROTOCOL;
			session.capabilities = session.modded ? payload.capabilities() : 0;
			session.clientVersion = payload.version();
			if (!session.modded) {
				RedstoneLab.LOGGER.info("{} has RedstoneLab {} with protocol {} (server uses {}), using vanilla fallback",
						player.getName().getString(), payload.version(), payload.protocol(), LabPayloads.PROTOCOL);
			}
			if (session.modded) {
				// The client sends its own piston settings (those that differ from the defaults) next.
				PistorderFeature.resetOptions(player);
			}
			send(player, new LabPayloads.HelloS2C(LabPayloads.PROTOCOL, RedstoneLab.version(), LabServer.canUse(player)));
			// Switch already visible markers from display entities to client rendering (or back).
			MarkerService.refreshMode(player);
		});
		ServerPlayNetworking.registerGlobalReceiver(LabPayloads.PistonWatchC2S.TYPE, (payload, context) ->
				Sessions.get(context.player()).watchedPistons = Set.copyOf(payload.pistons()));
	}

	/** Sends a payload only to a client that has the channel and finished a compatible handshake. True if sent. */
	public static boolean send(ServerPlayer player, CustomPacketPayload payload) {
		boolean handshake = payload instanceof LabPayloads.HelloS2C;
		if ((handshake || Sessions.get(player).modded) && ServerPlayNetworking.canSend(player, payload.type())) {
			ServerPlayNetworking.send(player, payload);
			return true;
		}
		return false;
	}

	public static boolean canSend(ServerPlayer player, CustomPacketPayload.Type<?> type) {
		return ServerPlayNetworking.canSend(player, type);
	}
}
