package dev.redstonelab.client.malilib;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import org.joml.Matrix4f;

import fi.dy.masa.malilib.config.HudAlignment;
import fi.dy.masa.malilib.interfaces.IRenderer;
import fi.dy.masa.malilib.render.GuiContext;
import fi.dy.masa.malilib.render.MaLiLibPipelines;
import fi.dy.masa.malilib.render.RenderContext;
import fi.dy.masa.malilib.render.RenderUtils;
import fi.dy.masa.malilib.util.data.Color4f;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.BlockPos;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import dev.redstonelab.RedstoneLab;
import dev.redstonelab.client.ClientState;
import dev.redstonelab.core.MeterEvent;
import dev.redstonelab.core.TickPhase;
import dev.redstonelab.net.LabPayloads;

/**
 * Meter timeline HUD and in-world outlines/labels (meters, piston previews) for modded clients.
 */
public final class LabRenderer implements IRenderer {
	public static final LabRenderer INSTANCE = new LabRenderer();

	private static final double MAX_DISTANCE = 96;
	private static final int MAX_MARKERS = 400;

	private LabRenderer() {
	}

	// ------------------------------------------------------------------ HUD

	@Override
	public void onRenderGameOverlayPostAdvanced(GuiContext ctx, float partialTicks, ProfilerFiller profiler) {
		Minecraft mc = ctx.mc();
		if (mc.player == null || mc.options.hideGui || mc.screen != null || ClientState.SCOPE.isEmpty()
				|| !LabConfigs.Settings.SHOW_TIMELINE.getBooleanValue()) {
			return;
		}
		profiler.push("redstonelab_scope");
		this.renderScope(ctx, mc.font);
		profiler.pop();
	}

	/** Events about the block itself, drawn in the upper row of the event lane. */
	private static final int BLOCK_EVENTS = MeterEvent.TURNED_ON.bit() | MeterEvent.TURNED_OFF.bit() | MeterEvent.POWER_CHANGED.bit()
			| MeterEvent.POWERED.bit() | MeterEvent.UNPOWERED.bit()
			| MeterEvent.MOVED.bit() | MeterEvent.PLACED.bit() | MeterEvent.BROKEN.bit()
			| MeterEvent.ITEM_IN.bit() | MeterEvent.ITEM_OUT.bit();
	private static final int LANE_H = 6;

	private static final int PX = 3;

	/*
	 * The box only grows while the meters stay the same, so it doesn't jump around when a
	 * line of text gets shorter for a moment (a block being moved, a new event colour, ...).
	 */
	private String layoutKey = "";
	private int stableWidth;
	private int legendFlags;

	private void renderScope(GuiContext ctx, Font font) {
		int visible = Math.min(LabConfigs.Settings.TIMELINE_TICKS.getIntegerValue(), ClientState.CAPACITY);
		int width = visible * PX;
		int chH = 11;
		boolean marks = LabConfigs.Settings.EVENT_MARKS.getBooleanValue();
		boolean showTitle = LabConfigs.Settings.SHOW_TITLE.getBooleanValue();
		boolean showInfo = LabConfigs.Settings.SHOW_INFO.getBooleanValue();
		// The colour legend only while frozen: that's when you read the marks closely.
		boolean showLegend = marks && LabConfigs.Settings.SHOW_LEGEND.getBooleanValue() && LabConfigs.Keys.PAUSE_TIMELINE.getBooleanValue();
		int laneH = marks ? LANE_H : 0;
		int labelW = 12;
		// One line per meter: the info text sits right of the bar.
		int rowH = chH + laneH + 2;
		String orderText = showInfo && !ClientState.order.isEmpty() ? "Order: " + ClientState.order : "";
		int titleH = (showTitle ? 13 : 3) + (orderText.isEmpty() ? 0 : font.lineHeight + 2);
		boolean paused = LabConfigs.Keys.PAUSE_TIMELINE.getBooleanValue();
		int scroll = paused ? LabCallbacks.scroll : 0;
		String name = "default".equals(ClientState.group) ? "Meters" : "Meters: " + ClientState.group;
		String title = paused ? name + " [frozen" + (scroll > 0 ? " -" + scroll + "t" : "") + "]" : name;

		String key = ClientState.layoutVersion + "," + visible + "," + marks + showTitle + showInfo + showLegend;
		if (!key.equals(this.layoutKey)) {
			this.layoutKey = key;
			this.stableWidth = 0;
			this.legendFlags = 0;
		}
		// Wide enough for every line of text, so nothing spills out of the panel.
		int infoX = labelW + width + 6;
		int panelW = infoX;
		if (showTitle) {
			panelW = Math.max(panelW, font.width(title) + 6);
		}
		panelW = Math.max(panelW, font.width(orderText) + 6);
		if (showInfo) {
			for (ClientState.Channel ch : ClientState.SCOPE) {
				panelW = Math.max(panelW, infoX + font.width(ch.stats) + 4);
			}
		}
		this.stableWidth = Math.max(this.stableWidth, panelW);
		// Never wider than the screen: long info text is cut with "..." instead.
		float panelScale = hudScale(ctx.mc());
		int maxW = (int) (ctx.guiWidth() / panelScale) - 2 * LabConfigs.Settings.OFFSET_X.getIntegerValue();
		panelW = Math.max(infoX, Math.min(this.stableWidth, maxW));
		if (showLegend) {
			this.legendFlags |= visibleEvents(visible, scroll);
		}
		List<List<MeterEvent>> legend = showLegend ? legendLines(font, panelW - 4, this.legendFlags) : List.of();
		// While frozen: one line explaining the bars (+ the mark colours when marks are on).
		boolean showHint = LabConfigs.Settings.SHOW_LEGEND.getBooleanValue() && LabConfigs.Keys.PAUSE_TIMELINE.getBooleanValue();
		int legendH = showHint ? (legend.size() + 1) * (font.lineHeight + 1) + 2 : 0;
		// Frozen: a cursor on the newest shown tick and a list of what happened in it, in order.
		List<Inspected> inspected = paused && LabConfigs.Settings.SHOW_INSPECT.getBooleanValue() ? inspect(scroll) : null;
		String inspectTitle = "";
		if (inspected != null) {
			int idx = ClientState.sampleIndex(scroll);
			inspectTitle = "Tick " + (idx >= 0 ? ClientState.sampleTimes[idx] : "?") + (scroll == 0 ? " (newest)" : " (" + scroll + "t ago)")
					+ (inspected.isEmpty() ? ": nothing happened" : "");
			int w = font.width(inspectTitle);
			for (Inspected in : inspected) {
				w = Math.max(w, font.width(in.text()) + (in.heading() ? 0 : 6));
			}
			panelW = Math.max(panelW, w + 8);
		}
		int inspectH = inspected == null ? 0 : (1 + Math.min(inspected.size(), MAX_INSPECT_LINES)) * (font.lineHeight + 1) + 3;
		int panelH = titleH + rowH * ClientState.SCOPE.size() + legendH + inspectH;
		float scale = hudScale(ctx.mc());

		int screenW = (int) (ctx.guiWidth() / scale);
		int screenH = (int) (ctx.guiHeight() / scale);
		HudAlignment align = LabConfigs.Settings.TIMELINE_POSITION.getOptionListValue() instanceof HudAlignment a ? a : HudAlignment.TOP_LEFT;
		int ox = LabConfigs.Settings.OFFSET_X.getIntegerValue();
		int oy = LabConfigs.Settings.OFFSET_Y.getIntegerValue();
		int x = switch (align) {
			case TOP_RIGHT, BOTTOM_RIGHT -> screenW - panelW - ox;
			case CENTER -> (screenW - panelW) / 2;
			default -> ox;
		};
		int y = switch (align) {
			case BOTTOM_LEFT, BOTTOM_RIGHT -> screenH - panelH - oy;
			case CENTER -> (screenH - panelH) / 2;
			default -> oy;
		};

		// Smooth scrolling: between two samples everything slides left by up to one column.
		float shift = 0;
		if (!paused && LabConfigs.Settings.SMOOTH_SCROLL.getBooleanValue()) {
			float sinceSample = (System.nanoTime() - ClientState.lastSampleNanos) / 50_000_000f;
			shift = Math.max(0, Math.min(1, sinceSample)) * PX;
		}

		ctx.pose().pushMatrix();
		ctx.pose().scale(scale, scale);
		if (LabConfigs.Settings.SHOW_BACKGROUND.getBooleanValue()) {
			int alpha = Math.round(LabConfigs.Settings.BACKGROUND_OPACITY.getIntegerValue() * 2.55f);
			ctx.fill(x, y, x + panelW, y + panelH, (alpha << 24) | 0x101010);
		}
		if (showTitle) {
			ctx.drawString(font, title, x + 3, y + 3, 0xFFFFFFFF, true);
		}
		if (!orderText.isEmpty()) {
			ctx.drawString(font, orderText, x + 3, y + titleH - font.lineHeight - 2, 0xFFFFDD77, true);
		}

		for (int ci = 0; ci < ClientState.SCOPE.size(); ci++) {
			ClientState.Channel ch = ClientState.SCOPE.get(ci);
			int top = y + titleH + ci * rowH;
			int bottom = top + chH;
			int left = x + labelW;
			ctx.fill(left, top, left + width, bottom, 0x20FFFFFF);
			ctx.drawString(font, ch.label, x + 3, top + (chH - font.lineHeight) / 2 + 1, ch.color, true);

			ctx.enableScissor(left, top, left + width, bottom + laneH + 1);
			this.drawTimeline(ctx, font, ch, visible, scroll, left, top, chH, shift, marks);
			ctx.disableScissor();
			if (inspected != null) {
				int cx = colX(left, visible - 1, 0);
				ctx.fill(cx - 1, top - 1, cx + PX + 1, bottom + laneH + 1, 0x70FFFFFF);
			}

			if (showInfo) {
				ctx.drawString(font, fit(font, ch.stats, panelW - infoX - 4), x + infoX, top + (chH - font.lineHeight) / 2 + 1, 0xFFDDDDDD, true);
			}
		}
		int ly = y + titleH + rowH * ClientState.SCOPE.size();
		if (showHint) {
			ctx.drawString(font, "bar = on, thin line = powered (not on yet)", x + 3, ly, 0xFFCCCCCC, true);
			ly += font.lineHeight + 1;
		}
		for (List<MeterEvent> line : legend) {
			int lx = x + 3;
			for (MeterEvent e : line) {
				ctx.fill(lx, ly + 1, lx + 6, ly + 7, e.color());
				ctx.drawString(font, e.displayName(), lx + 8, ly, 0xFFCCCCCC, true);
				lx += legendItemWidth(font, e);
			}
			ly += font.lineHeight + 1;
		}
		if (inspected != null) {
			ly += 2;
			ctx.drawString(font, inspectTitle, x + 3, ly, 0xFFFFFFFF, true);
			ly += font.lineHeight + 1;
			for (int i = 0; i < Math.min(inspected.size(), MAX_INSPECT_LINES); i++) {
				Inspected in = inspected.get(i);
				ctx.drawString(font, in.text(), in.heading() ? x + 3 : x + 9, ly, in.color(), true);
				ly += font.lineHeight + 1;
			}
		}
		ctx.pose().popMatrix();
	}

	private static final int MAX_INSPECT_LINES = 10;

	/**
	 * The timeline is the same size at every GUI scale: 2 screen pixels per font pixel (sharp
	 * text), times the size setting. Without this it gets huge at GUI scale 3-4.
	 */
	private static float hudScale(Minecraft mc) {
		double gui = mc.getWindow().getGuiScale();
		float fit = gui > 2 ? (float) (2.0 / gui) : 1f;
		return fit * (float) LabConfigs.Settings.TIMELINE_SCALE.getDoubleValue();
	}

	/** Cuts {@code text} to {@code width} pixels with "..." so it never runs off the screen. */
	private static String fit(Font font, String text, int width) {
		if (font.width(text) <= width) {
			return text;
		}
		String cut = font.plainSubstrByWidth(text, Math.max(0, width - font.width("...")));
		return cut + "...";
	}

	/** One line of the "what happened this tick" list; headings name the part of the tick. */
	private record Inspected(int seq, String text, int color, boolean heading) {
	}

	/**
	 * Everything that happened at the meters in the tick {@code age} ticks back, in the order it
	 * happened, under headings for the part of the tick it happened in (block ticks, block events...).
	 */
	private static List<Inspected> inspect(int age) {
		List<Inspected> list = new ArrayList<>();
		int idx = ClientState.sampleIndex(age);
		if (idx < 0) {
			return list;
		}
		MeterEvent[] events = MeterEvent.values();
		// Block / shape updates and level changes happen all the time: only listed in detail mode.
		int hidden = LabConfigs.Settings.EVENT_MARKS.getBooleanValue() ? 0
				: MeterEvent.BLOCK_UPDATE.bit() | MeterEvent.SHAPE_UPDATE.bit() | MeterEvent.POWER_CHANGED.bit();
		for (ClientState.Channel ch : ClientState.SCOPE) {
			int[] order = ch.order[idx];
			if (order == null) {
				continue;
			}
			for (int packed : order) {
				int ordinal = packed & 31;
				if (ordinal < events.length && (events[ordinal].bit() & hidden) == 0) {
					// keep the phase in the seq's low bits for the headings below
					list.add(new Inspected(packed, ch.label + "  " + events[ordinal].displayName(), ch.color, false));
				}
			}
		}
		list.sort((a, b) -> Integer.compare(a.seq() >>> 8, b.seq() >>> 8));
		List<Inspected> lines = new ArrayList<>(list.size() + 4);
		int phase = -1;
		int n = 0;
		for (Inspected in : list) {
			int p = (in.seq() >>> 5) & 7;
			if (p != phase) {
				phase = p;
				lines.add(new Inspected(0, TickPhase.byOrdinal(p).displayName(), 0xFFFFDD77, true));
			}
			lines.add(new Inspected(in.seq(), (++n) + ". " + in.text(), in.color(), false));
		}
		if (lines.size() > MAX_INSPECT_LINES) {
			int more = 0;
			for (int i = MAX_INSPECT_LINES - 1; i < lines.size(); i++) {
				more += lines.get(i).heading() ? 0 : 1;
			}
			lines = new ArrayList<>(lines.subList(0, MAX_INSPECT_LINES - 1));
			lines.add(new Inspected(Integer.MAX_VALUE, "... " + more + " more", 0xFF888888, false));
		}
		return lines;
	}

	/** Left edge of column {@code i} (0 = oldest visible, one extra older column at -1). */
	private static int colX(int left, int i, float shift) {
		return left + Math.round(i * PX - shift);
	}

	private void drawTimeline(GuiContext ctx, Font font, ClientState.Channel ch, int visible, int scroll, int left, int top, int chH,
	                          float shift, boolean marks) {
		int bottom = top + chH;
		int barColor = (ch.color & 0x00FFFFFF) | 0xD0000000;
		// One extra column on each side so the sliding edges stay filled.
		int first = -1;
		int last = visible;
		// Light line every 10 game ticks, so durations can be counted at a glance.
		for (int i = first; i <= last; i++) {
			int idx = ClientState.sampleIndex(scroll + (visible - 1 - i));
			if (idx >= 0 && ClientState.sampleTimes[idx] % 10 == 0) {
				int sx = colX(left, i, shift);
				ctx.fill(sx, top, sx + 1, bottom, 0x40FFFFFF);
			}
		}
		// Runs of on / off ticks, labelled with their length when there is room.
		int runStart = Integer.MIN_VALUE;
		boolean runOn = false;
		boolean runBounded = false;
		for (int i = first; i <= last + 1; i++) {
			int idx = i <= last ? ClientState.sampleIndex(scroll + (visible - 1 - i)) : -1;
			boolean present = idx >= 0;
			int bits = present ? ch.bits[idx] : 0;
			boolean on = (bits & 1) != 0;
			if (present) {
				int sx = colX(left, i, shift);
				int ex = colX(left, i + 1, shift);
				if (on) {
					ctx.fill(sx, top + 1, ex, bottom - 1, barColor);
				} else if ((bits & 4) != 0) {
					// Turned on and off again inside this tick (0-tick pulse).
					ctx.fill(sx + 1, top + 1, sx + 2, bottom - 1, 0xFFFFFFFF);
				} else if ((bits & 2) != 0 && !ch.container) {
					// Powered but not on yet (repeater delay, piston waiting): a thin line.
					ctx.fill(sx, bottom - 3, ex, bottom - 1, barColor);
				}
				if (marks && ch.events[idx] != 0) {
					this.drawMarks(ctx, ch.events[idx], sx, ex, bottom);
				}
			}
			if (runStart != Integer.MIN_VALUE && (!present || on != runOn)) {
				// A run ended at i - 1. Off runs are only labelled between two pulses.
				boolean closed = present;
				if (runBounded && (runOn || closed) && !ch.container && LabConfigs.Settings.SHOW_PULSE_LENGTHS.getBooleanValue()) {
					boolean wrong = present && MeterEvent.WRONG_LENGTH.in(ch.events[idx]);
					this.drawRunLength(ctx, font, i - runStart, colX(left, runStart, shift), colX(left, i, shift), top, chH, runOn, wrong);
				}
				runStart = Integer.MIN_VALUE;
			}
			if (present && runStart == Integer.MIN_VALUE) {
				runBounded = ClientState.sampleIndex(scroll + visible - i) >= 0;
				runStart = i;
				runOn = on;
			}
		}
	}

	/** Writes how many ticks a pulse (or the gap between two pulses) lasted, if it fits. */
	private void drawRunLength(GuiContext ctx, Font font, int ticks, int sx, int ex, int top, int chH, boolean on, boolean wrong) {
		if (wrong) {
			// Different from the expected length: red stripe over the whole pulse / gap.
			ctx.fill(sx, top, ex, top + 2, MeterEvent.WRONG_LENGTH.color());
		}
		String text = Integer.toString(ticks);
		int w = font.width(text);
		if (ex - sx < w + 2) {
			return;
		}
		if (wrong && !on) {
			ctx.drawString(font, text, sx + (ex - sx - w) / 2 + 1, top + (chH - font.lineHeight) / 2 + 1, 0xFFFF4444, false);
			return;
		}
		ctx.drawString(font, text, sx + (ex - sx - w) / 2 + 1, top + (chH - font.lineHeight) / 2 + 1, on ? 0xFF000000 : 0xFF888888, false);
	}

	/**
	 * Event lane under a waveform column: the upper row shows what happened to the block, the
	 * lower row what the game did to it. With several events in one row the most important one
	 * (lowest {@link MeterEvent} ordinal) wins.
	 */
	private void drawMarks(GuiContext ctx, int flags, int sx, int ex, int bottom) {
		if (MeterEvent.WRONG_LENGTH.in(flags)) {
			ctx.fill(sx, bottom + 1, ex, bottom + 6, MeterEvent.WRONG_LENGTH.color());
			return;
		}
		int blockFlags = flags & BLOCK_EVENTS;
		int gameFlags = flags & ~BLOCK_EVENTS & ~MeterEvent.WRONG_LENGTH.bit();
		if (blockFlags != 0) {
			ctx.fill(sx, bottom + 1, ex, bottom + 3, MeterEvent.of(blockFlags).get(0).color());
		}
		if (gameFlags != 0) {
			ctx.fill(sx, bottom + 4, ex, bottom + 6, MeterEvent.of(gameFlags).get(0).color());
		}
	}

	/** Every event flag drawn somewhere in the visible part of the HUD. */
	private static int visibleEvents(int visible, int scroll) {
		int flags = 0;
		for (int i = 0; i < visible; i++) {
			int idx = ClientState.sampleIndex(scroll + i);
			if (idx < 0) {
				break;
			}
			for (ClientState.Channel ch : ClientState.SCOPE) {
				int f = ch.events[idx];
				if (MeterEvent.WRONG_LENGTH.in(f)) {
					flags |= MeterEvent.WRONG_LENGTH.bit();
					continue;
				}
				f &= ~MeterEvent.WRONG_LENGTH.bit();
				// Only the winner of each row is drawn, see drawMarks.
				if ((f & BLOCK_EVENTS) != 0) {
					flags |= MeterEvent.of(f & BLOCK_EVENTS).get(0).bit();
				}
				if ((f & ~BLOCK_EVENTS) != 0) {
					flags |= MeterEvent.of(f & ~BLOCK_EVENTS).get(0).bit();
				}
			}
		}
		return flags;
	}

	private static int legendItemWidth(Font font, MeterEvent e) {
		return 8 + font.width(e.displayName()) + 6;
	}

	private static List<List<MeterEvent>> legendLines(Font font, int maxWidth, int flags) {
		List<List<MeterEvent>> lines = new ArrayList<>();
		List<MeterEvent> line = new ArrayList<>();
		int w = 0;
		for (MeterEvent e : MeterEvent.of(flags)) {
			int iw = legendItemWidth(font, e);
			if (!line.isEmpty() && w + iw > maxWidth) {
				lines.add(line);
				line = new ArrayList<>();
				w = 0;
			}
			line.add(e);
			w += iw;
		}
		if (!line.isEmpty()) {
			lines.add(line);
		}
		return lines;
	}

	// ------------------------------------------------------------------ world

	@Override
	public void onRenderWorldLastAdvanced(RenderTarget fb, Matrix4f posMatrix, Matrix4f projMatrix, Frustum frustum, Camera camera,
	                                      RenderBuffers buffers, ProfilerFiller profiler) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.level == null || !LabConfigs.Settings.SHOW_OUTLINES.getBooleanValue() || ClientState.MARKERS.isEmpty()) {
			return;
		}
		profiler.push("redstonelab_markers");
		Vec3 cam = mc.player.getEyePosition();
		double maxDistSq = MAX_DISTANCE * MAX_DISTANCE;
		int budget = MAX_MARKERS;

		List<LabPayloads.MarkerData> outlines = new ArrayList<>();
		List<LabPayloads.MarkerData> labels = new ArrayList<>();
		for (Map.Entry<String, List<LabPayloads.MarkerData>> e : ClientState.MARKERS.entrySet()) {
			for (LabPayloads.MarkerData m : e.getValue()) {
				if (outlines.size() + labels.size() >= budget) {
					break;
				}
				if (m.pos().getCenter().distanceToSqr(cam) > maxDistSq) {
					continue;
				}
				if ((m.style() & (LabPayloads.STYLE_OUTLINE | LabPayloads.STYLE_FILL)) != 0) {
					outlines.add(m);
				}
				if ((m.style() & LabPayloads.STYLE_LABEL) != 0 && !m.text().isEmpty()) {
					labels.add(m);
				}
			}
		}
		this.renderOutlines(outlines, true);
		this.renderLabels(mc, labels, true);
		profiler.pop();
	}

	private void renderOutlines(List<LabPayloads.MarkerData> outlines, boolean through) {
		if (outlines.isEmpty()) {
			return;
		}
		try (RenderContext ctx = new RenderContext(() -> "redstonelab:outlines",
				through ? MaLiLibPipelines.DEBUG_LINES_MASA_SIMPLE_NO_DEPTH_NO_CULL : MaLiLibPipelines.DEBUG_LINES_MASA_SIMPLE_LEQUAL_DEPTH)) {
			BufferBuilder buffer = ctx.getBuilder();
			for (LabPayloads.MarkerData m : outlines) {
				RenderUtils.drawBlockBoundingBoxOutlinesBatchedLinesSimple(m.pos(), Color4f.fromColor(m.color()), 0.003, 2.0f, buffer);
			}
			MeshData meshData = buffer.build();
			if (meshData != null) {
				ctx.draw(meshData, false, true);
				meshData.close();
			}
		} catch (Exception e) {
			RedstoneLab.LOGGER.debug("Outline rendering failed", e);
		}
	}

	private void renderLabels(Minecraft mc, List<LabPayloads.MarkerData> labels, boolean through) {
		Entity camera = mc.getCameraEntity();
		if (camera == null) {
			return;
		}
		float scale = 0.025f;
		int bg = 0x60000000;
		for (LabPayloads.MarkerData m : labels) {
			BlockPos p = m.pos();
			List<String> lines = Arrays.asList(m.text().split("\n"));
			try {
				RenderUtils.drawTextPlate(lines, p.getX() + 0.5, p.getY() + m.yOffset(), p.getZ() + 0.5,
						camera.getYRot(), camera.getXRot(), scale, m.color() | 0xFF000000, bg, through);
			} catch (Exception e) {
				RedstoneLab.LOGGER.debug("Label rendering failed", e);
				return;
			}
		}
	}
}
