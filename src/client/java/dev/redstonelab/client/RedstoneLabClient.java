package dev.redstonelab.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;

import dev.redstonelab.RedstoneLab;
import dev.redstonelab.client.malilib.MalilibBridge;

/**
 * Client entrypoint. The client part is optional: players without it still get meters
 * (boss bars) and piston previews. With this mod and MaLiLib the client draws the timeline
 * and outlines itself and gets hotkeys and the settings menu.
 *
 * <p>All MaLiLib code lives in {@code dev.redstonelab.client.malilib} and is only touched
 * after {@link #hasMalilib()} returned true, so the mod also loads without MaLiLib.</p>
 */
public class RedstoneLabClient implements ClientModInitializer {
	private static final boolean MALILIB = FabricLoader.getInstance().isModLoaded("malilib");

	@Override
	public void onInitializeClient() {
		ClientNetworking.init();
		if (MALILIB) {
			MalilibBridge.init();
		} else {
			RedstoneLab.LOGGER.warn("MaLiLib is not installed: the RedstoneLab timeline, hotkeys and settings menu are disabled.");
		}
	}

	public static boolean hasMalilib() {
		return MALILIB;
	}

	public static boolean isTimelinePaused() {
		return MALILIB && MalilibBridge.isTimelinePaused();
	}
}
