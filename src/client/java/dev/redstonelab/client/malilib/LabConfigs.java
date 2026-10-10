package dev.redstonelab.client.malilib;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.google.common.collect.ImmutableList;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import fi.dy.masa.malilib.config.ConfigUtils;
import fi.dy.masa.malilib.config.HudAlignment;
import fi.dy.masa.malilib.config.IConfigBase;
import fi.dy.masa.malilib.config.IConfigHandler;
import fi.dy.masa.malilib.config.options.ConfigBoolean;
import fi.dy.masa.malilib.config.options.ConfigBooleanHotkeyed;
import fi.dy.masa.malilib.config.options.ConfigDouble;
import fi.dy.masa.malilib.config.options.ConfigHotkey;
import fi.dy.masa.malilib.config.options.ConfigInteger;
import fi.dy.masa.malilib.config.options.ConfigOptionList;
import fi.dy.masa.malilib.hotkeys.IHotkey;
import fi.dy.masa.malilib.util.FileUtils;
import fi.dy.masa.malilib.util.JsonUtils;

import dev.redstonelab.RedstoneLab;

/**
 * Client settings, saved by MaLiLib to {@code config/redstonelab.json}. Everything is on one
 * page of the settings menu.
 */
public final class LabConfigs implements IConfigHandler {
	private static final String FILE_NAME = RedstoneLab.MOD_ID + ".json";

	public static final class Settings {
		public static final ConfigBoolean SHOW_TIMELINE = new ConfigBoolean("showTimeline", true,
				"Show the meter timeline on screen");
		public static final ConfigBoolean SHOW_OUTLINES = new ConfigBoolean("showOutlines", true,
				"Show meter outlines and piston previews in the world");
		public static final ConfigBoolean EVENT_MARKS = new ConfigBoolean("showEventMarks", false,
				"Coloured marks under each timeline for what happened that tick\n"
				+ "(turned on/off, moved, scheduled tick, block update...).\n"
				+ "Off keeps it clean; freeze the timeline to see the list for one tick.");
		public static final ConfigOptionList TIMELINE_POSITION = new ConfigOptionList("timelinePosition", HudAlignment.TOP_LEFT,
				"Where the timeline is on screen");
		public static final ConfigDouble TIMELINE_SCALE = new ConfigDouble("timelineScale", 1.0, 0.5, 3.0,
				"Size of the timeline (1 = normal; it stays the same size at every GUI scale)");
		public static final ConfigInteger TIMELINE_TICKS = new ConfigInteger("timelineTicks", 80, 20, 200,
				"How many ticks the timeline shows");

		public static final ConfigInteger OFFSET_X = new ConfigInteger("timelineOffsetX", 4, 0, 2000,
				"Distance from the left/right edge of the screen");
		public static final ConfigInteger OFFSET_Y = new ConfigInteger("timelineOffsetY", 4, 0, 2000,
				"Distance from the top/bottom edge of the screen");
		public static final ConfigBoolean SHOW_BACKGROUND = new ConfigBoolean("showBackground", true,
				"Dark box behind the timeline");
		public static final ConfigInteger BACKGROUND_OPACITY = new ConfigInteger("backgroundOpacity", 75, 0, 100,
				"How dark the box is (0 = see-through, 100 = solid)");
		public static final ConfigBoolean SHOW_TITLE = new ConfigBoolean("showTitle", true,
				"Show the \"Meters\" title line");
		public static final ConfigBoolean SHOW_INFO = new ConfigBoolean("showInfo", true,
				"Show the text next to each timeline (block, pulse lengths) and the order line");
		public static final ConfigBoolean SHOW_PULSE_LENGTHS = new ConfigBoolean("showPulseLengths", true,
				"Write how many ticks each pulse lasted inside the bars");
		public static final ConfigBoolean SHOW_LEGEND = new ConfigBoolean("showLegend", true,
				"Explain the colours of the event marks (shown while the timeline is frozen)");
		public static final ConfigBoolean SHOW_INSPECT = new ConfigBoolean("showTickInspector", true,
				"When the timeline is frozen: highlight the newest shown tick and list\nwhat happened at your meters in that tick, in order");
		public static final ConfigBoolean SMOOTH_SCROLL = new ConfigBoolean("smoothScrolling", true,
				"Slide the timeline smoothly instead of jumping one tick at a time");

		public static final ImmutableList<IConfigBase> OPTIONS = ImmutableList.of(
				SHOW_TIMELINE, SHOW_OUTLINES, EVENT_MARKS, TIMELINE_POSITION, OFFSET_X, OFFSET_Y, TIMELINE_SCALE, TIMELINE_TICKS,
				SHOW_BACKGROUND, BACKGROUND_OPACITY, SHOW_TITLE, SHOW_INFO, SHOW_PULSE_LENGTHS, SHOW_LEGEND, SHOW_INSPECT, SMOOTH_SCROLL);
	}

	/** Piston preview options; they live on the server, so changes are sent there. */
	public static final class Pistons {
		public static final ConfigBoolean LANDING = new ConfigBoolean("pistonLanding", false,
				"Piston previews (right-click a piston with an empty hand):\nalso outline where the pushed blocks end up (light blue)");

		public static final ImmutableList<IConfigBase> OPTIONS = ImmutableList.of(LANDING);
	}

	public static final class Keys {
		public static final ConfigHotkey TOGGLE_METER = new ConfigHotkey("toggleMeter", "M",
				"Put a meter on the block you look at, or remove it");
		public static final ConfigHotkey REMOVE_METER = new ConfigHotkey("removeMeter", "BACKSPACE",
				"Remove one meter: the one you look at, or else the newest one");
		public static final ConfigHotkey CLEAR_METERS = new ConfigHotkey("clearMeters", "R,M", "Remove all your meters");
		public static final ConfigBooleanHotkeyed PAUSE_TIMELINE = new ConfigBooleanHotkeyed("pauseTimeline", false, "R,SPACE",
				"Freeze the timeline so you can look back");
		public static final ConfigHotkey SCROLL_BACK = new ConfigHotkey("rewind", "R,LEFT",
				"Go back 1 tick on the timeline (freezes it automatically)");
		public static final ConfigHotkey SCROLL_FORWARD = new ConfigHotkey("forward", "R,RIGHT",
				"Go forward 1 tick; reaching the present un-freezes it");
		public static final ConfigHotkey CLEAR_PISTONS = new ConfigHotkey("clearPistons", "P",
				"Hide all piston previews (show one: right-click a piston with an empty hand)");
		public static final ConfigHotkey GROUPS = new ConfigHotkey("meterGroups", "R,G", "List your meter groups in chat (click one to switch)");
		public static final ConfigHotkey OPEN_SETTINGS = new ConfigHotkey("openSettings", "R,C", "Open this menu");

		public static final ImmutableList<IHotkey> HOTKEYS = ImmutableList.of(
				TOGGLE_METER, REMOVE_METER, CLEAR_METERS, PAUSE_TIMELINE, SCROLL_BACK, SCROLL_FORWARD, CLEAR_PISTONS, GROUPS, OPEN_SETTINGS);
	}

	/** Menu tabs. */
	public static final List<IConfigBase> TAB_GENERAL = ImmutableList.of(
			Settings.SHOW_TIMELINE, Settings.SHOW_OUTLINES, Settings.EVENT_MARKS, Settings.TIMELINE_TICKS, Settings.SHOW_INSPECT, Settings.SMOOTH_SCROLL);
	public static final List<IConfigBase> TAB_GUI = ImmutableList.of(
			Settings.TIMELINE_POSITION, Settings.OFFSET_X, Settings.OFFSET_Y, Settings.TIMELINE_SCALE,
			Settings.SHOW_BACKGROUND, Settings.BACKGROUND_OPACITY, Settings.SHOW_TITLE, Settings.SHOW_INFO,
			Settings.SHOW_PULSE_LENGTHS, Settings.SHOW_LEGEND);

	/** Everything shown in the menu, in order. */
	public static List<IConfigBase> all() {
		return ImmutableList.<IConfigBase>builder().addAll(Settings.OPTIONS).addAll(Keys.HOTKEYS).build();
	}

	// ------------------------------------------------------------------ load / save

	public static void loadFromFile() {
		Path file = FileUtils.getConfigDirectoryAsPath().resolve(FILE_NAME);
		if (Files.exists(file) && Files.isReadable(file)) {
			JsonElement element = JsonUtils.parseJsonFileAsPath(file);
			if (element != null && element.isJsonObject()) {
				JsonObject root = element.getAsJsonObject();
				ConfigUtils.readConfigBase(root, "Settings", Settings.OPTIONS);
				ConfigUtils.readConfigBase(root, "Keys", Keys.HOTKEYS);
				ConfigUtils.readConfigBase(root, "Pistons", Pistons.OPTIONS);
			}
		}
	}

	public static void saveToFile() {
		Path dir = FileUtils.getConfigDirectoryAsPath();
		try {
			Files.createDirectories(dir);
		} catch (java.io.IOException e) {
			RedstoneLab.LOGGER.error("Cannot create config folder {}", dir, e);
			return;
		}
		JsonObject root = new JsonObject();
		ConfigUtils.writeConfigBase(root, "Settings", Settings.OPTIONS);
		ConfigUtils.writeConfigBase(root, "Keys", Keys.HOTKEYS);
		ConfigUtils.writeConfigBase(root, "Pistons", Pistons.OPTIONS);
		JsonUtils.writeJsonToFileAsPath(root, dir.resolve(FILE_NAME));
	}

	@Override
	public void load() {
		loadFromFile();
	}

	@Override
	public void save() {
		saveToFile();
	}
}
