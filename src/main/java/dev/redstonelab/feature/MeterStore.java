package dev.redstonelab.feature;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dev.redstonelab.RedstoneLab;

/** Saves each player's meter groups in <world>/redstonelab/meters/<uuid>.json, so they survive relogs and restarts. */
final class MeterStore {
	/** A saved meter: dimension id ("minecraft:overworld"), position, expected on/off length (-1 = none). */
	record Saved(String dimension, BlockPos pos, int expectOn, int expectOff, String name) {
	}

	/** All groups of one player and the one that is active. */
	static final class Groups {
		String active = "default";
		final Map<String, List<Saved>> groups = new LinkedHashMap<>();
	}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private MeterStore() {
	}

	private static Path file(MinecraftServer server, UUID uuid) {
		return server.getWorldPath(LevelResource.ROOT).resolve("redstonelab").resolve("meters").resolve(uuid + ".json");
	}

	static Groups load(MinecraftServer server, UUID uuid) {
		Groups result = new Groups();
		Path path = file(server, uuid);
		if (Files.exists(path)) {
			try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
				JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
				if (root.has("active")) {
					result.active = root.get("active").getAsString();
				}
				JsonObject groups = root.has("groups") ? root.getAsJsonObject("groups") : new JsonObject();
				for (Map.Entry<String, JsonElement> g : groups.entrySet()) {
					List<Saved> list = new ArrayList<>();
					if (!g.getValue().isJsonArray()) {
						continue;
					}
					for (JsonElement e : g.getValue().getAsJsonArray()) {
						try {
							JsonObject o = e.getAsJsonObject();
							list.add(new Saved(o.get("dim").getAsString(),
									new BlockPos(o.get("x").getAsInt(), o.get("y").getAsInt(), o.get("z").getAsInt()),
									o.has("expectOn") ? o.get("expectOn").getAsInt() : -1,
									o.has("expectOff") ? o.get("expectOff").getAsInt() : -1,
									o.has("name") ? o.get("name").getAsString() : ""));
						} catch (RuntimeException bad) {
							RedstoneLab.LOGGER.warn("Skipping a broken meter entry in {}: {}", path, e);
						}
					}
					result.groups.put(g.getKey(), list);
				}
			} catch (IOException | RuntimeException e) {
				RedstoneLab.LOGGER.warn("Could not read meter groups from {}", path, e);
			}
		}
		result.groups.putIfAbsent(result.active, new ArrayList<>());
		return result;
	}

	static void save(MinecraftServer server, UUID uuid, Groups data) {
		JsonObject root = new JsonObject();
		root.addProperty("active", data.active);
		JsonObject groups = new JsonObject();
		for (Map.Entry<String, List<Saved>> g : data.groups.entrySet()) {
			JsonArray list = new JsonArray();
			for (Saved s : g.getValue()) {
				JsonObject o = new JsonObject();
				o.addProperty("dim", s.dimension());
				o.addProperty("x", s.pos().getX());
				o.addProperty("y", s.pos().getY());
				o.addProperty("z", s.pos().getZ());
				if (s.expectOn() >= 0) {
					o.addProperty("expectOn", s.expectOn());
				}
				if (s.expectOff() >= 0) {
					o.addProperty("expectOff", s.expectOff());
				}
				if (!s.name().isEmpty()) {
					o.addProperty("name", s.name());
				}
				list.add(o);
			}
			groups.add(g.getKey(), list);
		}
		root.add("groups", groups);
		Path path = file(server, uuid);
		try {
			Files.createDirectories(path.getParent());
			// Write a temp file first so a crash mid-write can't leave a broken file behind.
			Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
			try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
				GSON.toJson(root, writer);
			}
			Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			RedstoneLab.LOGGER.warn("Could not save meter groups to {}", path, e);
		}
	}
}
