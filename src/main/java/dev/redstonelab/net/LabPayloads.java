package dev.redstonelab.net;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;

import dev.redstonelab.RedstoneLab;

/** All custom payloads. Only sent to clients that registered the channel (vanilla clients never see them). */
public final class LabPayloads {
	public static final int PROTOCOL = 15;

	/** Client capability: can render the HUD (meter timeline). */
	public static final int CAP_HUD = 1;
	/** Client capability: can render in-world labels/outlines (replaces display entities). */
	public static final int CAP_WORLD = 2;

	/** Most meters in one packet (one per letter A-Z, with room to spare). */
	public static final int MAX_METERS = 32;

	public static final byte STYLE_LABEL = 1;
	public static final byte STYLE_OUTLINE = 2;
	public static final byte STYLE_FILL = 4;

	private LabPayloads() {
	}

	public static void registerCommon() {
		PayloadTypeRegistry.playC2S().register(HelloC2S.TYPE, HelloC2S.CODEC);
		PayloadTypeRegistry.playS2C().register(HelloS2C.TYPE, HelloS2C.CODEC);
		PayloadTypeRegistry.playS2C().register(MarkersS2C.TYPE, MarkersS2C.CODEC);
		PayloadTypeRegistry.playS2C().register(ScopeConfigS2C.TYPE, ScopeConfigS2C.CODEC);
		PayloadTypeRegistry.playS2C().register(ScopeSampleS2C.TYPE, ScopeSampleS2C.CODEC);
		PayloadTypeRegistry.playC2S().register(PistonWatchC2S.TYPE, PistonWatchC2S.CODEC);
		PayloadTypeRegistry.playS2C().register(PistonFireS2C.TYPE, PistonFireS2C.CODEC);
	}

	private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> payloadType(String path) {
		return new CustomPacketPayload.Type<>(RedstoneLab.id(path));
	}

	// ------------------------------------------------------------------ handshake

	/** Sent by a modded client right after joining. */
	public record HelloC2S(int protocol, int capabilities, String version) implements CustomPacketPayload {
		public static final Type<HelloC2S> TYPE = payloadType("hello");
		public static final StreamCodec<FriendlyByteBuf, HelloC2S> CODEC = CustomPacketPayload.codec(HelloC2S::write, HelloC2S::new);

		private HelloC2S(FriendlyByteBuf buf) {
			this(buf.readVarInt(), buf.readVarInt(), buf.readUtf(64));
		}

		private void write(FriendlyByteBuf buf) {
			buf.writeVarInt(this.protocol);
			buf.writeVarInt(this.capabilities);
			buf.writeUtf(this.version, 64);
		}

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** Server answer to {@link HelloC2S}. {@code allowed} tells the client whether it may use the tools. */
	public record HelloS2C(int protocol, String version, boolean allowed) implements CustomPacketPayload {
		public static final Type<HelloS2C> TYPE = payloadType("hello_ack");
		public static final StreamCodec<FriendlyByteBuf, HelloS2C> CODEC = CustomPacketPayload.codec(HelloS2C::write, HelloS2C::new);

		private HelloS2C(FriendlyByteBuf buf) {
			this(buf.readVarInt(), buf.readUtf(64), buf.readBoolean());
		}

		private void write(FriendlyByteBuf buf) {
			buf.writeVarInt(this.protocol);
			buf.writeUtf(this.version, 64);
			buf.writeBoolean(this.allowed);
		}

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	// ------------------------------------------------------------------ world markers

	/**
	 * One in-world label and/or block outline.
	 *
	 * @param yOffset label height above the block's bottom face
	 * @param color   ARGB
	 * @param style   bit set of STYLE_* flags
	 */
	public record MarkerData(BlockPos pos, float yOffset, String text, int color, byte style) {
		static MarkerData read(FriendlyByteBuf buf) {
			return new MarkerData(buf.readBlockPos(), buf.readFloat(), buf.readUtf(256), buf.readInt(), buf.readByte());
		}

		void write(FriendlyByteBuf buf) {
			buf.writeBlockPos(this.pos);
			buf.writeFloat(this.yOffset);
			buf.writeUtf(this.text.length() > 256 ? this.text.substring(0, 256) : this.text, 256);
			buf.writeInt(this.color);
			buf.writeByte(this.style);
		}
	}

	/** Replaces all markers of one channel ("meters" or "pistorder"). Empty list clears it. */
	public record MarkersS2C(String channel, List<MarkerData> markers) implements CustomPacketPayload {
		public static final Type<MarkersS2C> TYPE = payloadType("markers");
		public static final StreamCodec<FriendlyByteBuf, MarkersS2C> CODEC = CustomPacketPayload.codec(MarkersS2C::write, MarkersS2C::new);

		private MarkersS2C(FriendlyByteBuf buf) {
			this(buf.readUtf(32), readList(buf));
		}

		private static List<MarkerData> readList(FriendlyByteBuf buf) {
			int n = Math.min(buf.readVarInt(), 8192);
			List<MarkerData> list = new ArrayList<>(n);
			for (int i = 0; i < n; i++) {
				list.add(MarkerData.read(buf));
			}
			return list;
		}

		private void write(FriendlyByteBuf buf) {
			buf.writeUtf(this.channel, 32);
			int n = Math.min(this.markers.size(), 8192);
			buf.writeVarInt(n);
			for (int i = 0; i < n; i++) {
				this.markers.get(i).write(buf);
			}
		}

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	// ------------------------------------------------------------------ meter timeline

	/** {@code container}: the meter is on a hopper/chest/dropper..., the value is its comparator level. */
	public record ScopeChannel(BlockPos pos, String label, int color, boolean container) {
	}

	/** Meter layout and pulse lengths. Sent when meters change and every few ticks for the pulse lengths. */
	public record ScopeConfigS2C(List<ScopeChannel> channels, List<String> stats, int historyTicks, String group, String order)
			implements CustomPacketPayload {
		public static final Type<ScopeConfigS2C> TYPE = payloadType("scope_config");
		public static final StreamCodec<FriendlyByteBuf, ScopeConfigS2C> CODEC = CustomPacketPayload.codec(ScopeConfigS2C::write, ScopeConfigS2C::read);

		private static ScopeConfigS2C read(FriendlyByteBuf buf) {
			int n = Math.min(buf.readVarInt(), MAX_METERS);
			List<ScopeChannel> channels = new ArrayList<>(n);
			List<String> stats = new ArrayList<>(n);
			for (int i = 0; i < n; i++) {
				channels.add(new ScopeChannel(buf.readBlockPos(), buf.readUtf(64), buf.readInt(), buf.readBoolean()));
				stats.add(buf.readUtf(256));
			}
			return new ScopeConfigS2C(channels, stats, buf.readVarInt(), buf.readUtf(64), buf.readUtf(256));
		}

		private void write(FriendlyByteBuf buf) {
			int n = Math.min(this.channels.size(), MAX_METERS);
			buf.writeVarInt(n);
			for (int i = 0; i < n; i++) {
				ScopeChannel c = this.channels.get(i);
				buf.writeBlockPos(c.pos());
				buf.writeUtf(c.label(), 64);
				buf.writeInt(c.color());
				buf.writeBoolean(c.container());
				String s = i < this.stats.size() ? this.stats.get(i) : "";
				buf.writeUtf(s.length() > 256 ? s.substring(0, 256) : s, 256);
			}
			buf.writeVarInt(this.historyTicks);
			buf.writeUtf(trim(this.group, 64), 64);
			buf.writeUtf(trim(this.order, 256), 256);
		}

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/**
	 * One sample per meter for one game tick: end power, state bits (1 active, 2 powered at the end, 4 turned on,
	 * 8 got powered during the tick, so 0-tick pulses show), MeterEvent flags, and {@code order}: what happened
	 * in the tick as (sequence << 8) | (tick phase << 5) | event.
	 */
	public record ScopeSampleS2C(long gameTime, byte[] end, byte[] bits, int[] events, int[][] order) implements CustomPacketPayload {
		public static final Type<ScopeSampleS2C> TYPE = payloadType("scope_sample");
		public static final StreamCodec<FriendlyByteBuf, ScopeSampleS2C> CODEC = CustomPacketPayload.codec(ScopeSampleS2C::write, ScopeSampleS2C::new);

		private ScopeSampleS2C(FriendlyByteBuf buf) {
			this(buf.readLong(), buf.readByteArray(MAX_METERS), buf.readByteArray(MAX_METERS), readEvents(buf), readOrder(buf));
		}

		private static int[][] readOrder(FriendlyByteBuf buf) {
			int n = buf.readVarInt();
			if (n < 0 || n > MAX_METERS) {
				throw new IllegalArgumentException("Bad meter count " + n);
			}
			int[][] order = new int[n][];
			for (int i = 0; i < n; i++) {
				int k = buf.readVarInt();
				if (k < 0 || k > 64) {
					throw new IllegalArgumentException("Bad event count " + k);
				}
				order[i] = new int[k];
				for (int j = 0; j < k; j++) {
					order[i][j] = buf.readVarInt();
				}
			}
			return order;
		}

		private static int[] readEvents(FriendlyByteBuf buf) {
			int n = buf.readVarInt();
			if (n < 0 || n > MAX_METERS) {
				throw new IllegalArgumentException("Bad meter event count " + n);
			}
			int[] events = new int[n];
			for (int i = 0; i < n; i++) {
				events[i] = buf.readVarInt();
			}
			return events;
		}

		private void write(FriendlyByteBuf buf) {
			buf.writeLong(this.gameTime);
			buf.writeByteArray(this.end);
			buf.writeByteArray(this.bits);
			buf.writeVarInt(this.events.length);
			for (int e : this.events) {
				buf.writeVarInt(e);
			}
			buf.writeVarInt(this.order.length);
			for (int[] list : this.order) {
				buf.writeVarInt(list.length);
				for (int e : list) {
					buf.writeVarInt(e);
				}
			}
		}

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	static String trim(String s, int max) {
		if (s == null) {
			return "";
		}
		return s.length() > max ? s.substring(0, max) : s;
	}

	// ------------------------------------------------------------------ piston firing order

	/** The pistons the client shows previews of (replaces the last list; empty = none). */
	public record PistonWatchC2S(List<BlockPos> pistons) implements CustomPacketPayload {
		public static final Type<PistonWatchC2S> TYPE = payloadType("piston_watch");
		public static final StreamCodec<FriendlyByteBuf, PistonWatchC2S> CODEC = CustomPacketPayload.codec(PistonWatchC2S::write, PistonWatchC2S::new);
		public static final int MAX = 64;

		private PistonWatchC2S(FriendlyByteBuf buf) {
			this(readPositions(buf));
		}

		private static List<BlockPos> readPositions(FriendlyByteBuf buf) {
			int n = Math.min(buf.readVarInt(), MAX);
			List<BlockPos> list = new ArrayList<>(n);
			for (int i = 0; i < n; i++) {
				list.add(buf.readBlockPos());
			}
			return list;
		}

		private void write(FriendlyByteBuf buf) {
			int n = Math.min(this.pistons.size(), MAX);
			buf.writeVarInt(n);
			for (int i = 0; i < n; i++) {
				buf.writeBlockPos(this.pistons.get(i));
			}
		}

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** A watched piston started extending: server game time and order inside the tick (higher = later). */
	public record PistonFireS2C(BlockPos pos, long time, long seq) implements CustomPacketPayload {
		public static final Type<PistonFireS2C> TYPE = payloadType("piston_fire");
		public static final StreamCodec<FriendlyByteBuf, PistonFireS2C> CODEC = CustomPacketPayload.codec(PistonFireS2C::write, PistonFireS2C::new);

		private PistonFireS2C(FriendlyByteBuf buf) {
			this(buf.readBlockPos(), buf.readVarLong(), buf.readVarLong());
		}

		private void write(FriendlyByteBuf buf) {
			buf.writeBlockPos(this.pos);
			buf.writeVarLong(this.time);
			buf.writeVarLong(this.seq);
		}

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}
}
