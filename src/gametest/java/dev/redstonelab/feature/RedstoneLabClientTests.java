package dev.redstonelab.feature;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import dev.redstonelab.client.ClientState;
import dev.redstonelab.client.ClientPistons;
import dev.redstonelab.client.ui.LabConfig;
import dev.redstonelab.client.ui.LabConfigScreen;
import dev.redstonelab.client.ui.LabKeys;

/**
 * Opens a real client, builds a clock, repeater, lamp, piston and a dropper -> hopper -> chest
 * line with a comparator, puts meters on everything and records the demo video: one screenshot
 * per game tick ({@code screenshots/*_video.png}) plus {@code video-captions.txt} (frame count and
 * caption per scene). Also checks the timeline bars against the real clock.
 */
public class RedstoneLabClientTests implements FabricClientGameTest {
	private final List<String> captions = new ArrayList<>();
	/** Where the player spawned; the build is in front of it. */
	private static BlockPos base;

	@Override
	public void runTest(ClientGameTestContext context) {
		// Software rendering in CI is slow: keep the world small so the server keeps up.
		context.runOnClient(client -> {
			client.options.renderDistance().set(2);
			client.options.simulationDistance().set(5);
			client.options.framerateLimit().set(20);
			client.options.menuBackgroundBlurriness().set(0);
		});
		context.getInput().resizeWindow(1280, 720);
		// Cheats on, so the player may use RedstoneLab (like an op on a server).
		try (TestSingleplayerContext world = context.worldBuilder().adjustSettings(s -> s.setAllowCommands(true)).create()) {
			world.getClientWorld().waitForChunksRender();
			world.getServer().runOnServer(RedstoneLabClientTests::build);
			context.waitTicks(100);
			context.takeScreenshot("still-timeline");
			// The bars must match the real clock: observer clock = on 2t, off 4t.
			String runs = context.computeOnClient(client -> runs(ClientState.SCOPE.get(0)));
			if (!runs.equals("on 2 off 4")) {
				throw new AssertionError("timeline bars for the observer clock: " + runs);
			}

			this.record(context, 100, "Meters: bar = on, number = how many ticks. Text: on/off lengths");
			this.record(context, 100, "F-I: dropper -> hopper -> chest -> comparator. Items inside, signal, how often");

			world.getServer().runOnServer(server -> MeterFeature.expect(player(server), "B", 2, 4, false));
			this.record(context, 70, "/rlab meter expect B 2 4  ->  every pulse checked: ok");
			world.getServer().runOnServer(server -> MeterFeature.expect(player(server), "B", 3, null, false));
			this.record(context, 70, "/rlab meter expect B 3  ->  wrong pulses turn red");
			world.getServer().runOnServer(server -> MeterFeature.expect(player(server), "B", null, null, true));

			// Real keys: hold R, press Left = 1 tick back (freezes the timeline).
			context.getInput().holdKey(org.lwjgl.glfw.GLFW.GLFW_KEY_R);
			context.getInput().pressKey(org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT);
			context.getInput().releaseKey(org.lwjgl.glfw.GLFW.GLFW_KEY_R);
			context.waitTick();
			boolean frozen = context.computeOnClient(client -> LabKeys.paused && LabKeys.scroll == 1);
			if (!frozen) {
				throw new AssertionError("R + Left should freeze the timeline 1 tick back");
			}
			for (int step = 0; step <= 6; step++) {
				int scroll = step;
				context.runOnClient(client -> LabKeys.scroll = scroll);
				this.record(context, 14, "Frozen (R+Space). R+Left = 1 tick back: everything that happened, in order");
			}
			context.takeScreenshot("still-frozen");
			context.runOnClient(client -> LabKeys.setPaused(false));

			// The world: meter letters.
			context.runOnClient(client -> LabConfig.SHOW_TIMELINE.value = false);
			world.getServer().runOnServer(server -> {
				ServerPlayer player = player(server);
				player.teleportTo(player.level(), base.getX() + 0.5, base.getY() + 2.5, base.getZ() - 1.5, java.util.Set.of(), 0f, 25f, false);
			});
			context.waitTicks(10);
			this.record(context, 60, "In the world: every meter has its letter");
			context.takeScreenshot("still-world");

			// Pistons, like Pistorder: right-click one with an empty hand (a real mouse click).
			world.getServer().runOnServer(server -> {
				ServerPlayer player = player(server);
				player.teleportTo(player.level(), base.getX() + 5.5, base.getY(), base.getZ() + 2, java.util.Set.of(), 0f, 0f, false);
				player.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, base.offset(4, 0, 4).getCenter());
			});
			context.waitTicks(10);
			context.getInput().pressMouse(org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_RIGHT);
			context.waitTicks(4);
			boolean shown = context.computeOnClient(client -> ClientPistons.isShown(base.offset(4, 0, 4)));
			if (!shown) {
				throw new AssertionError("right-clicking the piston with an empty hand did not show its preview");
			}
			this.record(context, 90, "Pistons: right-click with an empty hand (like Pistorder). Numbers = push order");
			String label = context.computeOnClient(client -> ClientPistons.label(base.offset(4, 0, 4)));
			if (label.isEmpty()) {
				throw new AssertionError("the piston preview was never drawn");
			}
			context.takeScreenshot("still-piston");
			// P hides all piston previews.
			context.getInput().pressKey(org.lwjgl.glfw.GLFW.GLFW_KEY_P);
			context.waitTick();
			if (context.computeOnClient(client -> ClientPistons.isShown(base.offset(4, 0, 4)) || client.screen != null)) {
				throw new AssertionError("P should hide the piston previews (and not open a screen)");
			}

			// Chat view (works without the client mod) and the settings menu.
			world.getServer().runOnServer(server -> server.getCommands().performPrefixedCommand(
					player(server).createCommandSourceStack(), "rlab meter"));
			this.record(context, 80, "No client mod? /rlab meter shows it in chat (and boss bars)");
			context.runOnClient(client -> LabConfig.SHOW_TIMELINE.value = true);
			context.setScreen(() -> new LabConfigScreen(null));
			this.record(context, 40, "Settings: R + C. No other mods needed");
			context.clickScreenButton("Hotkeys");
			this.record(context, 40, "Hotkeys: click one, press the keys (e.g. hold R, press Left)");
			context.takeScreenshot("still-hotkeys");
			context.setScreen(() -> null);
			context.runOnClient(client -> LabConfig.SHOW_TIMELINE.value = true);
		}
		try {
			Files.write(Path.of("video-captions.txt"), this.captions, StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	/** {@code frames} screenshots, one per game tick, under one caption. */
	private void record(ClientGameTestContext context, int frames, String caption) {
		for (int i = 0; i < frames; i++) {
			context.waitTick();
			context.takeScreenshot("video");
		}
		this.captions.add(frames + "\t" + caption);
	}

	private static ServerPlayer player(MinecraftServer server) {
		return server.getPlayerList().getPlayers().get(0);
	}

	/**
	 * Row 1: observer clock -> repeater -> lamp -> sticky piston pushing stone.
	 * Row 1, other side: the clock also fires a dropper (stone + redstone) into a hopper, the
	 * hopper feeds a chest, a comparator reads the chest.
	 */
	private static void build(MinecraftServer server) {
		ServerPlayer player = player(server);
		ServerLevel level = player.level();
		player.setGameMode(GameType.CREATIVE);
		base = player.blockPosition();
		player.teleportTo(level, base.getX() + 0.5, base.getY(), base.getZ() + 0.5, java.util.Set.of(), 0f, 35f, false);
		BlockPos o1 = base.offset(0, 0, 4);
		BlockPos o2 = base.offset(1, 0, 4);
		BlockPos repeater = base.offset(2, 0, 4);
		BlockPos lamp = base.offset(3, 0, 4);
		BlockPos piston = base.offset(4, 0, 4);
		BlockPos stone = base.offset(4, 0, 5);
		BlockPos dropper = base.offset(-1, 0, 4);
		BlockPos hopper = base.offset(-2, 0, 4);
		BlockPos chest = base.offset(-3, 0, 4);
		BlockPos comparator = base.offset(-3, 0, 5);
		level.setBlockAndUpdate(repeater, Blocks.REPEATER.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.WEST));
		level.setBlockAndUpdate(lamp, Blocks.REDSTONE_LAMP.defaultBlockState());
		level.setBlockAndUpdate(piston, Blocks.STICKY_PISTON.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.SOUTH));
		level.setBlockAndUpdate(stone, Blocks.STONE.defaultBlockState());
		level.setBlockAndUpdate(chest, Blocks.CHEST.defaultBlockState());
		level.setBlockAndUpdate(hopper, Blocks.HOPPER.defaultBlockState().setValue(BlockStateProperties.FACING_HOPPER, Direction.WEST));
		level.setBlockAndUpdate(dropper, Blocks.DROPPER.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.WEST));
		level.setBlockAndUpdate(comparator, Blocks.COMPARATOR.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH));
		Container items = (Container) level.getBlockEntity(dropper);
		items.setItem(0, new ItemStack(Items.STONE, 64));
		items.setItem(1, new ItemStack(Items.REDSTONE, 32));
		items.setChanged();
		level.setBlockAndUpdate(o2, Blocks.OBSERVER.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.WEST));
		level.setBlockAndUpdate(o1, Blocks.OBSERVER.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.EAST));
		for (BlockPos p : new BlockPos[]{o2, repeater, lamp, piston, stone, dropper, hopper, chest, comparator}) {
			MeterFeature.toggle(player, level, p);
		}
		MeterFeature.rename(player, "A", "clock");
	}

	/** "on 2 off 4" when every complete on run and off run in the samples has the same length. */
	private static String runs(ClientState.Channel ch) {
		java.util.Set<Integer> on = new java.util.TreeSet<>();
		java.util.Set<Integer> off = new java.util.TreeSet<>();
		int run = 0;
		boolean current = false;
		boolean first = true;
		for (int age = ClientState.filled - 1; age >= 0; age--) {
			boolean v = (ch.bits[ClientState.sampleIndex(age)] & 1) != 0;
			if (run > 0 && v != current) {
				if (!first) {
					(current ? on : off).add(run);
				}
				first = false;
				run = 0;
			}
			current = v;
			run++;
		}
		return "on " + join(on) + " off " + join(off);
	}

	private static String join(java.util.Set<Integer> values) {
		StringBuilder sb = new StringBuilder();
		for (int v : values) {
			sb.append(sb.isEmpty() ? "" : "/").append(v);
		}
		return sb.toString();
	}
}
