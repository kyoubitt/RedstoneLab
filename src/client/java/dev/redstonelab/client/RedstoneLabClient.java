package dev.redstonelab.client;

import net.minecraft.resources.Identifier;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;

import dev.redstonelab.RedstoneLab;
import dev.redstonelab.client.ui.LabConfig;
import dev.redstonelab.client.ui.LabKeys;
import dev.redstonelab.client.ui.LookHud;
import dev.redstonelab.client.ui.TimelineHud;
import dev.redstonelab.client.ui.WorldMarkers;

/**
 * Client entrypoint. Needs nothing but Fabric API: settings screen, key combos, the timeline and
 * the in-world outlines are all built in. Players without the client mod still get meters (boss
 * bars) and piston previews from a server that has it.
 */
public class RedstoneLabClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		LabConfig.load();
		ClientNetworking.init();
		ClientPistons.init();
		LabKeys.init();
		HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, Identifier.fromNamespaceAndPath(RedstoneLab.MOD_ID, "timeline"),
				TimelineHud.INSTANCE::render);
		LookHud.init();
		HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, Identifier.fromNamespaceAndPath(RedstoneLab.MOD_ID, "explain"),
				LookHud::render);
		// Runs inside the game's per-tick drawing list.
		ClientTickEvents.END_CLIENT_TICK.register(mc -> WorldMarkers.emit());
	}
}
