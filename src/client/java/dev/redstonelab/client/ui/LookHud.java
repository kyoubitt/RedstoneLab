package dev.redstonelab.client.ui;

import java.util.List;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

import dev.redstonelab.feature.Explain;

/**
 * Under the crosshair: what the redstone block you look at is doing and why, and the rule behind
 * it. Works on any server (it reads the world the client sees).
 */
public final class LookHud {
	private static List<String> lines = List.of();
	/** The looked-at torch and since when (game time) it is off without power, for "burnt out". */
	private static BlockPos torch;
	private static long torchOffSince;

	private LookHud() {
	}

	public static void init() {
		ClientTickEvents.END_CLIENT_TICK.register(LookHud::tick);
	}

	/** The lines shown now (for the tests). */
	public static List<String> lines() {
		return lines;
	}

	private static void tick(Minecraft mc) {
		if (!LabConfig.EXPLAIN.value || mc.level == null || mc.player == null
				|| !(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) {
			lines = List.of();
			torch = null;
			return;
		}
		BlockPos pos = hit.getBlockPos();
		BlockState state = mc.level.getBlockState(pos);
		long now = mc.level.getGameTime();
		boolean off = Explain.torchOffUnpowered(mc.level, pos, state);
		if (!off) {
			torch = null;
		} else if (!pos.equals(torch)) {
			torch = pos.immutable();
			torchOffSince = now;
		}
		// A working torch relights 2t after its power goes: off longer without power = burnt out.
		lines = Explain.lines(mc.level, pos, off && now - torchOffSince > 3);
	}

	public static void render(GuiGraphics ctx, DeltaTracker tick) {
		Minecraft mc = Minecraft.getInstance();
		if (lines.isEmpty() || mc.options.hideGui || mc.screen != null) {
			return;
		}
		Font font = mc.font;
		int maxW = Math.min(ctx.guiWidth() - 20, 320);
		int y = ctx.guiHeight() / 2 + 16;
		for (int i = 0; i < lines.size(); i++) {
			int color = i == 0 ? 0xFFFFFFFF : 0xFFAAAAAA;
			for (FormattedCharSequence part : font.split(Component.literal(lines.get(i)), maxW)) {
				int w = font.width(part);
				int x = (ctx.guiWidth() - w) / 2;
				ctx.fill(x - 2, y - 1, x + w + 2, y + font.lineHeight, 0x90000000);
				ctx.drawString(font, part, x, y, color, true);
				y += font.lineHeight + 1;
			}
		}
	}
}
