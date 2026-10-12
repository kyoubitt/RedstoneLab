package dev.redstonelab.command;

import java.util.Map;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import dev.redstonelab.config.ServerConfig;
import dev.redstonelab.feature.MeterFeature;
import dev.redstonelab.feature.PistorderFeature;
import dev.redstonelab.server.LabServer;
import dev.redstonelab.util.Msg;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

/**
 * {@code /rlab}: everything works through commands too, so players without the mod can use it.
 */
public final class RlabCommand {
	private static final SuggestionProvider<CommandSourceStack> GROUPS =
			(ctx, builder) -> SharedSuggestionProvider.suggest(MeterFeature.groupNames(ctx.getSource().getPlayerOrException()), builder);
	private static final SuggestionProvider<CommandSourceStack> CONFIG_KEYS =
			(ctx, builder) -> SharedSuggestionProvider.suggest(ServerConfig.keys(), builder);

	private RlabCommand() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext buildContext) {
		dispatcher.register(literal("rlab")
				.requires(LabServer::canUse)
				.executes(ctx -> help(ctx.getSource()))
				.then(literal("help").executes(ctx -> help(ctx.getSource())))
				.then(literal("tool").executes(ctx -> {
					ServerPlayer player = player(ctx);
					if (!player.addItem(MeterFeature.createTool())) {
						player.drop(MeterFeature.createTool(), false);
					}
					Msg.ok(ctx.getSource(), "Meter Tool: right-click a block");
					return 1;
				}))
				.then(literal("meter")
						.executes(ctx -> {
							MeterFeature.list(player(ctx));
							return 1;
						})
						.then(literal("clear").executes(ctx -> {
							MeterFeature.clear(player(ctx));
							return 1;
						}))
						.then(literal("expect")
								.then(argument("meter", StringArgumentType.word())
										.suggests((ctx, b) -> SharedSuggestionProvider.suggest(MeterFeature.labels(ctx.getSource().getPlayerOrException()), b))
										.executes(ctx -> {
											MeterFeature.expect(player(ctx), StringArgumentType.getString(ctx, "meter"), null, null, false);
											return 1;
										})
										.then(literal("clear").executes(ctx -> {
											MeterFeature.expect(player(ctx), StringArgumentType.getString(ctx, "meter"), null, null, true);
											return 1;
										}))
										.then(argument("on", IntegerArgumentType.integer(0, 10000))
												.executes(ctx -> {
													MeterFeature.expect(player(ctx), StringArgumentType.getString(ctx, "meter"),
															IntegerArgumentType.getInteger(ctx, "on"), null, false);
													return 1;
												})
												.then(argument("off", IntegerArgumentType.integer(0, 10000)).executes(ctx -> {
													MeterFeature.expect(player(ctx), StringArgumentType.getString(ctx, "meter"),
															IntegerArgumentType.getInteger(ctx, "on"), IntegerArgumentType.getInteger(ctx, "off"), false);
													return 1;
												})))))
						.then(literal("name")
								.then(argument("meter", StringArgumentType.word())
										.suggests((ctx, b) -> SharedSuggestionProvider.suggest(MeterFeature.labels(ctx.getSource().getPlayerOrException()), b))
										.executes(ctx -> {
											MeterFeature.rename(player(ctx), StringArgumentType.getString(ctx, "meter"), "");
											return 1;
										})
										.then(argument("name", StringArgumentType.greedyString()).executes(ctx -> {
											MeterFeature.rename(player(ctx), StringArgumentType.getString(ctx, "meter"),
													StringArgumentType.getString(ctx, "name").trim());
											return 1;
										}))))
						.then(literal("remove")
								.executes(ctx -> {
									MeterFeature.removeOne(player(ctx), null);
									return 1;
								})
								.then(argument("pos", BlockPosArgument.blockPos()).executes(ctx -> {
									MeterFeature.removeOne(player(ctx), pos(ctx));
									return 1;
								})))
						.then(argument("pos", BlockPosArgument.blockPos()).executes(ctx -> {
							ServerPlayer player = player(ctx);
							MeterFeature.toggle(player, player.level(), pos(ctx));
							return 1;
						})))
				.then(literal("piston")
						.then(literal("clear").executes(ctx -> {
							PistorderFeature.clear(player(ctx));
							Msg.actionBar(player(ctx), Component.literal("Piston previews hidden").withStyle(ChatFormatting.GRAY));
							return 1;
						}))
						.then(literal("landing")
								.then(argument("on", com.mojang.brigadier.arguments.BoolArgumentType.bool()).executes(ctx -> {
									boolean on = com.mojang.brigadier.arguments.BoolArgumentType.getBool(ctx, "on");
									PistorderFeature.setOption(player(ctx), "landing", on);
									Msg.ok(ctx.getSource(), "Landing boxes: " + (on ? "shown" : "hidden"));
									return 1;
								}))))
				.then(literal("group")
						.executes(ctx -> {
							MeterFeature.listGroups(player(ctx));
							return 1;
						})
						.then(literal("share")
								.then(argument("name", StringArgumentType.word()).suggests(GROUPS)
										.then(argument("player", EntityArgument.player()).executes(ctx -> {
											MeterFeature.shareGroup(player(ctx), StringArgumentType.getString(ctx, "name"),
													EntityArgument.getPlayer(ctx, "player"));
											return 1;
										}))))
						.then(literal("delete")
								.then(argument("name", StringArgumentType.word()).suggests(GROUPS).executes(ctx -> {
									MeterFeature.deleteGroup(player(ctx), StringArgumentType.getString(ctx, "name"));
									return 1;
								})))
						.then(argument("name", StringArgumentType.word()).suggests(GROUPS).executes(ctx -> {
							MeterFeature.switchGroup(player(ctx), StringArgumentType.getString(ctx, "name"));
							return 1;
						})))
				.then(literal("clear").executes(ctx -> {
					LabServer.stopAll(player(ctx));
					Msg.ok(ctx.getSource(), "Cleared");
					return 1;
				}))
				.then(config()));
	}

	private static ServerPlayer player(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		return ctx.getSource().getPlayerOrException();
	}

	private static BlockPos pos(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		return BlockPosArgument.getLoadedBlockPos(ctx, "pos");
	}

	private static int help(CommandSourceStack source) {
		Msg.ok(source, Component.literal("RedstoneLab").withStyle(ChatFormatting.GOLD));
		source.sendSuccess(() -> Component.literal(" Meters: ").withStyle(ChatFormatting.YELLOW)
				.append(Msg.button("Meter Tool", "/rlab tool", "Get the Meter Tool"))
				.append(Component.literal(" or M on a block").withStyle(ChatFormatting.WHITE)), false);
		source.sendSuccess(() -> Component.literal(" Pistons: ").withStyle(ChatFormatting.YELLOW)
				.append(Component.literal("right-click with an empty hand (P hides)").withStyle(ChatFormatting.WHITE)), false);
		source.sendSuccess(() -> Component.literal(" Game: ").withStyle(ChatFormatting.YELLOW)
				.append(Component.literal("Up freeze / step · Down run · Left / Right slow motion (frozen: tick back / forward) · R + C settings").withStyle(ChatFormatting.WHITE)), false);
		source.sendSuccess(() -> Component.literal(" ")
				.append(Msg.button("Meters", "/rlab meter", "Meter history in chat"))
				.append(" ")
				.append(Msg.button("Groups", "/rlab group", "Saved meter sets"))
				.append(" ")
				.append(Msg.button("Clear", "/rlab clear", "Remove all meters")), false);
		return 1;
	}

	// ------------------------------------------------------------------ server settings

	private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> config() {
		return literal("config")
				.executes(ctx -> printConfig(ctx.getSource()))
				.then(literal("set").requires(s -> Commands.LEVEL_GAMEMASTERS.check(s.permissions()))
						.then(argument("key", StringArgumentType.word()).suggests(CONFIG_KEYS)
								.then(argument("value", StringArgumentType.greedyString()).executes(ctx -> {
									String key = StringArgumentType.getString(ctx, "key");
									String error = ServerConfig.get().set(key, StringArgumentType.getString(ctx, "value"));
									if (error != null) {
										Msg.fail(ctx.getSource(), error);
										return 0;
									}
									for (Map.Entry<String, String> e : ServerConfig.get().describe().entrySet()) {
										if (e.getKey().equalsIgnoreCase(key)) {
											Msg.ok(ctx.getSource(), e.getKey() + " = " + e.getValue() + " (saved)");
										}
									}
									return 1;
								}))));
	}

	private static int printConfig(CommandSourceStack source) {
		Msg.ok(source, Component.literal("Server settings (click to change):").withStyle(ChatFormatting.GOLD));
		for (Map.Entry<String, String> e : ServerConfig.get().describe().entrySet()) {
			source.sendSuccess(() -> Component.literal(" ").append(Msg.suggest(e.getKey(), "/rlab config set " + e.getKey() + " ", "Change this setting"))
					.append(Component.literal(" = " + e.getValue()).withStyle(ChatFormatting.WHITE)), false);
		}
		return 1;
	}
}
