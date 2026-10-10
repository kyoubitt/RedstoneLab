package dev.redstonelab;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.resources.Identifier;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;

import dev.redstonelab.config.ServerConfig;
import dev.redstonelab.net.LabPayloads;
import dev.redstonelab.server.LabServer;

/**
 * Common entrypoint (dedicated server and integrated server). Meters and piston previews
 * run on the server, so players without the mod can use them too.
 */
public class RedstoneLab implements ModInitializer {
	public static final String MOD_ID = "redstonelab";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		ServerConfig.load();
		LabPayloads.registerCommon();
		LabServer.init();
		LOGGER.info("RedstoneLab {} initialised", version());
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}

	public static String version() {
		return FabricLoader.getInstance().getModContainer(MOD_ID)
				.map(c -> c.getMetadata().getVersion().getFriendlyString())
				.orElse("dev");
	}
}
