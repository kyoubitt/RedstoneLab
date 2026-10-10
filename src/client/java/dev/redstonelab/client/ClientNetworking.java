package dev.redstonelab.client;

import net.minecraft.client.Minecraft;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import dev.redstonelab.RedstoneLab;
import dev.redstonelab.net.LabPayloads;

/**
 * Client side of the handshake and all S2C receivers. The client draws the timeline and the
 * outlines itself, so the server sends data instead of boss bars and display entities.
 */
public final class ClientNetworking {
	private ClientNetworking() {
	}

	public static void init() {
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
			ClientState.reset();
			if (ClientPlayNetworking.canSend(LabPayloads.HelloC2S.TYPE)) {
				ClientPlayNetworking.send(new LabPayloads.HelloC2S(LabPayloads.PROTOCOL, capabilities(), RedstoneLab.version()));
			}
		});
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> ClientState.reset());

		ClientPlayNetworking.registerGlobalReceiver(LabPayloads.HelloS2C.TYPE, (payload, context) -> {
			ClientState.serverHasMod = payload.protocol() == LabPayloads.PROTOCOL;
			ClientState.allowed = payload.allowed();
			ClientState.serverVersion = payload.version();
			if (payload.protocol() != LabPayloads.PROTOCOL) {
				RedstoneLab.LOGGER.warn("Server RedstoneLab protocol {} differs from client protocol {}; using vanilla fallback",
						payload.protocol(), LabPayloads.PROTOCOL);
			}
		});
		ClientPlayNetworking.registerGlobalReceiver(LabPayloads.MarkersS2C.TYPE, (payload, context) -> {
			if (payload.markers().isEmpty()) {
				ClientState.MARKERS.remove(payload.channel());
			} else {
				ClientState.MARKERS.put(payload.channel(), payload.markers());
			}
		});
		ClientPlayNetworking.registerGlobalReceiver(LabPayloads.ScopeConfigS2C.TYPE, (payload, context) -> ClientState.applyScopeConfig(payload));
		ClientPlayNetworking.registerGlobalReceiver(LabPayloads.ScopeSampleS2C.TYPE, (payload, context) -> {
			if (!dev.redstonelab.client.ui.LabKeys.paused) {
				ClientState.addSample(payload);
			}
		});
	}

	static int capabilities() {
		return LabPayloads.CAP_HUD | LabPayloads.CAP_WORLD;
	}

	/** Runs a /rlab command on the server as if typed. */
	public static void command(String command) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null && mc.getConnection() != null) {
			mc.getConnection().sendCommand(command.startsWith("/") ? command.substring(1) : command);
		}
	}
}
