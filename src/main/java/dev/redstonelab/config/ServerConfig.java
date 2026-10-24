package dev.redstonelab.config;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import net.fabricmc.loader.api.FabricLoader;

import dev.redstonelab.RedstoneLab;

/**
 * Server settings in config/redstonelab-server.json. Every public non-static field is one setting;
 * change them in game with /rlab config set <key> <value>.
 */
public final class ServerConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	private static ServerConfig instance = new ServerConfig();

	/** Permission level needed for /rlab, the Meter Tool and piston previews (0-4). 2 = cheats on. */
	public int permissionLevel = 2;
	/** Item used as the Meter Tool (vanilla item, marked with custom_data). */
	public String probeItem = "minecraft:blaze_rod";
	/** Meters per player (1-26). */
	public int maxMeters = 16;
	/** How many ticks of history each meter keeps. */
	public int meterHistoryTicks = 200;
	/** Number of recent pulses used for the pulse lengths. */
	public int pulseWindow = 8;
	/** Max outlines/labels shown to one player at once. */
	public int maxMarkersPerPlayer = 300;

	public static ServerConfig get() {
		return instance;
	}

	public static Path path() {
		return FabricLoader.getInstance().getConfigDir().resolve("redstonelab-server.json");
	}

	public static void load() {
		Path path = path();
		if (Files.exists(path)) {
			try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
				// Start from the defaults so settings missing from an older file keep their default value.
				JsonObject merged = GSON.toJsonTree(new ServerConfig()).getAsJsonObject();
				JsonElement file = JsonParser.parseReader(reader);
				if (file != null && file.isJsonObject()) {
					file.getAsJsonObject().entrySet().forEach(e -> merged.add(e.getKey(), e.getValue()));
				}
				ServerConfig loaded = GSON.fromJson(merged, ServerConfig.class);
				if (loaded != null) {
					instance = loaded;
				}
			} catch (IOException | JsonParseException e) {
				RedstoneLab.LOGGER.error("Failed to read {}, using defaults", path, e);
			}
		}
		instance.clamp();
		save();
	}

	public static void save() {
		Path path = path();
		try {
			Files.createDirectories(path.getParent());
			try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
				GSON.toJson(instance, writer);
			}
		} catch (IOException e) {
			RedstoneLab.LOGGER.error("Failed to write {}", path, e);
		}
	}

	private void clamp() {
		this.permissionLevel = clamp(this.permissionLevel, 0, 4);
		this.maxMeters = clamp(this.maxMeters, 1, 26);
		this.meterHistoryTicks = clamp(this.meterHistoryTicks, 100, 6000);
		this.pulseWindow = clamp(this.pulseWindow, 2, 1000);
		this.maxMarkersPerPlayer = clamp(this.maxMarkersPerPlayer, 1, 4096);
		if (this.probeItem == null || this.probeItem.isBlank()) {
			this.probeItem = "minecraft:blaze_rod";
		}
	}

	private static int clamp(int v, int min, int max) {
		return Math.max(min, Math.min(max, v));
	}

	/** All settings as key -> current value string, in declaration order. */
	public Map<String, String> describe() {
		Map<String, String> map = new LinkedHashMap<>();
		for (Field f : settingFields()) {
			try {
				map.put(f.getName(), String.valueOf(f.get(this)));
			} catch (IllegalAccessException ignored) {
			}
		}
		return map;
	}

	public static String[] keys() {
		return Arrays.stream(settingFields()).map(Field::getName).toArray(String[]::new);
	}

	/** Sets a value from a command string. Returns null on success, else the error message. */
	public String set(String key, String value) {
		for (Field f : settingFields()) {
			if (!f.getName().equalsIgnoreCase(key)) {
				continue;
			}
			try {
				Class<?> t = f.getType();
				if (t == int.class) {
					f.setInt(this, Integer.parseInt(value.trim()));
				} else if (t == boolean.class) {
					String v = value.trim().toLowerCase(Locale.ROOT);
					if (!v.equals("true") && !v.equals("false")) {
						return "Expected true or false";
					}
					f.setBoolean(this, Boolean.parseBoolean(v));
				} else if (t == String.class) {
					f.set(this, value);
				} else {
					return "Unsupported setting type";
				}
				this.clamp();
				save();
				return null;
			} catch (NumberFormatException e) {
				return "Expected a whole number";
			} catch (IllegalAccessException e) {
				return "Cannot change " + key;
			}
		}
		return "Unknown setting '" + key + "'";
	}

	private static Field[] settingFields() {
		return Arrays.stream(ServerConfig.class.getDeclaredFields())
				.filter(f -> Modifier.isPublic(f.getModifiers()) && !Modifier.isStatic(f.getModifiers()))
				.toArray(Field[]::new);
	}
}
