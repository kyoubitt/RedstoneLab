package dev.redstonelab.display;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import it.unimi.dsi.fastutil.ints.IntArrayList;

import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import dev.redstonelab.config.ServerConfig;
import dev.redstonelab.net.LabNetworking;
import dev.redstonelab.net.LabPayloads;
import dev.redstonelab.net.LabPayloads.MarkerData;
import dev.redstonelab.server.Sessions;

/**
 * In-world labels and outlines per player and channel ("meters", "pistorder"). Modded clients get a
 * MarkersS2C payload and draw them; vanilla clients get packet-only display entities (see FakeDisplays),
 * diffed so only changed markers are sent again.
 */
public final class MarkerService {
	private record Key(BlockPos pos, byte kind, float yOffset) {
	}

	private static final class Spawned {
		final MarkerData marker;
		final int id;
		final List<Packet<? super ClientGamePacketListener>> packets;

		Spawned(MarkerData marker, int id, List<Packet<? super ClientGamePacketListener>> packets) {
			this.marker = marker;
			this.id = id;
			this.packets = packets;
		}
	}

	private static final class Channel {
		ResourceKey<Level> dimension;
		List<MarkerData> desired = List.of();
		/** Vanilla mode: fake entities currently on the client. */
		final Map<Key, Spawned> spawned = new LinkedHashMap<>();
		/** Vanilla mode: how many fake entities the desired markers need (label + outline each). */
		int wantedCount;
		/** Modded mode: what was last sent, to avoid resending identical lists. */
		List<MarkerData> lastSent = null;
	}

	private static final class PlayerMarkers {
		final Map<String, Channel> channels = new HashMap<>();
		boolean payloadMode;

		int entityCount() {
			int n = 0;
			for (Channel c : this.channels.values()) {
				n += c.spawned.size();
			}
			return n;
		}
	}

	private static final Map<UUID, PlayerMarkers> PLAYERS = new HashMap<>();

	private MarkerService() {
	}

	// ------------------------------------------------------------------ API

	public static void set(ServerPlayer player, String channel, List<MarkerData> markers) {
		PlayerMarkers pm = PLAYERS.computeIfAbsent(player.getUUID(), k -> new PlayerMarkers());
		boolean payload = Sessions.get(player).rendersWorld();
		if (payload != pm.payloadMode) {
			switchMode(player, pm, payload);
		}
		Channel ch = pm.channels.computeIfAbsent(channel, k -> new Channel());
		ch.dimension = player.level().dimension();
		ch.desired = List.copyOf(markers);

		if (payload) {
			if (!Objects.equals(ch.lastSent, ch.desired)) {
				ch.lastSent = ch.desired;
				LabNetworking.send(player, new LabPayloads.MarkersS2C(channel, ch.desired));
			}
		} else {
			syncEntities(player, pm, ch);
		}
		if (markers.isEmpty() && ch.spawned.isEmpty()) {
			pm.channels.remove(channel);
		}
	}

	public static void clear(ServerPlayer player, String channel) {
		set(player, channel, List.of());
	}

	public static void clearAll(ServerPlayer player) {
		PlayerMarkers pm = PLAYERS.get(player.getUUID());
		if (pm == null) {
			return;
		}
		for (String channel : new ArrayList<>(pm.channels.keySet())) {
			clear(player, channel);
		}
		PLAYERS.remove(player.getUUID());
	}

	/** The player disconnected: the client dropped everything, nothing to send. */
	public static void forget(UUID uuid) {
		PLAYERS.remove(uuid);
	}

	/** The client lost its entities (respawn, dimension change): forget the spawned ids and send again what is still wanted. */
	public static void resync(ServerPlayer player) {
		PlayerMarkers pm = PLAYERS.get(player.getUUID());
		if (pm == null) {
			return;
		}
		for (Map.Entry<String, Channel> e : pm.channels.entrySet()) {
			Channel ch = e.getValue();
			ch.spawned.clear();
			ch.lastSent = null;
			if (ch.dimension != player.level().dimension()) {
				ch.desired = List.of();
			}
		}
		for (String channel : new ArrayList<>(pm.channels.keySet())) {
			Channel ch = pm.channels.get(channel);
			set(player, channel, ch.desired);
		}
	}

	/** Re-applies all channels, switching between payload and display-entity mode if the client changed. */
	public static void refreshMode(ServerPlayer player) {
		PlayerMarkers pm = PLAYERS.get(player.getUUID());
		if (pm == null) {
			return;
		}
		for (String channel : new ArrayList<>(pm.channels.keySet())) {
			Channel ch = pm.channels.get(channel);
			if (ch != null) {
				set(player, channel, ch.desired);
			}
		}
	}

	/** Server unloaded a chunk: drop the fake entities in it (the client unloads it too). */
	public static void onChunkUnload(ServerLevel level, ChunkPos chunk, Iterable<ServerPlayer> players) {
		for (ServerPlayer player : players) {
			PlayerMarkers pm = PLAYERS.get(player.getUUID());
			if (pm == null || pm.payloadMode) {
				continue;
			}
			IntArrayList remove = new IntArrayList();
			for (Channel ch : pm.channels.values()) {
				if (ch.dimension != level.dimension()) {
					continue;
				}
				for (Iterator<Map.Entry<Key, Spawned>> it = ch.spawned.entrySet().iterator(); it.hasNext(); ) {
					Map.Entry<Key, Spawned> e = it.next();
					if (new ChunkPos(e.getKey().pos()).equals(chunk)) {
						remove.add(e.getValue().id);
						it.remove();
					}
				}
			}
			if (!remove.isEmpty()) {
				player.connection.send(new ClientboundRemoveEntitiesPacket(remove));
			}
		}
	}

	/** Periodic: re-spawn wanted markers whose chunk is loaded again (vanilla mode). */
	public static void tick(Iterable<ServerPlayer> players) {
		for (ServerPlayer player : players) {
			PlayerMarkers pm = PLAYERS.get(player.getUUID());
			if (pm == null || pm.payloadMode) {
				continue;
			}
			for (Channel ch : pm.channels.values()) {
				if (ch.spawned.size() < ch.wantedCount && ch.dimension == player.level().dimension()) {
					syncEntities(player, pm, ch);
				}
			}
		}
	}

	// ------------------------------------------------------------------ internals

	private static void switchMode(ServerPlayer player, PlayerMarkers pm, boolean payload) {
		if (payload) {
			IntArrayList remove = new IntArrayList();
			for (Channel ch : pm.channels.values()) {
				ch.spawned.values().forEach(s -> remove.add(s.id));
				ch.spawned.clear();
				ch.lastSent = null;
			}
			if (!remove.isEmpty()) {
				player.connection.send(new ClientboundRemoveEntitiesPacket(remove));
			}
		} else {
			for (Map.Entry<String, Channel> e : pm.channels.entrySet()) {
				LabNetworking.send(player, new LabPayloads.MarkersS2C(e.getKey(), List.of()));
				e.getValue().lastSent = null;
			}
		}
		pm.payloadMode = payload;
	}

	private static void syncEntities(ServerPlayer player, PlayerMarkers pm, Channel ch) {
		ServerLevel level = player.level();
		int budget = ServerConfig.get().maxMarkersPerPlayer - (pm.entityCount() - ch.spawned.size());

		Map<Key, MarkerData> wanted = new LinkedHashMap<>();
		for (MarkerData m : ch.desired) {
			if (wanted.size() >= budget) {
				break;
			}
			if ((m.style() & LabPayloads.STYLE_LABEL) != 0 && !m.text().isEmpty()) {
				wanted.put(new Key(m.pos(), LabPayloads.STYLE_LABEL, m.yOffset()), m);
			}
			if ((m.style() & (LabPayloads.STYLE_OUTLINE | LabPayloads.STYLE_FILL)) != 0 && wanted.size() < budget) {
				wanted.put(new Key(m.pos(), LabPayloads.STYLE_OUTLINE, 0f), m);
			}
		}

		ch.wantedCount = wanted.size();
		IntArrayList remove = new IntArrayList();
		List<Packet<? super ClientGamePacketListener>> add = new ArrayList<>();

		for (Iterator<Map.Entry<Key, Spawned>> it = ch.spawned.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry<Key, Spawned> e = it.next();
			MarkerData want = wanted.get(e.getKey());
			if (want == null || !sameLook(e.getKey().kind(), want, e.getValue().marker)) {
				remove.add(e.getValue().id);
				it.remove();
			}
		}
		for (Map.Entry<Key, MarkerData> e : wanted.entrySet()) {
			if (ch.spawned.containsKey(e.getKey()) || !level.hasChunkAt(e.getKey().pos())) {
				continue;
			}
			MarkerData m = e.getValue();
			FakeDisplays.Fake fake = e.getKey().kind() == LabPayloads.STYLE_LABEL
					? FakeDisplays.label(level, m.pos(), m.yOffset(), m.text(), m.color(), 0.6f)
					: FakeDisplays.outline(level, m.pos(), m.color());
			if (fake != null) {
				ch.spawned.put(e.getKey(), new Spawned(m, fake.id(), fake.spawnPackets()));
				add.addAll(fake.spawnPackets());
			}
		}

		if (!remove.isEmpty()) {
			add.add(0, new ClientboundRemoveEntitiesPacket(remove));
		}
		if (!add.isEmpty()) {
			player.connection.send(new ClientboundBundlePacket(add));
		}
	}

	private static boolean sameLook(byte kind, MarkerData a, MarkerData b) {
		if (a.color() != b.color()) {
			return false;
		}
		return kind != LabPayloads.STYLE_LABEL || a.text().equals(b.text());
	}
}
