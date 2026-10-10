package dev.redstonelab.client.malilib;

import fi.dy.masa.malilib.hotkeys.IHotkey;
import fi.dy.masa.malilib.hotkeys.IKeybindManager;
import fi.dy.masa.malilib.hotkeys.IKeybindProvider;

/**
 * Registers RedstoneLab's hotkeys with MaLiLib.
 */
public final class LabInputHandler implements IKeybindProvider {
	public static final LabInputHandler INSTANCE = new LabInputHandler();

	private LabInputHandler() {
	}

	@Override
	public void addKeysToMap(IKeybindManager manager) {
		for (IHotkey hotkey : LabConfigs.Keys.HOTKEYS) {
			manager.addKeybindToMap(hotkey.getKeybind());
		}
	}

	@Override
	public void addHotkeys(IKeybindManager manager) {
		manager.addHotkeysForCategory("RedstoneLab", "redstonelab.hotkeys.category.keys", LabConfigs.Keys.HOTKEYS);
	}
}
