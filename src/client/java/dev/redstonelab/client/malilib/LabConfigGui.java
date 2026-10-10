package dev.redstonelab.client.malilib;

import java.util.List;

import fi.dy.masa.malilib.config.IConfigBase;
import fi.dy.masa.malilib.gui.GuiConfigsBase;
import fi.dy.masa.malilib.gui.button.ButtonBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.button.IButtonActionListener;

import dev.redstonelab.RedstoneLab;

/**
 * The settings menu (MaLiLib), opened with R + C or from Mod Menu. Tabs: Meters, GUI, Pistorder, Hotkeys.
 */
public class LabConfigGui extends GuiConfigsBase {
	public enum Tab {
		GENERAL("Meters"),
		GUI("GUI"),
		PISTONS("Pistorder"),
		KEYS("Hotkeys");

		final String display;

		Tab(String display) {
			this.display = display;
		}

		List<? extends IConfigBase> options() {
			return switch (this) {
				case GENERAL -> LabConfigs.TAB_GENERAL;
				case GUI -> LabConfigs.TAB_GUI;
				case PISTONS -> LabConfigs.Pistons.OPTIONS;
				case KEYS -> LabConfigs.Keys.HOTKEYS;
			};
		}
	}

	private static Tab tab = Tab.GENERAL;

	public LabConfigGui() {
		super(10, 50, RedstoneLab.MOD_ID, null, "RedstoneLab settings");
	}

	@Override
	public void initGui() {
		super.initGui();
		this.clearOptions();
		int x = 10;
		for (Tab t : Tab.values()) {
			ButtonGeneric button = new ButtonGeneric(x, 26, -1, 20, t.display);
			button.setEnabled(tab != t);
			this.addButton(button, new TabListener(t, this));
			x += button.getWidth() + 2;
		}
	}

	@Override
	protected boolean useKeybindSearch() {
		return tab == Tab.KEYS;
	}

	@Override
	public List<ConfigOptionWrapper> getConfigs() {
		return ConfigOptionWrapper.createFor(tab.options());
	}

	private record TabListener(Tab tab, LabConfigGui gui) implements IButtonActionListener {
		@Override
		public void actionPerformedWithButton(ButtonBase button, int mouseButton) {
			LabConfigGui.tab = this.tab;
			this.gui.reCreateListWidget();
			this.gui.getListWidget().resetScrollbarPosition();
			this.gui.initGui();
		}
	}
}
