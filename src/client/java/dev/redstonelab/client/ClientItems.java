package dev.redstonelab.client;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import dev.redstonelab.client.ui.LabConfig;
import dev.redstonelab.net.LabPayloads;

/** Item labels: asks the server for nearby items' age and pickup delay, keeps the latest answer. */
public final class ClientItems {
	private static final int[] NONE = new int[0];
	public static int[] ids = NONE;
	public static int[] ages = NONE;
	public static int[] pickups = NONE;
	/** Range last sent to the server (-1 = nothing sent yet on this server). */
	private static int sent = -1;

	private ClientItems() {
	}

	/** Every client tick: tells the server when the setting changed (or after joining). */
	public static void tick() {
		int want = LabConfig.ITEM_LABELS.value && ClientState.serverHasMod ? LabConfig.ITEM_RANGE.value : 0;
		if (want != sent && ClientPlayNetworking.canSend(LabPayloads.ItemWatchC2S.TYPE)) {
			ClientPlayNetworking.send(new LabPayloads.ItemWatchC2S(want));
			sent = want;
		}
		if (want == 0) {
			ids = ages = pickups = NONE;
		}
	}

	public static void update(LabPayloads.ItemInfoS2C payload) {
		ids = payload.ids();
		ages = payload.ages();
		pickups = payload.pickups();
	}

	public static void reset() {
		sent = -1;
		ids = ages = pickups = NONE;
	}
}
