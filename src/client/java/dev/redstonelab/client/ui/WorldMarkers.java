package dev.redstonelab.client.ui;

import java.util.List;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.gizmos.TextGizmo;
import net.minecraft.world.phys.Vec3;

import dev.redstonelab.client.ClientState;
import dev.redstonelab.net.LabPayloads;

/**
 * Meter outlines and letters, piston previews: drawn with the game's own debug shapes
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

	/** Drew outlines this tick / the tick before (one more refresh clears them when they go). */
	private static boolean drawn;
	private static boolean drawnBefore;

	/**
	 * The game shows this tick's list only while the world runs normally; frozen or in slow
	 * motion it would keep showing old outlines (even removed meters). While we draw, it always
	 * shows the newest list (see the Minecraft mixin).
	 */
	public static boolean needsRefresh() {
		return drawn || drawnBefore;
	}

	public static void emit() {
		drawnBefore = drawn;
		drawn = false;
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.level == null || !LabConfig.SHOW_OUTLINES.value || ClientState.MARKERS.isEmpty()) {
			return;
		}
		drawn = true;
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
}
