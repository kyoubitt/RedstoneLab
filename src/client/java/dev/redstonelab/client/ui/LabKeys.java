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

	/** How many ticks back the timeline looks while stopped (0 = newest). */
	public static int scroll;
	/** The timeline is stopped on the client only (players who can't freeze the game). */
	public static boolean paused;

	/** Client tick of the last /tick command we sent (its chat reply gets hidden). */
	private static long tickCommandAt = Long.MIN_VALUE;
	private static long clientTicks;

	private LabKeys() {
	}

	public static void init() {
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			clientTicks++;
			if (!stopped()) {
				scroll = 0;
			}
		});
		// Our own /tick freeze / step / unfreeze answer in chat: hidden, the action bar says it.
		ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> !(clientTicks - tickCommandAt < 40
				&& message.getContents() instanceof TranslatableContents t && t.getKey().startsWith("commands.tick.")));
	}

	/** The player may use /tick (op, or cheats on in singleplayer). */
	private static boolean canTick(Minecraft mc) {
		return mc.getConnection() != null && mc.getConnection().getCommands().getRoot().getChild("tick") != null;
	}

	/** Game speeds for slow motion, in ticks per second (20 = normal). */
	private static final float[] RATES = {20, 10, 5, 2, 1};
	/** The speed asked for last (the server's answer takes a moment to arrive). */
	private static float asked = -1;
	private static long askedAt;

	/** The game's speed now, in ticks per second. */
	public static float tickRate() {
		Minecraft mc = Minecraft.getInstance();
		float rate = mc.level == null ? 20 : mc.level.tickRateManager().tickrate();
		// Real time: in slow motion the client ticks slower too.
		return asked > 0 && System.currentTimeMillis() - askedAt < 2000 ? asked : rate;
	}

	/** "1/4 speed", or "" at full speed. */
	public static String speedLabel() {
		float rate = tickRate();
		if (rate >= 20 - 0.01f) {
			return "";
		}
		float part = 20 / rate;
		return Math.abs(part - Math.round(part)) < 0.01f ? "1/" + Math.round(part) + " speed" : Math.round(rate) + " tps";
	}

	private static void setRate(Minecraft mc, float rate) {
		asked = rate;
		askedAt = System.currentTimeMillis();
		tick("tick rate " + (rate == Math.round(rate) ? String.valueOf(Math.round(rate)) : String.valueOf(rate)));
		say(mc, rate >= 20 ? "Full speed" : "Slow motion: " + speedLabelFor(rate) + " \u00b7 " + key(LabConfig.SLOWER) + " / "
				+ key(LabConfig.FASTER) + " \u00b7 " + key(LabConfig.RUN_GAME) + ": full speed");
	}

	private static String speedLabelFor(float rate) {
		float before = asked;
		asked = rate;
		String label = speedLabel();
		asked = before;
		return label;
	}

	public static boolean gameFrozen() {
		Minecraft mc = Minecraft.getInstance();
		return mc.level != null && mc.level.tickRateManager().isFrozen();
	}

	/** The timeline stands still (game frozen, or stopped here): Left / Right look back. */
	public static boolean stopped() {
		return paused || gameFrozen();
	}

	/** A new sample came in while stopped: keep looking at the same tick. */
	public static void onSample() {
		if (scroll > 0) {
			scroll = Math.min(scroll + 1, Math.max(0, ClientState.filled - 1));
		}
	}

	private static boolean timelineShown() {
		return LabConfig.SHOW_TIMELINE.value && !ClientState.SCOPE.isEmpty();
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
		// Keys that start a combo (R in "R + M"). Other held keys (walking, sprinting) don't matter.
		java.util.Set<Integer> modifiers = new java.util.HashSet<>();
		for (LabConfig.Hotkey hotkey : LabConfig.hotkeys()) {
			List<Integer> keys = hotkey.value.keys();
			modifiers.addAll(keys.subList(0, Math.max(0, keys.size() - 1)));
		}
		java.util.Set<Integer> held = new java.util.HashSet<>();
		for (int k : HELD) {
			if (k == key || modifiers.contains(k)) {
				held.add(k);
			}
		}
		for (LabConfig.Hotkey hotkey : LabConfig.hotkeys()) {
			List<Integer> keys = hotkey.value.keys();
			if (keys.isEmpty() || keys.get(keys.size() - 1) != key || !held.equals(new java.util.HashSet<>(keys))) {
				continue;
			}
			if (action == GLFW.GLFW_REPEAT && !hotkey.repeats) {
				return true;
			}
			run(hotkey, mc, action == GLFW.GLFW_REPEAT);
			return true;
		}
		return false;
	}

	public static void setPaused(boolean value) {
		paused = value;
		scroll = 0;
	}

	private static void run(LabConfig.Hotkey hotkey, Minecraft mc, boolean repeat) {
		if (hotkey == LabConfig.OPEN_SETTINGS) {
			mc.setScreen(new LabConfigScreen(null));
		} else if (hotkey == LabConfig.FREEZE_GAME || hotkey == LabConfig.RUN_GAME || hotkey == LabConfig.SLOWER
				|| hotkey == LabConfig.FASTER) {
			time(hotkey, mc, repeat);
		} else if (hotkey == LabConfig.CLEAR_PISTONS) {
			ClientPistons.clear();
			mc.player.displayClientMessage(Component.literal("Piston previews hidden"), true);
		} else if (hotkey == LabConfig.GROUPS) {
			send("rlab group");
		} else if (hotkey == LabConfig.CLEAR_METERS) {
			send("rlab meter clear");
		} else if (hotkey == LabConfig.TOGGLE_METER) {
			if (mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
				BlockPos pos = hit.getBlockPos();
				send("rlab meter " + pos.getX() + " " + pos.getY() + " " + pos.getZ());
			} else {
				mc.player.displayClientMessage(Component.literal("Look at a block"), true);
			}
		}
	}

	/**
	 * The arrow keys: Up stops (then steps), Down runs (then full speed), Left / Right change the
	 * speed while running and look back / forward through the ticks while stopped. Players who
	 * can't use /tick still stop and look back through the timeline.
	 */
	private static void time(LabConfig.Hotkey hotkey, Minecraft mc, boolean repeat) {
		boolean op = canTick(mc);
		boolean back = hotkey == LabConfig.SLOWER;
		if ((hotkey == LabConfig.SLOWER || hotkey == LabConfig.FASTER) && timelineShown() && (stopped() || !op)) {
			if (!stopped()) {
				if (!back) {
					say(mc, "Slow motion needs /tick (op)");
					return;
				}
				// Can't freeze the game: Left stops the timeline instead.
				setPaused(true);
				say(mc, "Timeline stopped \u00b7 " + keys() + " \u00b7 " + key(LabConfig.RUN_GAME) + ": live");
			}
			scroll = back ? Math.min(scroll + 1, Math.max(0, ClientState.filled - 1)) : Math.max(0, scroll - 1);
			return;
		}
		if (!op) {
			if (hotkey == LabConfig.FREEZE_GAME && timelineShown() && !stopped()) {
				setPaused(true);
				say(mc, "Timeline stopped \u00b7 " + keys() + " \u00b7 " + key(LabConfig.RUN_GAME) + ": live");
			} else if (hotkey == LabConfig.RUN_GAME && paused) {
				setPaused(false);
				say(mc, "Timeline live");
			} else if (!repeat) {
				say(mc, "Needs /tick (op)");
			}
			return;
		}
		boolean frozen = gameFrozen();
		float rate = tickRate();
		if (hotkey == LabConfig.SLOWER || hotkey == LabConfig.FASTER) {
			if (repeat) {
				return;
			}
			// The next speed down (or up) from the list, from wherever the speed is now.
			float next = rate;
			for (float r : RATES) {
				if (back ? r < rate - 0.01f && next == rate : r > rate + 0.01f) {
					next = r;
				}
			}
			if (next != rate) {
				setRate(mc, next);
			}
		} else if (hotkey == LabConfig.RUN_GAME) {
			if (repeat) {
				return;
			}
			setPaused(false);
			// Frozen: run at the speed it had. Running: back to full speed.
			if (frozen) {
				tick("tick unfreeze");
				String speed = speedLabel();
				say(mc, speed.isEmpty() ? "Running" : "Running at " + speed + " \u00b7 " + key(LabConfig.RUN_GAME) + ": full speed");
			} else if (rate < 20 - 0.01f) {
				setRate(mc, 20);
			} else {
				say(mc, "Full speed");
			}
		} else if (frozen) {
			// Step: show the new tick.
			scroll = 0;
			tick("tick step 1");
		} else {
			setPaused(false);
			tick("tick freeze");
			say(mc, "Frozen \u00b7 " + key(LabConfig.FREEZE_GAME) + ": step" + (timelineShown() ? " \u00b7 " + keys() : "")
					+ " \u00b7 " + key(LabConfig.RUN_GAME) + ": run");
		}
	}

	/** The keys that work while stopped, for the timeline. */
	public static String stoppedKeys() {
		Minecraft mc = Minecraft.getInstance();
		String keys = key(LabConfig.SLOWER) + " / " + key(LabConfig.FASTER) + ": back / forward";
		if (canTick(mc)) {
			return keys + " \u00b7 " + key(LabConfig.FREEZE_GAME) + ": step \u00b7 " + key(LabConfig.RUN_GAME) + ": run";
		}
		return paused ? keys + " \u00b7 " + key(LabConfig.RUN_GAME) + ": live" : keys;
	}

	/** "Left / Right: look back". */
	private static String keys() {
		return key(LabConfig.SLOWER) + " / " + key(LabConfig.FASTER) + ": look back";
	}

	private static String key(LabConfig.Hotkey hotkey) {
		return hotkey.value.display();
	}

	private static void say(Minecraft mc, String text) {
		mc.player.displayClientMessage(Component.literal(text), true);
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
