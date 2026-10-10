package dev.redstonelab.client.ui;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import net.fabricmc.loader.api.FabricLoader;

import dev.redstonelab.RedstoneLab;

/**
 * Client settings, saved to {@code config/redstonelab.json}. No library needed: every option
 * knows its tab, name, help text and default, and the settings screen is built from them.
 */
public final class LabConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final List<Option> ALL = new ArrayList<>();

	public enum Tab {
		METERS("Meters"),
		GUI("GUI"),
		PISTONS("Pistorder"),
		HOTKEYS("Hotkeys");

		public final String title;

		Tab(String title) {
			this.title = title;
		}
	}

	public enum Position {
		TOP_LEFT("Top left"),
		TOP_RIGHT("Top right"),
		BOTTOM_LEFT("Bottom left"),
		BOTTOM_RIGHT("Bottom right"),
		CENTER("Center");

		public final String title;

		Position(String title) {
			this.title = title;
		}
	}

	/** One setting. */
	public abstract static class Option {
		public final Tab tab;
		public final String name;
		public final String help;

		Option(Tab tab, String name, String help) {
			this.tab = tab;
			this.name = name;
			this.help = help;
			ALL.add(this);
		}

		abstract JsonElement write();

		abstract void read(JsonElement json);

		public abstract void reset();

		public abstract boolean isDefault();
	}

	public static final class Bool extends Option {
		public final boolean def;
		public boolean value;

		Bool(Tab tab, String name, boolean def, String help) {
			super(tab, name, help);
			this.def = this.value = def;
		}

		JsonElement write() {
			return new JsonPrimitive(this.value);
		}

		void read(JsonElement json) {
			this.value = json.getAsBoolean();
		}

		public void reset() {
			this.value = this.def;
		}

		public boolean isDefault() {
			return this.value == this.def;
		}
	}

	public static final class Int extends Option {
		public final int def;
		public final int min;
		public final int max;
		public int value;

		Int(Tab tab, String name, int def, int min, int max, String help) {
			super(tab, name, help);
			this.def = this.value = def;
			this.min = min;
			this.max = max;
		}

		public void set(int v) {
			this.value = Math.max(this.min, Math.min(this.max, v));
		}

		JsonElement write() {
			return new JsonPrimitive(this.value);
		}

		void read(JsonElement json) {
			this.set(json.getAsInt());
		}

		public void reset() {
			this.value = this.def;
		}

		public boolean isDefault() {
			return this.value == this.def;
		}
	}

	public static final class Dbl extends Option {
		public final double def;
		public final double min;
		public final double max;
		public double value;

		Dbl(Tab tab, String name, double def, double min, double max, String help) {
			super(tab, name, help);
			this.def = this.value = def;
			this.min = min;
			this.max = max;
		}

		public void set(double v) {
			this.value = Math.max(this.min, Math.min(this.max, v));
		}

		JsonElement write() {
			return new JsonPrimitive(this.value);
		}

		void read(JsonElement json) {
			this.set(json.getAsDouble());
		}

		public void reset() {
			this.value = this.def;
		}

		public boolean isDefault() {
			return this.value == this.def;
		}
	}

	public static final class Choice extends Option {
		public final Position def;
		public Position value;

		Choice(Tab tab, String name, Position def, String help) {
			super(tab, name, help);
			this.def = this.value = def;
		}

		JsonElement write() {
			return new JsonPrimitive(this.value.name().toLowerCase(java.util.Locale.ROOT));
		}

		void read(JsonElement json) {
			for (Position p : Position.values()) {
				if (p.name().equalsIgnoreCase(json.getAsString())) {
					this.value = p;
				}
			}
		}

		public void reset() {
			this.value = this.def;
		}

		public boolean isDefault() {
			return this.value == this.def;
		}
	}

	public static final class Hotkey extends Option {
		public final KeyCombo def;
		public KeyCombo value;
		/** Keeps firing while held (rewind / forward). */
		public final boolean repeats;

		Hotkey(String name, String def, boolean repeats, String help) {
			super(Tab.HOTKEYS, name, help);
			this.def = this.value = KeyCombo.parse(def);
			this.repeats = repeats;
		}

		JsonElement write() {
			return new JsonPrimitive(this.value.save());
		}

		void read(JsonElement json) {
			// Old MaLiLib files store {"keys": "R,M", ...}.
			String text = json.isJsonObject() && json.getAsJsonObject().has("keys")
					? json.getAsJsonObject().get("keys").getAsString() : json.getAsString();
			this.value = KeyCombo.parse(text);
		}

		public void reset() {
			this.value = this.def;
		}

		public boolean isDefault() {
			return this.value.equals(this.def);
		}
	}

	// ------------------------------------------------------------------ the settings

	public static final Bool SHOW_TIMELINE = new Bool(Tab.METERS, "showTimeline", true, "Timeline on screen");
	public static final Bool SHOW_OUTLINES = new Bool(Tab.METERS, "showOutlines", true,
			"Outlines, letters and piston previews in the world");
	public static final Bool EVENT_MARKS = new Bool(Tab.METERS, "showEventMarks", false,
			"Marks for what happened each tick, and block / shape updates in the tick list");
	public static final Int TIMELINE_TICKS = new Int(Tab.METERS, "timelineTicks", 80, 20, 200, "Ticks shown");
	public static final Bool SHOW_INSPECT = new Bool(Tab.METERS, "showTickInspector", true,
			"Frozen: list what happened in the tick, in order");
	public static final Bool SMOOTH_SCROLL = new Bool(Tab.METERS, "smoothScrolling", true,
			"Slide instead of jumping a tick at a time");

	public static final Choice POSITION = new Choice(Tab.GUI, "timelinePosition", Position.TOP_LEFT, "Where on screen");
	public static final Int OFFSET_X = new Int(Tab.GUI, "timelineOffsetX", 4, 0, 2000, "Gap from the left / right edge");
	public static final Int OFFSET_Y = new Int(Tab.GUI, "timelineOffsetY", 4, 0, 2000, "Gap from the top / bottom edge");
	public static final Dbl SCALE = new Dbl(Tab.GUI, "timelineScale", 1.0, 0.5, 3.0,
			"Size (1 = normal, same at every GUI scale)");
	public static final Bool SHOW_BACKGROUND = new Bool(Tab.GUI, "showBackground", true, "Dark box behind it");
	public static final Int BACKGROUND_OPACITY = new Int(Tab.GUI, "backgroundOpacity", 75, 0, 100, "0 = clear, 100 = solid");
	public static final Bool SHOW_TITLE = new Bool(Tab.GUI, "showTitle", true, "The \"Meters\" title line");
	public static final Bool SHOW_INFO = new Bool(Tab.GUI, "showInfo", true, "Text next to each bar, and the order line");
	public static final Bool SHOW_PULSE_LENGTHS = new Bool(Tab.GUI, "showPulseLengths", true, "Tick counts inside the bars");
	public static final Bool SHOW_LEGEND = new Bool(Tab.GUI, "showLegend", true, "Frozen: explain the bars");

	public static final Bool PISTON_LANDING = new Bool(Tab.PISTONS, "pistonLanding", false,
			"Also outline where pushed blocks land (light blue)");

	public static final Hotkey TOGGLE_METER = new Hotkey("toggleMeter", "M", false, "Meter on / off (block you look at)");
	public static final Hotkey REMOVE_METER = new Hotkey("removeMeter", "BACKSPACE", false, "Remove the meter you look at, else the newest");
	public static final Hotkey CLEAR_METERS = new Hotkey("clearMeters", "R,M", false, "Remove all meters");
	public static final Hotkey PAUSE_TIMELINE = new Hotkey("pauseTimeline", "R,SPACE", false, "Freeze / unfreeze");
	public static final Hotkey REWIND = new Hotkey("rewind", "R,BACKSPACE", true, "1 tick back (freezes). Hold to repeat");
	public static final Hotkey FORWARD = new Hotkey("forward", "R,ENTER", true, "1 tick forward. Back at now it runs again");
	public static final Hotkey FREEZE_GAME = new Hotkey("gameFreeze", "UP", false, "Freeze / unfreeze the game (/tick, needs op)");
	public static final Hotkey STEP_GAME = new Hotkey("gameStep", "DOWN", true, "Frozen game: step 1 tick. Hold to repeat");
	public static final Hotkey CLEAR_PISTONS = new Hotkey("clearPistons", "P", false, "Hide piston previews");
	public static final Hotkey GROUPS = new Hotkey("meterGroups", "R,G", false, "Meter groups (click one to switch)");
	public static final Hotkey OPEN_SETTINGS = new Hotkey("openSettings", "R,C", false, "This menu");

	private LabConfig() {
	}

	public static List<Option> options(Tab tab) {
		List<Option> list = new ArrayList<>();
		for (Option o : ALL) {
			if (o.tab == tab) {
				list.add(o);
			}
		}
		return list;
	}

	public static List<Hotkey> hotkeys() {
		List<Hotkey> list = new ArrayList<>();
		for (Option o : ALL) {
			if (o instanceof Hotkey h) {
				list.add(h);
			}
		}
		return list;
	}

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve(RedstoneLab.MOD_ID + ".json");
	}

	public static void load() {
		Path file = file();
		if (!Files.exists(file)) {
			return;
		}
		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
			// Sections: ours ("settings", "hotkeys") and the old MaLiLib ones ("Settings", "Pistons", "Keys").
			for (Map.Entry<String, JsonElement> section : root.entrySet()) {
				if (!section.getValue().isJsonObject()) {
					continue;
				}
				for (Map.Entry<String, JsonElement> e : section.getValue().getAsJsonObject().entrySet()) {
					for (Option o : ALL) {
						if (o.name.equals(e.getKey())) {
							try {
								o.read(e.getValue());
							} catch (RuntimeException bad) {
								RedstoneLab.LOGGER.warn("Bad value for {} in {}", o.name, file);
							}
						}
					}
				}
			}
		} catch (IOException | RuntimeException e) {
			RedstoneLab.LOGGER.warn("Could not read {}", file, e);
		}
		// Old defaults (before 0.3.2) move to the new ones.
		if (REWIND.value.equals(KeyCombo.parse("R,LEFT"))) {
			REWIND.reset();
		}
		if (FORWARD.value.equals(KeyCombo.parse("R,RIGHT"))) {
			FORWARD.reset();
		}
	}

	public static void save() {
		JsonObject settings = new JsonObject();
		JsonObject hotkeys = new JsonObject();
		for (Option o : ALL) {
			(o instanceof Hotkey ? hotkeys : settings).add(o.name, o.write());
		}
		JsonObject root = new JsonObject();
		root.add("settings", settings);
		root.add("hotkeys", hotkeys);
		Path file = file();
		try {
			Files.createDirectories(file.getParent());
			try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
				GSON.toJson(root, writer);
			}
		} catch (IOException e) {
			RedstoneLab.LOGGER.warn("Could not save {}", file, e);
		}
	}
}
