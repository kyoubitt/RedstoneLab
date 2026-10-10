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

	public static final Bool SHOW_TIMELINE = new Bool(Tab.METERS, "showTimeline", true, "Show the meter timeline on screen");
	public static final Bool SHOW_OUTLINES = new Bool(Tab.METERS, "showOutlines", true,
			"Show meter outlines and letters, and piston previews, in the world");
	public static final Bool EVENT_MARKS = new Bool(Tab.METERS, "showEventMarks", false,
			"Coloured marks under each timeline for what happened that tick, and block / shape\n"
			+ "updates in the tick list. Off keeps it clean.");
	public static final Int TIMELINE_TICKS = new Int(Tab.METERS, "timelineTicks", 80, 20, 200, "How many ticks the timeline shows");
	public static final Bool SHOW_INSPECT = new Bool(Tab.METERS, "showTickInspector", true,
			"When the timeline is frozen: list everything that happened at your meters\nin the newest shown tick, in order");
	public static final Bool SMOOTH_SCROLL = new Bool(Tab.METERS, "smoothScrolling", true,
			"Slide the timeline smoothly instead of jumping one tick at a time");

	public static final Choice POSITION = new Choice(Tab.GUI, "timelinePosition", Position.TOP_LEFT, "Where the timeline is on screen");
	public static final Int OFFSET_X = new Int(Tab.GUI, "timelineOffsetX", 4, 0, 2000, "Distance from the left / right edge of the screen");
	public static final Int OFFSET_Y = new Int(Tab.GUI, "timelineOffsetY", 4, 0, 2000, "Distance from the top / bottom edge of the screen");
	public static final Dbl SCALE = new Dbl(Tab.GUI, "timelineScale", 1.0, 0.5, 3.0,
			"Size of the timeline (1 = normal; it stays the same size at every GUI scale)");
	public static final Bool SHOW_BACKGROUND = new Bool(Tab.GUI, "showBackground", true, "Dark box behind the timeline");
	public static final Int BACKGROUND_OPACITY = new Int(Tab.GUI, "backgroundOpacity", 75, 0, 100, "How dark the box is (0 = see-through, 100 = solid)");
	public static final Bool SHOW_TITLE = new Bool(Tab.GUI, "showTitle", true, "Show the \"Meters\" title line");
	public static final Bool SHOW_INFO = new Bool(Tab.GUI, "showInfo", true, "Show the text next to each timeline and the order line");
	public static final Bool SHOW_PULSE_LENGTHS = new Bool(Tab.GUI, "showPulseLengths", true, "Write how many ticks each pulse lasted inside the bars");
	public static final Bool SHOW_LEGEND = new Bool(Tab.GUI, "showLegend", true, "While frozen: explain the bars (and the mark colours)");

	public static final Bool PISTON_LANDING = new Bool(Tab.PISTONS, "pistonLanding", false,
			"Piston previews (right-click a piston with an empty hand):\nalso outline where the pushed blocks end up (light blue)");

	public static final Hotkey TOGGLE_METER = new Hotkey("toggleMeter", "M", false, "Put a meter on the block you look at, or remove it");
	public static final Hotkey REMOVE_METER = new Hotkey("removeMeter", "BACKSPACE", false, "Remove one meter: the one you look at, or else the newest one");
	public static final Hotkey CLEAR_METERS = new Hotkey("clearMeters", "R,M", false, "Remove all your meters");
	public static final Hotkey PAUSE_TIMELINE = new Hotkey("pauseTimeline", "R,SPACE", false, "Freeze / unfreeze the timeline");
	public static final Hotkey REWIND = new Hotkey("rewind", "R,LEFT", true, "Go back 1 tick on the timeline (freezes it). Hold to keep going.");
	public static final Hotkey FORWARD = new Hotkey("forward", "R,RIGHT", true, "Go forward 1 tick; reaching the present un-freezes it");
	public static final Hotkey CLEAR_PISTONS = new Hotkey("clearPistons", "P", false, "Hide all piston previews");
	public static final Hotkey GROUPS = new Hotkey("meterGroups", "R,G", false, "List your meter groups in chat (click one to switch)");
	public static final Hotkey OPEN_SETTINGS = new Hotkey("openSettings", "R,C", false, "Open this menu");

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
