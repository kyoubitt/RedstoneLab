package dev.redstonelab.client.malilib;

import fi.dy.masa.malilib.config.ConfigManager;
import fi.dy.masa.malilib.event.InputEventHandler;
import fi.dy.masa.malilib.event.RenderEventHandler;
import fi.dy.masa.malilib.interfaces.IInitializationHandler;
import fi.dy.masa.malilib.registry.Registry;
import fi.dy.masa.malilib.util.data.ModInfo;

import dev.redstonelab.RedstoneLab;

/**
 * Registers config, config screen, hotkeys and renderers with MaLiLib once the game is
 * initialised (same pattern as masa's mods).
 */
public final class LabInitHandler implements IInitializationHandler {
	@Override
	public void registerModHandlers() {
		ConfigManager.getInstance().registerConfigHandler(RedstoneLab.MOD_ID, new LabConfigs());
		Registry.CONFIG_SCREEN.registerConfigScreenFactory(new ModInfo(RedstoneLab.MOD_ID, "RedstoneLab", LabConfigGui::new));

		InputEventHandler.getKeybindManager().registerKeybindProvider(LabInputHandler.INSTANCE);

		RenderEventHandler.getInstance().registerGameOverlayRenderer(LabRenderer.INSTANCE);
		RenderEventHandler.getInstance().registerWorldLastRenderer(LabRenderer.INSTANCE);

		LabCallbacks.init();
	}
}
