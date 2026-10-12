package dev.redstonelab.feature;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import dev.redstonelab.config.ServerConfig;
import dev.redstonelab.server.LabServer;

/**
 * Everything a player does by hand, through the real command tree: every /rlab command, the
 * Meter Tool, groups (saved, shared, reloaded on join), limits, permissions and the boss bars
 * players without the client mod get.
 */
public class FeatureTests {
	private static final String DOT = " · ";

	/** Runs a command as an op player; returns what it said in chat. */
	private static List<String> run(ServerPlayer player, String command) {
		List<String> said = new ArrayList<>();
		CommandSource capture = new CommandSource() {
			@Override
			public void sendSystemMessage(Component message) {
				said.add(message.getString());
			}

			@Override
			public boolean acceptsSuccess() {
				return true;
			}

			@Override
			public boolean acceptsFailure() {
				return true;
			}

			@Override
			public boolean shouldInformAdmins() {
				return false;
			}
		};
		CommandSourceStack source = player.createCommandSourceStack().withSource(capture).withPermission(LevelBasedPermissionSet.OWNER);
		player.level().getServer().getCommands().performPrefixedCommand(source, command);
		return said;
	}

	private static String xyz(GameTestHelper helper, BlockPos rel) {
		BlockPos p = helper.absolutePos(rel);
		return p.getX() + " " + p.getY() + " " + p.getZ();
	}

	/** /rlab meter (add / remove by position), name, expect, remove, clear, tool, piston landing, help. */
	@GameTest(maxTicks = 20)
	public void meterCommands(GameTestHelper helper) {
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		BlockPos a = new BlockPos(1, 1, 1);
		BlockPos b = new BlockPos(3, 1, 1);
		BlockPos c = new BlockPos(5, 1, 1);
		for (BlockPos p : new BlockPos[]{a, b, c}) {
			helper.setBlock(p, Blocks.REDSTONE_LAMP);
		}
		run(player, "rlab meter " + xyz(helper, a));
		run(player, "rlab meter " + xyz(helper, b));
		run(player, "rlab meter " + xyz(helper, c));
		helper.assertTrue(MeterFeature.labels(player).equals(List.of("A", "B", "C")), "3 meters: " + MeterFeature.labels(player));
		run(player, "rlab meter " + xyz(helper, b));
		helper.assertTrue(MeterFeature.labels(player).equals(List.of("A", "C")), "same command again removes it: " + MeterFeature.labels(player));
		run(player, "rlab meter " + xyz(helper, b));
		helper.assertTrue(MeterFeature.labels(player).equals(List.of("A", "C", "B")), "free letter B is used again: " + MeterFeature.labels(player));

		run(player, "rlab meter name A main clock");
		helper.assertTrue(MeterFeature.statusForTest(player, "A").startsWith("main clock" + DOT + "Redstone Lamp"), MeterFeature.statusForTest(player, "A"));
		run(player, "rlab meter name A " + "x".repeat(40));
		helper.assertTrue(MeterFeature.statusForTest(player, "A").startsWith("x".repeat(24) + DOT), "names are cut at 24: " + MeterFeature.statusForTest(player, "A"));
		run(player, "rlab meter name A");
		helper.assertTrue(MeterFeature.statusForTest(player, "A").startsWith("Redstone Lamp"), "name removed: " + MeterFeature.statusForTest(player, "A"));

		run(player, "rlab meter expect A 2 4");
		helper.assertTrue(MeterFeature.statusForTest(player, "A").endsWith("✔ 0 ok"), "expect set: " + MeterFeature.statusForTest(player, "A"));
		run(player, "rlab meter expect A clear");
		helper.assertTrue(!MeterFeature.statusForTest(player, "A").contains("ok"), "expect cleared: " + MeterFeature.statusForTest(player, "A"));
		run(player, "rlab meter expect A");
		helper.assertTrue(!MeterFeature.statusForTest(player, "A").contains("ok"), "no pulse yet: nothing to expect");

		run(player, "rlab meter remove " + xyz(helper, c));
		helper.assertTrue(MeterFeature.labels(player).equals(List.of("A", "B")), "remove by position: " + MeterFeature.labels(player));
		run(player, "rlab meter remove");
		helper.assertTrue(MeterFeature.labels(player).equals(List.of("A")), "remove newest: " + MeterFeature.labels(player));
		run(player, "rlab meter clear");
		helper.assertTrue(MeterFeature.labels(player).isEmpty(), "clear");

		run(player, "rlab tool");
		helper.assertTrue(MeterFeature.isTool(player.getInventory().getSelectedItem()) || player.getInventory().contains(s -> MeterFeature.isTool(s)),
				"/rlab tool gives the Meter Tool");
		run(player, "rlab piston landing true");
		helper.assertTrue(PistorderFeature.options(player).landing, "landing on");
		run(player, "rlab piston landing false");
		List<String> help = run(player, "rlab help");
		String all = String.join("\n", help);
		helper.assertTrue(all.contains("M on a block") && all.contains("empty hand") && all.contains("slow motion"), "help: " + all);
		helper.succeed();
	}

	/** The Meter Tool: right-click a block = meter on / off, sneak + right-click = remove all. */
	@GameTest(maxTicks = 20)
	public void meterTool(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		helper.setBlock(new BlockPos(2, 1, 2), Blocks.REDSTONE_LAMP);
		helper.setBlock(new BlockPos(4, 1, 2), Blocks.REPEATER);
		player.setItemInHand(InteractionHand.MAIN_HAND, MeterFeature.createTool());
		for (BlockPos p : new BlockPos[]{new BlockPos(2, 1, 2), new BlockPos(4, 1, 2)}) {
			BlockPos abs = helper.absolutePos(p);
			MeterFeature.onUseBlock(player, level, InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(abs), Direction.UP, abs, false));
		}
		helper.assertTrue(MeterFeature.count(player) == 2, "tool adds meters: " + MeterFeature.count(player));
		helper.assertTrue(helper.getBlockState(new BlockPos(4, 1, 2)).getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.DELAY) == 1,
				"the click must not change the repeater");
		player.setShiftKeyDown(true);
		BlockPos abs = helper.absolutePos(new BlockPos(2, 1, 2));
		MeterFeature.onUseBlock(player, level, InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(abs), Direction.UP, abs, false));
		player.setShiftKeyDown(false);
		helper.assertTrue(MeterFeature.count(player) == 0, "sneak + right-click removes all: " + MeterFeature.count(player));
		helper.succeed();
	}

	/**
	 * Groups: a new group starts empty, switching back brings the meters (with names and expected
	 * lengths) back, the active group can't be deleted, a shared group arrives as a copy, and
	 * everything is saved: leaving and joining again restores it.
	 */
	@GameTest(maxTicks = 20)
	public void groupsSavedSharedReloaded(GameTestHelper helper) {
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		ServerPlayer friend = helper.makeMockServerPlayerInLevel();
		helper.setBlock(new BlockPos(1, 1, 1), Blocks.REDSTONE_LAMP);
		helper.setBlock(new BlockPos(3, 1, 1), Blocks.REDSTONE_LAMP);
		run(player, "rlab meter " + xyz(helper, new BlockPos(1, 1, 1)));
		run(player, "rlab meter " + xyz(helper, new BlockPos(3, 1, 1)));
		run(player, "rlab meter name B output");
		run(player, "rlab meter expect A 4 4");
		run(player, "rlab group door");
		helper.assertTrue(MeterFeature.count(player) == 0, "new group is empty");
		run(player, "rlab group default");
		helper.assertTrue(MeterFeature.count(player) == 2 && MeterFeature.statusForTest(player, "B").startsWith("output" + DOT), "back: " + MeterFeature.labels(player));
		run(player, "rlab group delete default");
		helper.assertTrue(MeterFeature.groupNames(player).contains("default"), "the active group can't be deleted");
		run(player, "rlab group delete door");
		helper.assertTrue(!MeterFeature.groupNames(player).contains("door"), "other groups can: " + MeterFeature.groupNames(player));
		MeterFeature.shareGroup(player, "default", friend);
		helper.assertTrue(MeterFeature.groupNames(friend).contains("default"), "shared: " + MeterFeature.groupNames(friend));
		// Leave and join again: everything comes back from the save file.
		MeterFeature.onLeave(player);
		helper.assertTrue(MeterFeature.count(player) == 0, "gone while away");
		MeterFeature.onJoin(player);
		helper.assertTrue(MeterFeature.count(player) == 2, "restored on join: " + MeterFeature.count(player));
		helper.assertTrue(MeterFeature.statusForTest(player, "B").startsWith("output" + DOT) && MeterFeature.statusForTest(player, "A").endsWith("✔ 0 ok"),
				"names and expected lengths kept: " + MeterFeature.statusForTest(player, "A") + " / " + MeterFeature.statusForTest(player, "B"));
		MeterFeature.clear(player);
		helper.succeed();
	}

	/** Server settings: the meter limit holds, bad values are refused, everything is reported. */
	@GameTest(maxTicks = 20)
	public void serverSettings(GameTestHelper helper) {
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		int before = ServerConfig.get().maxMeters;
		try {
			List<String> said = run(player, "rlab config set maxMeters 2");
			helper.assertTrue(ServerConfig.get().maxMeters == 2 && String.join(" ", said).contains("maxMeters = 2"), "set: " + said);
			List<String> bad = run(player, "rlab config set maxMeters lots");
			helper.assertTrue(ServerConfig.get().maxMeters == 2 && !bad.isEmpty(), "bad value refused: " + bad);
			for (int x = 1; x <= 5; x += 2) {
				helper.setBlock(new BlockPos(x, 1, 1), Blocks.REDSTONE_LAMP);
				run(player, "rlab meter " + xyz(helper, new BlockPos(x, 1, 1)));
			}
			helper.assertTrue(MeterFeature.count(player) == 2, "limit of 2 holds: " + MeterFeature.count(player));
			List<String> list = run(player, "rlab config");
			helper.assertTrue(String.join(" ", list).contains("permissionLevel"), "config lists the settings: " + list);
		} finally {
			ServerConfig.get().set("maxMeters", String.valueOf(before));
			MeterFeature.clear(player);
		}
		helper.succeed();
	}

	/** Permissions: a normal player can't use RedstoneLab at the default level 2; level 0 lets everyone. */
	@GameTest(maxTicks = 20)
	public void permissions(GameTestHelper helper) {
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		int before = ServerConfig.get().permissionLevel;
		try {
			ServerConfig.get().set("permissionLevel", "2");
			helper.assertTrue(LabServer.canUse(player.createCommandSourceStack().withPermission(LevelBasedPermissionSet.GAMEMASTER)), "ops can");
			helper.assertTrue(!LabServer.canUse(player.createCommandSourceStack().withPermission(LevelBasedPermissionSet.ALL)), "normal players can't");
			ServerConfig.get().set("permissionLevel", "0");
			helper.assertTrue(LabServer.canUse(player.createCommandSourceStack().withPermission(LevelBasedPermissionSet.ALL)), "level 0: everyone");
		} finally {
			ServerConfig.get().set("permissionLevel", String.valueOf(before));
		}
		helper.succeed();
	}

	/** Players without the client mod get a boss bar per meter, gone when the meter is. */
	@GameTest(maxTicks = 20)
	public void bossBarsForVanillaClients(GameTestHelper helper) {
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		helper.setBlock(new BlockPos(1, 1, 1), Blocks.REDSTONE_LAMP);
		run(player, "rlab meter " + xyz(helper, new BlockPos(1, 1, 1)));
		helper.runAtTickTime(6, () -> {
			helper.assertTrue(MeterFeature.bossBarForTest(player, "A"), "boss bar shown");
			run(player, "rlab meter clear");
			helper.succeed();
		});
	}
}
