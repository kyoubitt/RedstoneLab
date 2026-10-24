package dev.redstonelab.client;

import net.minecraft.resources.Identifier;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;

import dev.redstonelab.RedstoneLab;
import dev.redstonelab.client.ui.LabConfig;
import dev.redstonelab.client.ui.TimelineHud;
import dev.redstonelab.client.ui.WorldMarkers;

/** Client entrypoint. Needs only Fabric API: settings, key combos, timeline and outlines are built in. */
public class RedstoneLabClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		LabConfig.load();
		ClientNetworking.init();
		ClientPistons.init();
		HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, Identifier.fromNamespaceAndPath(RedstoneLab.MOD_ID, "timeline"),
				TimelineHud.INSTANCE::render);
		// Runs inside the game's per-tick drawing list.
		ClientTickEvents.END_CLIENT_TICK.register(mc -> WorldMarkers.emit());
	}
}
