package dev.redstonelab.util;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextColor;
import net.minecraft.server.level.ServerPlayer;

/** Chat and action bar helpers. Plain vanilla text, so vanilla clients see the same. */
public final class Msg {
	private Msg() {
	}

	public static MutableComponent prefix() {
		return Component.literal("[RL] ").withStyle(ChatFormatting.DARK_AQUA);
	}

	public static MutableComponent text(String s) {
		return Component.literal(s);
	}

	public static MutableComponent text(String s, ChatFormatting... formats) {
		return Component.literal(s).withStyle(formats);
	}

	public static MutableComponent colored(String s, int rgb) {
		return Component.literal(s).withStyle(st -> st.withColor(TextColor.fromRgb(rgb & 0xFFFFFF)));
	}

	/** Position; click puts a teleport command into the chat box. */
	public static MutableComponent pos(BlockPos pos) {
		String xyz = pos.getX() + " " + pos.getY() + " " + pos.getZ();
		return Component.literal("[" + xyz + "]")
				.withStyle(st -> st.withColor(ChatFormatting.AQUA)
						.withClickEvent(new ClickEvent.SuggestCommand("/tp @s " + xyz))
						.withHoverEvent(new HoverEvent.ShowText(Component.literal("Click to teleport there"))));
	}

	/** Clickable text that runs a command. */
	public static MutableComponent button(String label, String command, String hover) {
		return Component.literal("[" + label + "]")
				.withStyle(st -> st.withColor(ChatFormatting.GREEN)
						.withClickEvent(new ClickEvent.RunCommand(command))
						.withHoverEvent(new HoverEvent.ShowText(Component.literal(hover))));
	}

	/** Clickable text that puts a command into the chat box. */
	public static MutableComponent suggest(String label, String command, String hover) {
		return Component.literal("[" + label + "]")
				.withStyle(st -> st.withColor(ChatFormatting.YELLOW)
						.withClickEvent(new ClickEvent.SuggestCommand(command))
						.withHoverEvent(new HoverEvent.ShowText(Component.literal(hover))));
	}

	public static MutableComponent hover(String shown, String hover, ChatFormatting color) {
		return Component.literal(shown).withStyle(st -> st.withColor(color)
				.withHoverEvent(new HoverEvent.ShowText(Component.literal(hover))));
	}

	public static void send(ServerPlayer player, Component message) {
		player.sendSystemMessage(prefix().append(message));
	}

	public static void sendRaw(ServerPlayer player, Component message) {
		player.sendSystemMessage(message);
	}

	public static void actionBar(ServerPlayer player, Component message) {
		player.displayClientMessage(message, true);
	}

	public static void ok(CommandSourceStack source, Component message) {
		source.sendSuccess(() -> prefix().append(message), false);
	}

	public static void ok(CommandSourceStack source, String message) {
		ok(source, Component.literal(message));
	}

	public static void fail(CommandSourceStack source, String message) {
		source.sendFailure(prefix().append(Component.literal(message).withStyle(ChatFormatting.RED)));
	}
}
