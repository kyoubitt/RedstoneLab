package dev.redstonelab.client.ui;

import java.util.ArrayList;
import java.util.List;

import org.lwjgl.glfw.GLFW;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import com.mojang.blaze3d.platform.InputConstants;

import dev.redstonelab.client.ClientNetworking;
import dev.redstonelab.client.ClientPistons;
import dev.redstonelab.client.ClientState;

/**
 * Key combos ("R + Left": hold R, press Left) and what they do. Fed from the keyboard mixin; a
 * combo that fires swallows its last key, so the game doesn't also react to it.
 */
public final class LabKeys {
	/** Keys held right now, in the order they went down. */
	private static final List<Integer> HELD = new ArrayList<>();

	/** Timeline scroll offset in ticks while frozen. */
	public static int scroll;
	/** The timeline is frozen. */
	public static boolean paused;
	/** Frozen by the rewind key (not by hand), so going forward to the present un-freezes it. */
	private static boolean autoPaused;

	private LabKeys() {
	}

	/** A key went down (1), up (0) or repeats (2). Returns true when a combo used it. */
	public static boolean onKey(int key, int action) {
		Minecraft mc = Minecraft.getInstance();
		if (action == GLFW.GLFW_RELEASE) {
			HELD.remove((Integer) key);
			return false;
		}
		if (action == GLFW.GLFW_PRESS && !HELD.contains(key)) {
			HELD.add(key);
		}
		// Keys let go while a screen had the focus never send a release here.
		HELD.removeIf(k -> k != key && !InputConstants.isKeyDown(mc.getWindow(), k));
		if (mc.screen != null || mc.player == null) {
			return false;
		}
		for (LabConfig.Hotkey hotkey : LabConfig.hotkeys()) {
			List<Integer> keys = hotkey.value.keys();
			if (keys.isEmpty() || keys.get(keys.size() - 1) != key || HELD.size() != keys.size() || !HELD.containsAll(keys)) {
				continue;
			}
			if (action == GLFW.GLFW_REPEAT && !hotkey.repeats) {
				return true;
			}
			run(hotkey, mc);
			return true;
		}
		return false;
	}

	public static void setPaused(boolean value) {
		paused = value;
		scroll = 0;
		autoPaused = false;
	}

	private static void run(LabConfig.Hotkey hotkey, Minecraft mc) {
		if (hotkey == LabConfig.OPEN_SETTINGS) {
			mc.setScreen(new LabConfigScreen(null));
		} else if (hotkey == LabConfig.PAUSE_TIMELINE) {
			setPaused(!paused);
			mc.player.displayClientMessage(Component.literal(paused ? "Timeline frozen" : "Timeline running"), true);
		} else if (hotkey == LabConfig.REWIND) {
			if (!paused) {
				paused = true;
				autoPaused = true;
			}
			scroll = Math.min(scroll + 1, Math.max(0, ClientState.filled - 1));
		} else if (hotkey == LabConfig.FORWARD) {
			scroll = Math.max(0, scroll - 1);
			if (scroll == 0 && autoPaused) {
				paused = false;
				autoPaused = false;
			}
		} else if (hotkey == LabConfig.CLEAR_PISTONS) {
			ClientPistons.clear();
			mc.player.displayClientMessage(Component.literal("Piston previews hidden"), true);
		} else if (hotkey == LabConfig.GROUPS) {
			send("rlab group");
		} else if (hotkey == LabConfig.CLEAR_METERS) {
			send("rlab meter clear");
		} else if (hotkey == LabConfig.REMOVE_METER) {
			BlockPos looked = mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK ? hit.getBlockPos() : null;
			boolean metered = false;
			for (ClientState.Channel ch : ClientState.SCOPE) {
				metered |= ch.pos.equals(looked);
			}
			send(metered ? "rlab meter remove " + looked.getX() + " " + looked.getY() + " " + looked.getZ() : "rlab meter remove");
		} else if (hotkey == LabConfig.TOGGLE_METER) {
			if (mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
				BlockPos pos = hit.getBlockPos();
				send("rlab meter " + pos.getX() + " " + pos.getY() + " " + pos.getZ());
			} else {
				mc.player.displayClientMessage(Component.literal("[RedstoneLab] Look at a block first"), true);
			}
		}
	}

	/** Sends a command only when connected to a server that has RedstoneLab. */
	private static void send(String command) {
		Minecraft mc = Minecraft.getInstance();
		if (!ClientState.serverHasMod) {
			mc.player.displayClientMessage(Component.literal("[RedstoneLab] Meters need RedstoneLab on the server too"), true);
			return;
		}
		ClientNetworking.command(command);
	}
}
