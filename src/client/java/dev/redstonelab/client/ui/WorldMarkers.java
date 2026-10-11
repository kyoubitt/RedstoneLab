package dev.redstonelab.client.ui;

import java.util.List;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.gizmos.TextGizmo;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import dev.redstonelab.client.ClientItems;
import dev.redstonelab.client.ClientState;
import dev.redstonelab.core.ItemLife;
import dev.redstonelab.net.LabPayloads;

/**
 * Meter outlines and letters, piston previews, item labels: drawn with the game's own debug shapes
 * ("gizmos"), seen through walls. Added once per client tick to the game's per-tick list, which
 * it keeps drawing every frame until the next tick.
 */
public final class WorldMarkers {
	private static final double MAX_DISTANCE = 96;
	private static final int MAX_MARKERS = 400;
	private static final float TEXT_SCALE = 0.45f;
	private static final double LINE = 0.3;

	private WorldMarkers() {
	}

	public static void emit() {
		Minecraft mc = Minecraft.getInstance();
		// The game only clears this list while it runs: frozen (/tick freeze), the last one stays up.
		if (mc.player == null || mc.level == null || !mc.level.tickRateManager().runsNormally()) {
			return;
		}
		items(mc);
		if (!LabConfig.SHOW_OUTLINES.value) {
			return;
		}
		Vec3 eye = mc.player.getEyePosition();
		double maxDistSq = MAX_DISTANCE * MAX_DISTANCE;
		int budget = MAX_MARKERS;
		for (Map.Entry<String, List<LabPayloads.MarkerData>> e : ClientState.MARKERS.entrySet()) {
			for (LabPayloads.MarkerData m : e.getValue()) {
				if (budget-- <= 0) {
					return;
				}
				BlockPos p = m.pos();
				if (p.getCenter().distanceToSqr(eye) > maxDistSq) {
					continue;
				}
				int color = m.color() | 0xFF000000;
				if ((m.style() & (LabPayloads.STYLE_OUTLINE | LabPayloads.STYLE_FILL)) != 0) {
					Gizmos.cuboid(p, GizmoStyle.stroke(color, 2f)).setAlwaysOnTop();
				}
				if ((m.style() & LabPayloads.STYLE_LABEL) != 0 && !m.text().isEmpty()) {
					String[] lines = m.text().split("\n");
					for (int i = 0; i < lines.length; i++) {
						Vec3 at = new Vec3(p.getX() + 0.5, p.getY() + m.yOffset() + (lines.length - 1 - i) * LINE, p.getZ() + 0.5);
						Gizmos.billboardText(lines[i], at, TextGizmo.Style.forColorAndCentered(color).withScale(TEXT_SCALE)).setAlwaysOnTop();
					}
				}
			}
		}
	}

	/** Item labels: name, then despawn / pickup / hopper / speed, with the server's numbers. */
	private static void items(Minecraft mc) {
		int[] ids = ClientItems.ids;
		int[] ages = ClientItems.ages;
		int[] pickups = ClientItems.pickups;
		int[] hoppers = ClientItems.hoppers;
		float[] speeds = ClientItems.speeds;
		int n = Math.min(Math.min(ids.length, ages.length), Math.min(pickups.length, Math.min(hoppers.length, speeds.length)));
		ItemLife.Hopper[] kinds = ItemLife.Hopper.values();
		for (int i = 0; i < n; i++) {
			if (!(mc.level.getEntity(ids[i]) instanceof ItemEntity item)) {
				continue;
			}
			ItemStack stack = item.getItem();
			ItemLife.Hopper hopper = kinds[Math.min(hoppers[i] & 15, kinds.length - 1)];
			String[] lines = ItemLife.label(stack.getCount(), stack.getHoverName().getString(), ages[i], pickups[i], hopper, hoppers[i] >> 4, speeds[i])
					.split("\n");
			TextGizmo.Style style = TextGizmo.Style.forColorAndCentered(ItemLife.color(ages[i])).withScale(TEXT_SCALE);
			for (int l = 0; l < lines.length; l++) {
				Gizmos.billboardText(lines[l], item.position().add(0, 0.6 + (lines.length - 1 - l) * LINE, 0), style).setAlwaysOnTop();
			}
		}
	}
}
