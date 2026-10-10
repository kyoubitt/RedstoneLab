package dev.redstonelab.client.compat;

import org.jetbrains.annotations.Nullable;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Shown by Mod Menu's "Configure" button when MaLiLib is not installed.
 */
public class MissingMalilibScreen extends Screen {
	private final @Nullable Screen parent;

	public MissingMalilibScreen(@Nullable Screen parent) {
		super(Component.literal("RedstoneLab"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		this.addRenderableWidget(Button.builder(Component.literal("Done"), button -> this.onClose())
				.bounds(this.width / 2 - 50, this.height / 2 + 30, 100, 20).build());
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
		super.render(graphics, mouseX, mouseY, delta);
		graphics.drawCenteredString(this.font, Component.literal("RedstoneLab needs MaLiLib for its config menu, HUD and overlays."),
				this.width / 2, this.height / 2 - 20, 0xFFFFFFFF);
		graphics.drawCenteredString(this.font, Component.literal("Server settings: /rlab config   |   all tools: /rlab help"),
				this.width / 2, this.height / 2 - 5, 0xFFAAAAAA);
	}

	@Override
	public void onClose() {
		if (this.minecraft != null) {
			this.minecraft.setScreen(this.parent);
		}
	}
}
