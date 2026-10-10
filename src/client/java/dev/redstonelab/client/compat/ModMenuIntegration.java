package dev.redstonelab.client.compat;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

import dev.redstonelab.client.RedstoneLabClient;
import dev.redstonelab.client.malilib.MalilibBridge;

/**
 * Mod Menu "Configure" button. Only loaded by Mod Menu (soft dependency, compile-only).
 * Without MaLiLib it shows a short explanation instead of crashing.
 */
public class ModMenuIntegration implements ModMenuApi {
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return parent -> RedstoneLabClient.hasMalilib() ? MalilibBridge.createConfigScreen(parent) : new MissingMalilibScreen(parent);
	}
}
