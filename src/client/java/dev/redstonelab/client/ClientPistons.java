package dev.redstonelab.client;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.piston.PistonBaseBlock;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;

import dev.redstonelab.client.ui.LabConfig;
import dev.redstonelab.feature.PistonView;
import dev.redstonelab.net.LabPayloads.MarkerData;

/**
 * Piston previews on the client, like Pistorder: right-click a piston with an empty hand to show
 * what it will do, again to hide it; sneaking does nothing; P hides all; a new world hides all.
 * Works on any server, with or without RedstoneLab.
 */
public final class ClientPistons {
	public static final String CHANNEL = "pistons-client";
	private static final Map<BlockPos, PistonView.View> SHOWN = new LinkedHashMap<>();
	private static ClientLevel world;

	private ClientPistons() {
	}

	public static void init() {
		UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
			if (!level.isClientSide() || hand != InteractionHand.MAIN_HAND || !player.getMainHandItem().isEmpty() || player.isShiftKeyDown()
					|| !(level.getBlockState(hit.getBlockPos()).getBlock() instanceof PistonBaseBlock)) {
				return InteractionResult.PASS;
			}
			toggle(hit.getBlockPos());
			return InteractionResult.SUCCESS;
		});
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			if (mc.level != world) {
				clear();
				world = mc.level;
			}
			// Every tick: on a fast clock a piston may be still for only one tick at a time.
			if (!SHOWN.isEmpty() && mc.level != null) {
				refresh(mc.level);
			}
		});
	}

	/** Shows the piston's preview, or hides it if it is shown. */
	public static void toggle(BlockPos pos) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || mc.player == null) {
			return;
		}
		if (SHOWN.remove(pos) != null) {
			refresh(mc.level);
			mc.player.displayClientMessage(Component.literal("Piston preview hidden"), true);
			return;
		}
		PistonView.View view = new PistonView.View(pos.immutable());
		SHOWN.put(view.pos, view);
		// Mid-move the preview would be wrong: it fills in when the blocks land.
		Component text = PistonView.isMoving(mc.level, pos) ? Component.literal("Piston preview shown")
				: PistonView.build(mc.level, view, new ArrayList<>(), LabConfig.PISTON_LANDING.value);
		refresh(mc.level);
		mc.player.displayClientMessage(text, true);
	}

	public static void clear() {
		SHOWN.clear();
		ClientState.MARKERS.remove(CHANNEL);
	}

	public static boolean isShown(BlockPos pos) {
		return SHOWN.containsKey(pos);
	}

	/** The piston's label ("✔ push 1"), empty until it was worked out. */
	public static String label(BlockPos pos) {
		PistonView.View view = SHOWN.get(pos);
		return view == null ? "" : view.headLabel;
	}

	private static void refresh(ClientLevel level) {
		List<MarkerData> markers = new ArrayList<>();
		for (Iterator<PistonView.View> it = SHOWN.values().iterator(); it.hasNext(); ) {
			PistonView.View view = it.next();
			if (level.hasChunkAt(view.pos) && !PistonView.isMoving(level, view.pos)) {
				if (!(level.getBlockState(view.pos).getBlock() instanceof PistonBaseBlock)) {
					it.remove();
					continue;
				}
				List<MarkerData> fresh = new ArrayList<>();
				PistonView.build(level, view, fresh, LabConfig.PISTON_LANDING.value);
				view.markers = fresh;
			}
			markers.addAll(view.markers);
		}
		if (markers.isEmpty()) {
			ClientState.MARKERS.remove(CHANNEL);
		} else {
			ClientState.MARKERS.put(CHANNEL, markers);
		}
	}
}
