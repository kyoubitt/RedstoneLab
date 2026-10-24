package dev.redstonelab.client.compat;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

import dev.redstonelab.client.ui.LabConfigScreen;

/** Mod Menu "Configure" button (soft dependency): opens the settings screen. */
public class ModMenuIntegration implements ModMenuApi {
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return LabConfigScreen::new;
	}
}
