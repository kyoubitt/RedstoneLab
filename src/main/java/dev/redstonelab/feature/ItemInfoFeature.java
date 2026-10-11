package dev.redstonelab.feature;

import java.util.Comparator;
import java.util.List;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;

import dev.redstonelab.mixin.ItemEntityAccessor;
import dev.redstonelab.net.LabNetworking;
import dev.redstonelab.net.LabPayloads;
import dev.redstonelab.server.LabServer;
import dev.redstonelab.server.PlayerSession;
import dev.redstonelab.server.Sessions;

/**
 * Item labels: every tick, the age and pickup delay of the items near players who turned them
 * on (the client never gets these from the game). Nearest {@link #MAX} items within their range.
 */
public final class ItemInfoFeature {
	public static final int MAX = 64;
	public static final int MAX_RANGE = 32;

	private ItemInfoFeature() {
	}

	public static void tick(MinecraftServer server) {
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			PlayerSession session = Sessions.get(player);
			if (session.itemRange <= 0 || !session.modded || !LabServer.canUse(player)) {
				continue;
			}
			List<ItemEntity> items = collect(player, session.itemRange);
			if (items.isEmpty() && session.itemsSentEmpty) {
				continue;
			}
			session.itemsSentEmpty = items.isEmpty();
			int n = items.size();
			int[] ids = new int[n];
			int[] ages = new int[n];
			int[] pickups = new int[n];
			for (int i = 0; i < n; i++) {
				ItemEntity item = items.get(i);
				ids[i] = item.getId();
				ages[i] = item.getAge();
				pickups[i] = ((ItemEntityAccessor) item).redstonelab$pickupDelay();
			}
			LabNetworking.send(player, new LabPayloads.ItemInfoS2C(ids, ages, pickups));
		}
	}

	/** The nearest items within {@code range} blocks, at most {@link #MAX}. */
	static List<ItemEntity> collect(ServerPlayer player, int range) {
		List<ItemEntity> items = player.level().getEntitiesOfClass(ItemEntity.class, player.getBoundingBox().inflate(range), e -> e.isAlive());
		items.sort(Comparator.comparingDouble(player::distanceToSqr));
		return items.size() > MAX ? items.subList(0, MAX) : items;
	}
}
