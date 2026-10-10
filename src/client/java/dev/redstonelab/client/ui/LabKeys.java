package dev.redstonelab.client.ui;

import java.util.ArrayList;
import java.util.List;

import org.lwjgl.glfw.GLFW;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import com.mojang.blaze3d.platform.InputConstants;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;

import dev.redstonelab.client.ClientNetworking;
import dev.redstonelab.client.ClientPistons;
import dev.redstonelab.client.ClientState;

/**
 * Key combos ("R + Enter": hold R, press Enter) and what they do. Fed from the keyboard mixin; a
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

	/** Client tick of the last /tick command we sent (its chat reply gets hidden). */
	private static long tickCommandAt = Long.MIN_VALUE;
	private static long clientTicks;

	private LabKeys() {
	}

	public static void init() {
		ClientTickEvents.END_CLIENT_TICK.register(mc -> clientTicks++);
		// Our own /tick freeze / step / unfreeze answer in chat: hidden, the action bar says it.
		ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> !(clientTicks - tickCommandAt < 40
				&& message.getContents() instanceof TranslatableContents t && t.getKey().startsWith("commands.tick.")));
	}

	/** The player may use /tick (op, or cheats on in singleplayer). */
	private static boolean canTick(Minecraft mc) {
		return mc.getConnection() != null && mc.getConnection().getCommands().getRoot().getChild("tick") != null;
	}

	public static boolean gameFrozen() {
		Minecraft mc = Minecraft.getInstance();
		return mc.level != null && mc.level.tickRateManager().isFrozen();
	}

	private static void tick(String command) {
		tickCommandAt = clientTicks;
		ClientNetworking.command(command);
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
		} else if (hotkey == LabConfig.FREEZE_GAME || hotkey == LabConfig.STEP_GAME) {
			if (!canTick(mc)) {
				mc.player.displayClientMessage(Component.literal("Freezing the game needs /tick (op)"), true);
				return;
			}
			boolean frozen = gameFrozen();
			if (hotkey == LabConfig.STEP_GAME && frozen) {
				tick("tick step 1");
				return;
			}
			// Down while running freezes first; the timeline keeps showing the game.
			tick(frozen ? "tick unfreeze" : "tick freeze");
			setPaused(false);
			mc.player.displayClientMessage(Component.literal(frozen ? "Game running"
					: "Game frozen \u00b7 " + LabConfig.STEP_GAME.value.display() + ": step 1t"), true);
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
				mc.player.displayClientMessage(Component.literal("Look at a block"), true);
			}
		}
	}

	/** Sends a command only when connected to a server that has RedstoneLab. */
	private static void send(String command) {
		Minecraft mc = Minecraft.getInstance();
		if (!ClientState.serverHasMod) {
			mc.player.displayClientMessage(Component.literal("Meters need RedstoneLab on the server"), true);
			return;
		}
		ClientNetworking.command(command);
	}
}
