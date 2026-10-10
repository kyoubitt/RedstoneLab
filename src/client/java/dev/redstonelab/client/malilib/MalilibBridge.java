package dev.redstonelab.client.malilib;

import org.jetbrains.annotations.Nullable;

import fi.dy.masa.malilib.event.InitializationHandler;

import net.minecraft.client.gui.screens.Screen;

/**
 * The only entry point into MaLiLib-dependent code. Every method here may only be called
 * after {@code FabricLoader.isModLoaded("malilib")} returned true.
 */
public final class MalilibBridge {
	private MalilibBridge() {
	}

	public static void init() {
		InitializationHandler.getInstance().registerInitializationHandler(new LabInitHandler());
	}

	public static void onHandshake() {
		LabCallbacks.syncPistonOptions();
	}

	public static boolean isTimelinePaused() {
		return LabConfigs.Keys.PAUSE_TIMELINE.getBooleanValue();
	}

	public static Screen createConfigScreen(@Nullable Screen parent) {
		LabConfigGui gui = new LabConfigGui();
		gui.setParent(parent);
		return gui;
	}
}
