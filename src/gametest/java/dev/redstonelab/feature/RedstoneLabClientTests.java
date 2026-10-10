package dev.redstonelab.feature;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

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
import net.minecraft.world.level.block.piston.PistonBaseBlock;
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

			// Real keys: hold R, press Backspace = 1 tick back (freezes the timeline), Enter = forward.
			int metersBefore = context.computeOnClient(client -> ClientState.SCOPE.size());
			context.getInput().holdKey(org.lwjgl.glfw.GLFW.GLFW_KEY_R);
			context.getInput().pressKey(org.lwjgl.glfw.GLFW.GLFW_KEY_BACKSPACE);
			context.getInput().pressKey(org.lwjgl.glfw.GLFW.GLFW_KEY_BACKSPACE);
			context.getInput().pressKey(org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER);
			context.getInput().releaseKey(org.lwjgl.glfw.GLFW.GLFW_KEY_R);
			context.waitTicks(5);
			boolean frozen = context.computeOnClient(client -> LabKeys.paused && LabKeys.scroll == 1);
			if (!frozen) {
				throw new AssertionError("R + Backspace twice, R + Enter once: should be frozen 1 tick back, is "
						+ context.computeOnClient(client -> LabKeys.paused + " " + LabKeys.scroll));
			}
			// R + Backspace must not also remove a meter (plain Backspace does that).
			if (context.computeOnClient(client -> ClientState.SCOPE.size()) != metersBefore) {
				throw new AssertionError("R + Backspace removed a meter");
			}
			for (int step = 0; step <= 6; step++) {
				int scroll = step;
				context.runOnClient(client -> LabKeys.scroll = scroll);
				this.record(context, 14, "Frozen (R+Space). R+Backspace = 1 tick back: everything that happened, in order");
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
			context.runOnClient(client -> {
				LabConfig.SHOW_TIMELINE.value = true;
				client.gui.getChat().clearMessages(false);
			});
			context.setScreen(() -> new LabConfigScreen(null));
			this.record(context, 40, "Settings: R + C. No other mods needed");
			context.takeScreenshot("still-settings");
			context.clickScreenButton("Hotkeys");
			this.record(context, 40, "Hotkeys: click one, press the keys (e.g. hold R, press Left)");
			context.takeScreenshot("still-hotkeys");
			// Click a hotkey, press Esc: it gets unbound (like vanilla controls) and the menu stays open.
			context.clickScreenButton("R + Space");
			context.getInput().pressKey(org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE);
			context.waitTick();
			boolean unbound = context.computeOnClient(client -> LabConfig.PAUSE_TIMELINE.value.keys().isEmpty()
					&& client.screen instanceof LabConfigScreen);
			if (!unbound) {
				throw new AssertionError("Esc while setting a hotkey should unbind it and keep the menu open");
			}
			context.runOnClient(client -> {
				LabConfig.PAUSE_TIMELINE.reset();
				LabConfig.save();
			});
			context.setScreen(() -> null);
			context.runOnClient(client -> LabConfig.SHOW_TIMELINE.value = true);
			this.freezeAndStep(context, world);
			this.pistonGuideShot(context, world);
			this.firingOrder(context, world);
			this.clientPreviewsMatchServer(context, world);
		}
		try {
			Files.write(Path.of("video-captions.txt"), this.captions, StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	/** R + Space freezes the real game (/tick), R + Enter steps exactly 1 tick, R + Space runs it again. */
	private void freezeAndStep(ClientGameTestContext context, TestSingleplayerContext world) {
		context.runOnClient(client -> LabKeys.setPaused(false));
		combo(context, org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE);
		context.waitTicks(5);
		if (!world.getServer().computeOnServer(server -> server.tickRateManager().isFrozen())) {
			throw new AssertionError("R + Space should freeze the game");
		}
		long before = world.getServer().computeOnServer(server -> server.overworld().getGameTime());
		combo(context, org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER);
		context.waitTicks(5);
		long after = world.getServer().computeOnServer(server -> server.overworld().getGameTime());
		if (after - before != 1) {
			throw new AssertionError("R + Enter on a frozen game should step 1 tick, stepped " + (after - before));
		}
		context.waitTicks(5);
		context.takeScreenshot("still-game-frozen");
		combo(context, org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE);
		context.waitTicks(5);
		if (world.getServer().computeOnServer(server -> server.tickRateManager().isFrozen())) {
			throw new AssertionError("R + Space again should run the game");
		}
	}

	/** Hold R, press {@code key}, let go of R. */
	private static void combo(ClientGameTestContext context, int key) {
		context.getInput().holdKey(org.lwjgl.glfw.GLFW.GLFW_KEY_R);
		context.getInput().pressKey(key);
		context.getInput().releaseKey(org.lwjgl.glfw.GLFW.GLFW_KEY_R);
	}

	/** Three still pistons for the guide: pushes 4 (slime), blocked (obsidian), too many. */
	private void pistonGuideShot(ClientGameTestContext context, TestSingleplayerContext world) {
		BlockPos[] shown = new BlockPos[3];
		world.getServer().runOnServer(server -> {
			ServerPlayer player = player(server);
			ServerLevel level = player.level();
			// One block up, so the slime doesn't grab the ground.
			BlockPos o = base.offset(12, 1, -14);
			java.util.function.BiConsumer<BlockPos, net.minecraft.world.level.block.state.BlockState> set = (p, st) -> level.setBlock(p, st, 2 | 16);
			net.minecraft.world.level.block.state.BlockState east = Blocks.STICKY_PISTON.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.EAST);
			// 1: slime with stone stuck to it.
			shown[0] = o;
			set.accept(o, east);
			set.accept(o.east(), Blocks.SLIME_BLOCK.defaultBlockState());
			set.accept(o.east().above(), Blocks.STONE.defaultBlockState());
			set.accept(o.east().south(), Blocks.STONE.defaultBlockState());
			set.accept(o.east(2), Blocks.STONE.defaultBlockState());
			// 2: obsidian in the way.
			shown[1] = o.south(4);
			set.accept(shown[1], east);
			set.accept(shown[1].east(), Blocks.STONE.defaultBlockState());
			set.accept(shown[1].east(2), Blocks.OBSIDIAN.defaultBlockState());
			// 3: 13 blocks.
			shown[2] = o.south(8);
			set.accept(shown[2], east);
			for (int i = 1; i <= 13; i++) {
				set.accept(shown[2].east(i), (i % 2 == 0 ? Blocks.STONE : Blocks.OAK_PLANKS).defaultBlockState());
			}
			player.teleportTo(level, o.getX() - 5.5, o.getY() + 6, o.getZ() + 4.5, java.util.Set.of(), 0f, 0f, false);
			player.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, o.offset(6, 0, 4).getCenter());
		});
		context.runOnClient(client -> LabConfig.SHOW_TIMELINE.value = false);
		context.waitTicks(10);
		context.runOnClient(client -> {
			ClientPistons.clear();
			for (BlockPos p : shown) {
				ClientPistons.toggle(p);
			}
		});
		// Let the action bar text fade.
		context.waitTicks(80);
		context.takeScreenshot("still-guide-pistons");
		context.runOnClient(client -> ClientPistons.clear());
	}

	/**
	 * Firing order of shown pistons, client-side: exact with the server's ticks, and measured on
	 * the client (as on a server without RedstoneLab). Same build as the server test.
	 */
	private void firingOrder(ClientGameTestContext context, TestSingleplayerContext world) {
		BlockPos[][] pistons = new BlockPos[1][];
		world.getServer().runOnServer(server -> {
			ServerPlayer player = player(server);
			pistons[0] = FiringOrderBuild.build(player.level(), base.offset(-14, 0, -12));
			BlockPos look = base.offset(-12, 1, -11);
			player.teleportTo(player.level(), look.getX() + 4.5, look.getY() + 3, look.getZ() + 5.5, java.util.Set.of(), 0f, 0f, false);
			player.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, look.getCenter());
		});
		context.runOnClient(client -> LabConfig.SHOW_TIMELINE.value = false);
		context.waitTicks(10);
		for (boolean exact : new boolean[]{true, false}) {
			context.runOnClient(client -> {
				ClientPistons.clear();
				ClientState.serverHasMod = exact;
				for (BlockPos p : pistons[0]) {
					ClientPistons.toggle(p);
				}
			});
			context.waitTicks(70);
			String all = context.computeOnClient(client -> {
				StringBuilder sb = new StringBuilder();
				for (BlockPos p : pistons[0]) {
					sb.append(ClientPistons.order(p)).append(" | ");
				}
				return sb.toString();
			});
			String sameTick = all.startsWith("#1 | ") ? "#2 \u00b7 same tick | " : "#1 | ";
			String want = (all.startsWith("#1 | ") ? "#1 | " : "#2 \u00b7 same tick | ") + "#3 \u00b7 +2t | #4 \u00b7 +2t | " + sameTick;
			if (!all.equals(want)) {
				throw new AssertionError((exact ? "exact" : "client-measured") + " firing order: " + all);
			}
			if (exact) {
				this.record(context, 60, "Several pistons shown: #1, #2 ... = the order they fire in, +2t = ticks after the one before");
				context.takeScreenshot("still-order");
			}
		}
		context.runOnClient(client -> {
			ClientPistons.clear();
			ClientState.serverHasMod = true;
		});
	}

	// ------------------------------------------------------------------ client vs server previews

	private static volatile BlockPos boxMin;
	private static volatile BlockPos boxMax;
	private static volatile boolean sampling;
	private static BlockPos comparatorPiston;
	/** Server: block fingerprint -> preview of every still piston. */
	private static final Map<Long, Map<BlockPos, String>> SERVER = new ConcurrentHashMap<>();

	/**
	 * Five random observer-clock piston builds and one piston held out by a comparator. All previews
	 * shown on the client; for 240 ticks every preview the client draws must be the same as the
	 * server's preview for the exact same blocks (matched by a fingerprint of the whole area).
	 */
	private void clientPreviewsMatchServer(ClientGameTestContext context, TestSingleplayerContext world) {
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (sampling) {
				ServerLevel level = server.overworld();
				SERVER.putIfAbsent(fingerprint(level), previews(level));
			}
		});
		world.getServer().runOnServer(server -> {
			ServerLevel level = player(server).level();
			boxMin = base.offset(-12, -1, 8);
			boxMax = base.offset(12, 6, 24);
			java.util.Random random = new java.util.Random(99);
			for (int i = 0; i < 6; i++) {
				BlockPos piston = base.offset(-8 + (i % 3) * 8, 1, 12 + (i / 3) * 8);
				if (i < 5) {
					ClockBuilds.random(level, piston, random);
					continue;
				}
				// Comparator -> sticky piston with a stone in front; the comparator's input (a
				// redstone block) is put down and taken away later, so the piston rests out.
				comparatorPiston = piston;
				level.setBlock(piston, Blocks.STICKY_PISTON.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.NORTH), 2 | 16);
				level.setBlock(piston.north(), Blocks.STONE.defaultBlockState(), 2 | 16);
				level.setBlock(piston.below().west(), Blocks.STONE.defaultBlockState(), 2 | 16);
				level.setBlock(piston.west(), Blocks.COMPARATOR.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.WEST), 3);
			}
			player(server).teleportTo(level, base.getX() + 0.5, base.getY() + 7, base.getZ() + 4, java.util.Set.of(), 0f, 40f, false);
		});
		context.waitTicks(20);
		context.runOnClient(client -> {
			ClientPistons.clear();
			for (BlockPos p : BlockPos.betweenClosed(boxMin, boxMax)) {
				if (client.level.getBlockState(p).getBlock() instanceof PistonBaseBlock) {
					ClientPistons.toggle(p.immutable());
				}
			}
		});
		context.runOnClient(client -> LabConfig.SHOW_TIMELINE.value = false);
		SERVER.clear();
		sampling = true;
		List<Long> clientPrints = new ArrayList<>();
		List<Map<BlockPos, String>> clientViews = new ArrayList<>();
		int[] shownWrong = new int[1];
		Map<BlockPos, String> lastStill = new java.util.HashMap<>();
		List<String> problems = new ArrayList<>();
		boolean[] comparatorOut = new boolean[1];
		for (int t = 0; t < 240; t++) {
			if (t == 40 || t == 140) {
				boolean on = t == 40;
				world.getServer().runOnServer(server -> player(server).level().setBlock(comparatorPiston.west(2),
						on ? Blocks.REDSTONE_BLOCK.defaultBlockState() : Blocks.AIR.defaultBlockState(), 3));
			}
			if (t == 120) {
				comparatorOut[0] = world.getServer().computeOnServer(server -> {
					var s = player(server).level().getBlockState(comparatorPiston);
					return s.is(Blocks.STICKY_PISTON) && s.getValue(BlockStateProperties.EXTENDED);
				});
			}
			context.waitTick();
			context.runOnClient(client -> {
				Map<BlockPos, String> views = previews(client.level);
				clientPrints.add(fingerprint(client.level));
				clientViews.add(views);
				// What is drawn must be the fresh preview for every still piston.
				// (Drawn at the end of the tick, so it may still be the last one before this tick, or
				// not worked out yet when it was shown mid-move.)
				views.forEach((pos, view) -> {
					String drawn = ClientPistons.label(pos);
					boolean notYet = drawn.isEmpty() && !lastStill.containsKey(pos);
					if (ClientPistons.isShown(pos) && !notYet && !view.startsWith(drawn + "\n")
							&& !String.valueOf(lastStill.get(pos)).startsWith(drawn + "\n")) {
						shownWrong[0]++;
						if (problems.size() < 5) {
							problems.add(pos.toShortString() + " draws '" + drawn + "' but is " + view.lines().findFirst().orElse(""));
						}
					}
				});
				lastStill.putAll(views);
			});
			if (t % 3 == 0) {
				context.takeScreenshot("clocks");
			}
		}
		sampling = false;
		int compared = 0;
		int differ = 0;
		int unmatched = 0;
		for (int i = 0; i < clientPrints.size(); i++) {
			Map<BlockPos, String> server = SERVER.get(clientPrints.get(i));
			if (server == null) {
				unmatched++;
				continue;
			}
			for (Map.Entry<BlockPos, String> e : clientViews.get(i).entrySet()) {
				compared++;
				if (!e.getValue().equals(server.get(e.getKey()))) {
					differ++;
					if (problems.size() < 10) {
						problems.add(e.getKey().toShortString() + " client: " + e.getValue().replace("\n", " | ") + " server: "
								+ String.valueOf(server.get(e.getKey())).replace("\n", " | "));
					}
				}
			}
		}
		String summary = compared + " client previews compared with the server's for the same blocks, " + differ + " differ; "
				+ unmatched + "/" + clientPrints.size() + " client ticks had no matching server tick; " + shownWrong[0] + " drawn wrong";
		dev.redstonelab.RedstoneLab.LOGGER.info("clientPreviewsMatchServer: {}", summary);
		problems.forEach(p -> dev.redstonelab.RedstoneLab.LOGGER.info("  {}", p));
		if (!comparatorOut[0]) {
			throw new AssertionError("the comparator-powered piston never extended");
		}
		if (differ > 0 || shownWrong[0] > 0 || compared < 300) {
			throw new AssertionError(summary + ". " + problems);
		}
	}

	private static long fingerprint(net.minecraft.world.level.Level level) {
		long h = 17;
		for (BlockPos p : BlockPos.betweenClosed(boxMin, boxMax)) {
			h = h * 0x9E3779B97F4A7C15L + net.minecraft.world.level.block.Block.getId(level.getBlockState(p)) + 1;
		}
		return h;
	}

	/** Label and markers of every still piston in the box, as the player would see them. */
	private static Map<BlockPos, String> previews(net.minecraft.world.level.Level level) {
		Map<BlockPos, String> out = new java.util.TreeMap<>();
		for (BlockPos p : BlockPos.betweenClosed(boxMin, boxMax)) {
			if (!(level.getBlockState(p).getBlock() instanceof PistonBaseBlock) || PistonView.isMoving(level, p)) {
				continue;
			}
			PistonView.View view = new PistonView.View(p.immutable());
			List<dev.redstonelab.net.LabPayloads.MarkerData> markers = new ArrayList<>();
			PistonView.build(level, view, markers, false);
			List<String> lines = new ArrayList<>();
			markers.forEach(m -> lines.add(m.toString()));
			java.util.Collections.sort(lines);
			out.put(p.immutable(), view.headLabel + "\n" + String.join("\n", lines));
		}
		return out;
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
