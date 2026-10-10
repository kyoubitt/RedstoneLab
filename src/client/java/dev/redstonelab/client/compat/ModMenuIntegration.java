package dev.redstonelab.client.compat;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

import dev.redstonelab.client.ui.LabConfigScreen;

/**
 * Mod Menu "Configure" button. Only loaded by Mod Menu (soft dependency, compile-only).
 * Opens the built-in settings screen.
 */
public class ModMenuIntegration implements ModMenuApi {
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return LabConfigScreen::new;
	}
}
