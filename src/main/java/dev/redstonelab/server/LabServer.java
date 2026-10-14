package dev.redstonelab.server;

import org.jetbrains.annotations.Nullable;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityWorldChangeEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;

import dev.redstonelab.capture.PowerWatch;
import dev.redstonelab.command.RlabCommand;
import dev.redstonelab.config.ServerConfig;
import dev.redstonelab.display.MarkerService;
import dev.redstonelab.feature.MeterFeature;
import dev.redstonelab.feature.PistorderFeature;
import dev.redstonelab.net.LabNetworking;

/**
 * Server bootstrap: lifecycle, tick and player events, commands, clicks.
 */
public final class LabServer {
	private static @Nullable MinecraftServer server;

	private LabServer() {
	}

	public static void init() {
		LabNetworking.registerServerReceivers();

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
				RlabCommand.register(dispatcher, registryAccess));

		ServerLifecycleEvents.SERVER_STARTED.register(s -> server = s);
		ServerLifecycleEvents.SERVER_STOPPING.register(s -> shutdown());

		ServerTickEvents.END_SERVER_TICK.register(LabServer::tick);

		ServerPlayConnectionEvents.JOIN.register((handler, sender, s) -> {
			Sessions.get(handler.getPlayer());
			MeterFeature.onJoin(handler.getPlayer());
		});
		ServerPlayConnectionEvents.DISCONNECT.register((handler, s) -> onLeave(handler.getPlayer()));

		ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> onClientLostEntities(newPlayer));
		ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD.register((player, origin, destination) -> onClientLostEntities(player));

		ServerChunkEvents.CHUNK_UNLOAD.register((level, chunk) -> {
			if (server != null) {
				MarkerService.onChunkUnload(level, chunk.getPos(), server.getPlayerList().getPlayers());
			}
		});

		UseBlockCallback.EVENT.register(MeterFeature::onUseBlock);
		UseBlockCallback.EVENT.register(PistorderFeature::onUseBlock);
	}

	public static @Nullable MinecraftServer server() {
		return server;
	}

	// ------------------------------------------------------------------ permissions

	public static boolean canUse(CommandSourceStack source) {
		return switch (ServerConfig.get().permissionLevel) {
			case 0 -> true;
			case 1 -> Commands.LEVEL_MODERATORS.check(source.permissions());
			case 2 -> Commands.LEVEL_GAMEMASTERS.check(source.permissions());
			case 3 -> Commands.LEVEL_ADMINS.check(source.permissions());
			default -> Commands.LEVEL_OWNERS.check(source.permissions());
		};
	}

	public static boolean canUse(ServerPlayer player) {
		return canUse(player.createCommandSourceStack());
	}

	// ------------------------------------------------------------------ events

	private static void tick(MinecraftServer s) {
		MeterFeature.tick(s);
		PistorderFeature.tick(s);
		// Tell modded clients each tick, so their pistons and mobs move in step with the game
		// (normal speed and slow motion; in fast forward a client can't tick as often).
		if (s.tickRateManager().tickrate() <= 20 && !s.tickRateManager().isFrozen()) {
			for (net.minecraft.server.level.ServerPlayer player : s.getPlayerList().getPlayers()) {
				if (net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.canSend(player, dev.redstonelab.net.LabPayloads.WorldTickS2C.TYPE)) {
					net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(player, dev.redstonelab.net.LabPayloads.WorldTickS2C.INSTANCE);
				}
			}
		}
		if (s.getTickCount() % 20 == 0) {
			MarkerService.tick(s.getPlayerList().getPlayers());
		}
	}

	private static void onLeave(ServerPlayer player) {
		MeterFeature.onLeave(player);
		PistorderFeature.onLeave(player);
		MarkerService.forget(player.getUUID());
		Sessions.remove(player.getUUID());
	}

	private static void onClientLostEntities(ServerPlayer player) {
		MarkerService.resync(player);
		MeterFeature.onClientReset(player);
	}

	/** Removes all meters and piston previews of one player. */
	public static void stopAll(ServerPlayer player) {
		MeterFeature.clear(player);
		PistorderFeature.clear(player);
	}

	private static void shutdown() {
		MinecraftServer s = server;
		if (s != null) {
			for (ServerPlayer player : s.getPlayerList().getPlayers()) {
				MeterFeature.onLeave(player);
				PistorderFeature.onLeave(player);
				MarkerService.clearAll(player);
			}
		}
		PowerWatch.clearAll();
		Sessions.clear();
		server = null;
	}

	public static @Nullable ServerLevel level(net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> key) {
		return server == null ? null : server.getLevel(key);
	}
}
