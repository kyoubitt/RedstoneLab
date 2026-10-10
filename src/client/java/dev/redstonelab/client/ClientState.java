package dev.redstonelab.client;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.core.BlockPos;

import dev.redstonelab.net.LabPayloads;

/**
 * Everything the client received from the server. Plain data, no MaLiLib types, so it
 * exists even when MaLiLib is missing.
 */
public final class ClientState {
	/** Server has RedstoneLab and answered the handshake. */
	public static boolean serverHasMod;
	/** The player may use the tools on this server. */
	public static boolean allowed;
	public static String serverVersion = "";

	/** World markers per channel. */
	public static final Map<String, List<LabPayloads.MarkerData>> MARKERS = new LinkedHashMap<>();

	// ------------------------------------------------------------------ meter timeline

	public static final class Channel {
		/** Where the meter is now (it moves with blocks pushed by pistons). */
		public BlockPos pos;
		public final String label;
		public final int color;
		public String stats = "";
		/** Meter on a container: draw the comparator level as a fill bar. */
		public boolean container;
		/** Ring buffers: power level at the end of the tick, and state bits (1 active, 2 powered at the end; 4 / 8 = turned on / got powered during the tick). */
		public final byte[] end;
		public final byte[] bits;
		/** {@link dev.redstonelab.core.MeterEvent} flags per sample. */
		public final int[] events;
		/** What happened in each tick, in order: (sequence << 8) | (tick phase << 5) | event ordinal. */
		public final int[][] order;

		Channel(BlockPos pos, String label, int color, int capacity) {
			this.pos = pos;
			this.label = label;
			this.color = color;
			this.end = new byte[capacity];
			this.bits = new byte[capacity];
			this.events = new int[capacity];
			this.order = new int[capacity][];
		}
	}

	public static final List<Channel> SCOPE = new ArrayList<>();
	/** Game time of each sample slot (shared by all channels). */
	public static long[] sampleTimes = new long[0];
	/** Index of the next write; samples are a ring buffer. */
	public static int head;
	public static int filled;
	/** Samples kept on the client (the timeline shows at most this many ticks). */
	public static final int CAPACITY = 200;
	public static int capacity = CAPACITY;
	/** Changes whenever meters are added or removed. */
	public static int layoutVersion;
	/** When the newest sample arrived, for smooth scrolling. */
	public static long lastSampleNanos;

	private ClientState() {
	}

	public static void reset() {
		serverHasMod = false;
		allowed = false;
		serverVersion = "";
		MARKERS.clear();
		SCOPE.clear();
		sampleTimes = new long[0];
		head = 0;
		filled = 0;
	}

	/** Name of the player's active meter group. */
	public static String group = "default";
	/** "A C → 2t → B D": the order the meters turn on in. */
	public static String order = "";

	public static void applyScopeConfig(LabPayloads.ScopeConfigS2C cfg) {
		group = cfg.group();
		order = cfg.order();
		boolean same = cfg.channels().size() == SCOPE.size();
		for (int i = 0; same && i < SCOPE.size(); i++) {
			Channel c = SCOPE.get(i);
			LabPayloads.ScopeChannel n = cfg.channels().get(i);
			// Same meters (a meter moved by a piston keeps its history).
			same = c.label.equals(n.label()) && c.color == n.color();
		}
		if (!same) {
			capacity = CAPACITY;
			layoutVersion++;
			SCOPE.clear();
			for (LabPayloads.ScopeChannel n : cfg.channels()) {
				SCOPE.add(new Channel(n.pos(), n.label(), n.color(), capacity));
			}
			sampleTimes = new long[capacity];
			head = 0;
			filled = 0;
		}
		for (int i = 0; i < SCOPE.size(); i++) {
			SCOPE.get(i).pos = cfg.channels().get(i).pos();
			SCOPE.get(i).container = cfg.channels().get(i).container();
			SCOPE.get(i).stats = i < cfg.stats().size() ? cfg.stats().get(i) : "";
		}
	}

	public static void addSample(LabPayloads.ScopeSampleS2C sample) {
		if (SCOPE.isEmpty() || sampleTimes.length == 0) {
			return;
		}
		sampleTimes[head] = sample.gameTime();
		for (int i = 0; i < SCOPE.size(); i++) {
			Channel c = SCOPE.get(i);
			c.end[head] = i < sample.end().length ? sample.end()[i] : 0;
			c.bits[head] = i < sample.bits().length ? sample.bits()[i] : 0;
			c.events[head] = i < sample.events().length ? sample.events()[i] : 0;
			c.order[head] = i < sample.order().length ? sample.order()[i] : new int[0];
		}
		head = (head + 1) % capacity;
		lastSampleNanos = System.nanoTime();
		filled = Math.min(filled + 1, capacity);
	}

	/** Index of the sample {@code age} samples ago (0 = newest), or -1. */
	public static int sampleIndex(int age) {
		if (age < 0 || age >= filled) {
			return -1;
		}
		return Math.floorMod(head - 1 - age, capacity);
	}
}
