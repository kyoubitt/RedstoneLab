package dev.redstonelab.client.ui;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

/**
 * The settings menu (R + C, or Mod Menu). Tabs by type: Timeline, Layout, World, Hotkeys. Every change is
 * saved right away. Hotkeys: click one, press the keys (e.g. hold R, press Enter), let go; Esc unbinds.
 */
public class LabConfigScreen extends Screen {
	private static final int ROW = 24;
	private static final int TOP = 50;
	private static LabConfig.Tab tab = LabConfig.Tab.TIMELINE;

	private final @Nullable Screen parent;
	private int firstRow;
	/** The hotkey being set, and the keys pressed so far. */
	private LabConfig.@Nullable Hotkey recording;
	private final List<Integer> recorded = new ArrayList<>();
	private final List<Row> rows = new ArrayList<>();

	private record Row(int y, LabConfig.Option option) {
	}

	public LabConfigScreen(@Nullable Screen parent) {
		super(Component.literal("RedstoneLab settings"));
		this.parent = parent;
	}

	private int visibleRows() {
		return Math.max(1, (this.height - TOP - 34) / ROW);
	}

	@Override
	protected void init() {
		this.rows.clear();
		// The tab buttons, centred.
		int x = this.width / 2 - LabConfig.Tab.values().length * 104 / 2 + 2;
		for (LabConfig.Tab t : LabConfig.Tab.values()) {
			LabConfig.Tab target = t;
			Button b = Button.builder(Component.literal(t.title), button -> {
				tab = target;
				this.firstRow = 0;
				this.recording = null;
				this.rebuildWidgets();
			}).bounds(x, 22, 100, 20).build();
			b.active = tab != t;
			this.addRenderableWidget(b);
			x += 104;
		}
		List<LabConfig.Option> options = LabConfig.options(tab);
		this.firstRow = Math.max(0, Math.min(this.firstRow, options.size() - this.visibleRows()));
		int controlX = this.width / 2 + 10;
		int controlW = Math.min(160, this.width / 2 - 70);
		for (int i = this.firstRow; i < options.size() && i < this.firstRow + this.visibleRows(); i++) {
			LabConfig.Option option = options.get(i);
			int y = TOP + (i - this.firstRow) * ROW;
			this.rows.add(new Row(y, option));
			AbstractWidget control = this.control(option, controlX, y, controlW);
			control.setTooltip(Tooltip.create(Component.literal(option.help)));
			this.addRenderableWidget(control);
			Button reset = Button.builder(Component.literal("Reset"), button -> {
				option.reset();
				LabConfig.save();
				this.rebuildWidgets();
			}).bounds(controlX + controlW + 4, y, 46, 20).build();
			reset.active = !option.isDefault();
			this.addRenderableWidget(reset);
		}
		this.addRenderableWidget(Button.builder(Component.literal("Done"), button -> this.onClose())
				.bounds(this.width / 2 - 50, this.height - 26, 100, 20).build());
	}

	private AbstractWidget control(LabConfig.Option option, int x, int y, int w) {
		if (option instanceof LabConfig.Bool b) {
			return CycleButton.onOffBuilder(b.value).displayOnlyValue().create(x, y, w, 20, Component.literal(label(b.name)), (button, value) -> {
				b.value = value;
				LabConfig.save();
				this.rebuildWidgets();
			});
		}
		if (option instanceof LabConfig.Choice c) {
			return CycleButton.<LabConfig.Position>builder(p -> Component.literal(p.title), c.value)
					.withValues(LabConfig.Position.values()).displayOnlyValue()
					.create(x, y, w, 20, Component.literal(label(c.name)), (button, value) -> {
						c.value = value;
						LabConfig.save();
						this.rebuildWidgets();
					});
		}
		if (option instanceof LabConfig.Hotkey h) {
			String text = h == this.recording
					? (this.recorded.isEmpty() ? "> press keys <" : "> " + new KeyCombo(List.copyOf(this.recorded)).display() + " <")
					: h.value.display();
			return Button.builder(Component.literal(text), button -> {
				this.recording = h;
				this.recorded.clear();
				this.rebuildWidgets();
			}).bounds(x, y, w, 20).build();
		}
		EditBox box = new EditBox(this.font, x + 1, y + 1, w - 2, 18, Component.literal(label(option.name)));
		box.setFilter(text -> text.matches("[0-9.]*"));
		if (option instanceof LabConfig.Int n) {
			box.setValue(Integer.toString(n.value));
			box.setResponder(text -> {
				try {
					n.set(Integer.parseInt(text));
					LabConfig.save();
				} catch (NumberFormatException ignored) {
					// keep typing
				}
			});
		} else if (option instanceof LabConfig.Dbl d) {
			box.setValue(Double.toString(d.value));
			box.setResponder(text -> {
				try {
					d.set(Double.parseDouble(text));
					LabConfig.save();
				} catch (NumberFormatException ignored) {
					// keep typing
				}
			});
		}
		return box;
	}

	/** "showTimeline" -> "Show timeline". */
	static String label(String name) {
		StringBuilder sb = new StringBuilder();
		for (char c : name.toCharArray()) {
			if (Character.isUpperCase(c)) {
				sb.append(' ').append(Character.toLowerCase(c));
			} else {
				sb.append(sb.isEmpty() ? Character.toUpperCase(c) : c);
			}
		}
		return sb.toString();
	}

	@Override
	public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
		super.renderBackground(graphics, mouseX, mouseY, delta);
		// Dark enough that chat and the timeline behind don't mix with the labels.
		graphics.fill(0, 0, this.width, this.height, 0xC0101010);
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
		super.render(graphics, mouseX, mouseY, delta);
		graphics.drawCenteredString(this.font, this.title, this.width / 2, 8, 0xFFFFFFFF);
		int labelX = Math.max(10, this.width / 2 - 200);
		for (Row row : this.rows) {
			graphics.drawString(this.font, label(row.option().name), labelX, row.y() + 6, 0xFFFFFFFF, true);
			if (mouseX >= labelX && mouseX < this.width / 2 && mouseY >= row.y() && mouseY < row.y() + 20) {
				graphics.setTooltipForNextFrame(this.font, Component.literal(row.option().help), mouseX, mouseY);
			}
		}
		int total = LabConfig.options(tab).size();
		if (total > this.visibleRows()) {
			graphics.drawCenteredString(this.font, "scroll for more (" + (this.firstRow + 1) + "-"
					+ Math.min(total, this.firstRow + this.visibleRows()) + " of " + total + ")", this.width / 2, this.height - 38, 0xFF888888);
		}
		if (this.recording != null) {
			graphics.drawCenteredString(this.font, "Hold the keys, then let go. Esc = unbind.",
					this.width / 2, TOP - 12, 0xFFFFDD77);
		}
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		int total = LabConfig.options(tab).size();
		int before = this.firstRow;
		this.firstRow = Math.max(0, Math.min(total - this.visibleRows(), this.firstRow - (int) Math.signum(scrollY)));
		if (this.firstRow != before) {
			this.rebuildWidgets();
		}
		return true;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (this.recording != null) {
			if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
				// Like vanilla's controls menu: Esc unbinds.
				this.recording.value = KeyCombo.NONE;
				this.recording = null;
				this.recorded.clear();
				LabConfig.save();
			} else if (KeyCombo.known(event.key()) && !this.recorded.contains(event.key())) {
				this.recorded.add(event.key());
			}
			this.rebuildWidgets();
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public boolean keyReleased(KeyEvent event) {
		if (this.recording != null && !this.recorded.isEmpty()) {
			this.recording.value = new KeyCombo(List.copyOf(this.recorded));
			this.recording = null;
			this.recorded.clear();
			LabConfig.save();
			this.rebuildWidgets();
			return true;
		}
		return super.keyReleased(event);
	}

	@Override
	public void onClose() {
		LabConfig.save();
		if (this.minecraft != null) {
			this.minecraft.setScreen(this.parent);
		}
	}
}
