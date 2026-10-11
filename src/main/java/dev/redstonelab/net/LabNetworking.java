package dev.redstonelab.net;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import dev.redstonelab.RedstoneLab;
import dev.redstonelab.display.MarkerService;
import dev.redstonelab.server.LabServer;
import dev.redstonelab.server.PlayerSession;
import dev.redstonelab.server.Sessions;

/**
 * Server side networking. Every send goes through {@link #send}, which never sends to
 * a client that did not register the channel (i.e. vanilla clients).
 */
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
				dev.redstonelab.feature.PistorderFeature.resetOptions(player);
			}
			send(player, new LabPayloads.HelloS2C(LabPayloads.PROTOCOL, RedstoneLab.version(), LabServer.canUse(player)));
			// Switch already visible markers from display entities to client rendering (or back).
			MarkerService.refreshMode(player);
		});
		ServerPlayNetworking.registerGlobalReceiver(LabPayloads.PistonWatchC2S.TYPE, (payload, context) ->
				Sessions.get(context.player()).watchedPistons = java.util.Set.copyOf(payload.pistons()));
		ServerPlayNetworking.registerGlobalReceiver(LabPayloads.ItemWatchC2S.TYPE, (payload, context) -> {
			PlayerSession session = Sessions.get(context.player());
			session.itemRange = Math.max(0, Math.min(dev.redstonelab.feature.ItemInfoFeature.MAX_RANGE, payload.range()));
			session.itemsSentEmpty = false;
		});
	}

	/**
	 * Sends a payload only if the client registered the channel and completed a compatible
	 * handshake (the handshake answer itself is exempt). Returns true if sent.
	 */
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
