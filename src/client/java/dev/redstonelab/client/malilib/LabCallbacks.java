package dev.redstonelab.client.malilib;

import fi.dy.masa.malilib.config.IConfigBase;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.hotkeys.IHotkey;
import fi.dy.masa.malilib.hotkeys.IHotkeyCallback;
import fi.dy.masa.malilib.hotkeys.IKeybind;
import fi.dy.masa.malilib.hotkeys.KeyAction;
import fi.dy.masa.malilib.hotkeys.KeyCallbackToggleBooleanConfigWithMessage;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import dev.redstonelab.client.ClientNetworking;
import dev.redstonelab.client.ClientState;

/**
 * Hotkey actions. Meters live on the server, so meter keys send {@code /rlab} commands (the
 * server checks permissions, exactly as for vanilla clients).
 */
public final class LabCallbacks implements IHotkeyCallback {
	public static final LabCallbacks INSTANCE = new LabCallbacks();

	/** Timeline scroll offset in ticks while paused. */
	public static int scroll;
	/** The timeline was frozen by the rewind key (not by the pause key), so going forward to the present un-freezes it. */
	private static boolean autoPaused;

	private LabCallbacks() {
	}

	public static void init() {
		for (IHotkey hotkey : LabConfigs.Keys.HOTKEYS) {
			hotkey.getKeybind().setCallback(INSTANCE);
		}
		LabConfigs.Keys.PAUSE_TIMELINE.getKeybind().setCallback(new KeyCallbackToggleBooleanConfigWithMessage(LabConfigs.Keys.PAUSE_TIMELINE));
		for (IConfigBase option : LabConfigs.Pistons.OPTIONS) {
			if (option instanceof fi.dy.masa.malilib.config.options.ConfigBoolean b) {
				b.setValueChangeCallback(c -> sendPistonOption(c));
			}
		}
		LabConfigs.Keys.PAUSE_TIMELINE.setValueChangeCallback(c -> {
			scroll = 0;
			autoPaused = false;
		});
	}

	private static void sendPistonOption(fi.dy.masa.malilib.config.options.ConfigBoolean option) {
		if (ClientState.serverHasMod && ClientState.allowed) {
			ClientNetworking.command("rlab piston landing " + option.getBooleanValue());
		}
	}

	/** After joining a server with RedstoneLab: send the piston options that differ from the defaults. */
	public static void syncPistonOptions() {
		for (IConfigBase option : LabConfigs.Pistons.OPTIONS) {
			if (option instanceof fi.dy.masa.malilib.config.options.ConfigBoolean b && b.getBooleanValue() != b.getDefaultBooleanValue()) {
				sendPistonOption(b);
			}
		}
	}

	/** Sends a command only when connected to a server that has RedstoneLab. */
	static void send(String command) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) {
			return;
		}
		if (!ClientState.serverHasMod) {
			mc.player.displayClientMessage(Component.literal("[RedstoneLab] This server does not have RedstoneLab"), true);
			return;
		}
		ClientNetworking.command(command);
	}

	@Override
	public boolean onKeyAction(KeyAction action, IKeybind key) {
		Minecraft mc = Minecraft.getInstance();
		if (key == LabConfigs.Keys.OPEN_SETTINGS.getKeybind()) {
			GuiBase.openGui(new LabConfigGui());
			return true;
		}
		if (mc.player == null) {
			return false;
		}
		if (key == LabConfigs.Keys.SCROLL_BACK.getKeybind()) {
			if (!LabConfigs.Keys.PAUSE_TIMELINE.getBooleanValue()) {
				LabConfigs.Keys.PAUSE_TIMELINE.setBooleanValue(true);
				autoPaused = true;
			}
			scroll = Math.min(scroll + 1, Math.max(0, ClientState.filled - 1));
			return true;
		}
		if (key == LabConfigs.Keys.SCROLL_FORWARD.getKeybind()) {
			scroll = Math.max(0, scroll - 1);
			if (scroll == 0 && autoPaused) {
				LabConfigs.Keys.PAUSE_TIMELINE.setBooleanValue(false);
			}
			return true;
		}
		if (key == LabConfigs.Keys.CLEAR_PISTONS.getKeybind()) {
			send("rlab piston clear");
			return true;
		}
		if (key == LabConfigs.Keys.GROUPS.getKeybind()) {
			send("rlab group");
			return true;
		}
		if (key == LabConfigs.Keys.REMOVE_METER.getKeybind()) {
			BlockPos looked = mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK ? hit.getBlockPos() : null;
			boolean metered = false;
			for (ClientState.Channel ch : ClientState.SCOPE) {
				metered |= ch.pos.equals(looked);
			}
			send(metered ? "rlab meter remove " + looked.getX() + " " + looked.getY() + " " + looked.getZ() : "rlab meter remove");
			return true;
		}
		if (key == LabConfigs.Keys.CLEAR_METERS.getKeybind()) {
			send("rlab meter clear");
			return true;
		}
		if (key == LabConfigs.Keys.TOGGLE_METER.getKeybind()) {
			if (mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
				BlockPos pos = hit.getBlockPos();
				send("rlab meter " + pos.getX() + " " + pos.getY() + " " + pos.getZ());
			} else {
				mc.player.displayClientMessage(Component.literal("[RedstoneLab] Look at a block first"), true);
			}
			return true;
		}
		return false;
	}
}
