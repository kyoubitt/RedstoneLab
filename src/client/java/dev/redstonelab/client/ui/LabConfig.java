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
		/** What the timeline shows. */
		TIMELINE("Timeline"),
		/** How things look: the timeline's place and size, outlines in the world. */
		GUI("GUI"),
		/** How slow and how fast the game speed keys go. */
		SPEED("Speed"),
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

	/** One of a few fixed values, picked with a slider (saved as the value). */
	public static final class Steps extends Option {
		public final float[] values;
		public final String[] labels;
		public final int def;
		public int index;

		Steps(Tab tab, String name, float[] values, String[] labels, int def, String help) {
			super(tab, name, help);
			this.values = values;
			this.labels = labels;
			this.def = this.index = def;
		}

		public float value() {
			return this.values[this.index];
		}

		public String label() {
			return this.labels[this.index];
		}

		JsonElement write() {
			return new JsonPrimitive(this.value());
		}

		void read(JsonElement json) {
			float v = json.getAsFloat();
			int best = 0;
			for (int i = 1; i < this.values.length; i++) {
				if (Math.abs(this.values[i] - v) < Math.abs(this.values[best] - v)) {
					best = i;
				}
			}
			this.index = best;
		}

		public void reset() {
			this.index = this.def;
		}

		public boolean isDefault() {
			return this.index == this.def;
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

	public static final Bool SHOW_TIMELINE = new Bool(Tab.TIMELINE, "showTimeline", true, "Timeline on screen");
	public static final Int TIMELINE_TICKS = new Int(Tab.TIMELINE, "timelineTicks", 80, 20, 200, "Ticks shown");
	public static final Bool SHOW_INFO = new Bool(Tab.TIMELINE, "showInfo", true, "Text next to each bar, and the order line");
	public static final Bool SHOW_PULSE_LENGTHS = new Bool(Tab.TIMELINE, "showPulseLengths", true, "Tick counts inside the bars");
	public static final Bool EVENT_MARKS = new Bool(Tab.TIMELINE, "showEventMarks", false,
			"Marks for what happened each tick, and block / shape updates in the tick list");
	public static final Bool SHOW_INSPECT = new Bool(Tab.TIMELINE, "showTickInspector", true,
			"Timeline stopped: list what happened in the tick, in order");

	public static final Choice POSITION = new Choice(Tab.GUI, "timelinePosition", Position.TOP_LEFT, "Where on screen");
	public static final Int OFFSET_X = new Int(Tab.GUI, "timelineOffsetX", 4, 0, 2000, "Gap from the left / right edge");
	public static final Int OFFSET_Y = new Int(Tab.GUI, "timelineOffsetY", 4, 0, 2000, "Gap from the top / bottom edge");
	public static final Dbl SCALE = new Dbl(Tab.GUI, "timelineScale", 1.0, 0.5, 3.0,
			"Size (1 = normal, same at every GUI scale)");
	public static final Int BACKGROUND_OPACITY = new Int(Tab.GUI, "backgroundOpacity", 75, 0, 100, "Dark box behind it: 0 = none, 100 = solid");

	public static final Bool SHOW_OUTLINES = new Bool(Tab.GUI, "showOutlines", true,
			"Outlines, letters and piston previews in the world");
	public static final Bool PISTON_LANDING = new Bool(Tab.GUI, "pistonLanding", false,
			"Piston previews: also outline where pushed blocks land (light blue)");

	public static final Steps SLOWEST = new Steps(Tab.SPEED, "slowestSpeed", new float[]{10, 5, 2, 1},
			new String[]{"1/2", "1/4", "1/10", "1/20"}, 3, "Slow motion: the slowest the Left key goes");
	public static final Steps FASTEST = new Steps(Tab.SPEED, "fastestSpeed", new float[]{20, 40, 80, 200},
			new String[]{"Normal", "2x", "4x", "10x"}, 2, "Fast forward: the fastest the Right key goes (Normal = none)");
	public static final Bool SOUND_PITCH = new Bool(Tab.SPEED, "soundPitch", true,
			"Sounds of the world play lower in slow motion, higher in fast forward (like G4mespeed)");

	/** Not in the menu: the one-time hint on the first join was shown. */
	public static final Bool INTRO_SHOWN = new Bool(null, "introShown", false, "");
	/** Not in the menu: which defaults the file was saved with (for moving old defaults to new ones). */
	public static final Int CONFIG_VERSION = new Int(null, "configVersion", 2, 0, 1000, "");

	public static final Hotkey TOGGLE_METER = new Hotkey("toggleMeter", "M", false, "Meter on / off (block you look at)");
	public static final Hotkey CLEAR_METERS = new Hotkey("clearMeters", "R,M", false, "Remove all meters");
	public static final Hotkey REWIND = new Hotkey("rewind", "R,BACKSPACE", true, "Timeline 1 tick back (it stops there). Hold to repeat");
	public static final Hotkey FORWARD = new Hotkey("forward", "R,ENTER", true, "Timeline 1 tick forward. Back at now it runs again");
	public static final Hotkey FREEZE_GAME = new Hotkey("gameFreeze", "UP", true,
			"Freeze the game (creative, op). Frozen: step 1 tick, hold to repeat");
	public static final Hotkey RUN_GAME = new Hotkey("gameRun", "DOWN", false, "Back to normal: unfreeze, normal speed");
	public static final Hotkey SLOWER = new Hotkey("gameSlower", "LEFT", false,
			"Slower: 1/2, 1/4, 1/10, 1/20 speed (creative, op; limit in the Speed tab)");
	public static final Hotkey FASTER = new Hotkey("gameFaster", "RIGHT", false, "Faster: back to normal, then 2x, 4x, 10x (limit in the Speed tab)");
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
		// Files from before versions were saved count as 0.
		CONFIG_VERSION.value = 0;
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
		// 0.6.0 (version 1) had no groups key: R + G is back.
		if (CONFIG_VERSION.value == 1 && GROUPS.value.keys().isEmpty()) {
			GROUPS.reset();
		}
		CONFIG_VERSION.reset();
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
