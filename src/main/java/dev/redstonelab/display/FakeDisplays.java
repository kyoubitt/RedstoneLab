package dev.redstonelab.display;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.chat.TextColor;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;

import dev.redstonelab.RedstoneLab;

/**
 * Builds packet-only text_display / block_display entities for vanilla clients.
 *
 * <p>The entities are created from the same NBT format {@code /summon} uses (public,
 * stable format), but never added to the level: only the spawn + data packets are sent to
 * one player. Nothing is saved to disk, other players never see them, and logging out or
 * stopping the server cannot leave anything behind.</p>
 */
public final class FakeDisplays {
	public static final String TAG = "redstonelab_marker";

	/** A spawned fake entity: its network id plus the packets needed to (re)create it. */
	public record Fake(int id, List<Packet<? super ClientGamePacketListener>> spawnPackets) {
	}

	private FakeDisplays() {
	}

	/** Billboarded, see-through text label centred on the block, {@code yOffset} above its bottom. */
	public static @Nullable Fake label(ServerLevel level, BlockPos pos, float yOffset, String text, int argb, float scale) {
		CompoundTag tag = base("minecraft:text_display");
		Component component = Component.literal(text).withStyle(s -> s.withColor(TextColor.fromRgb(argb & 0xFFFFFF)));
		tag.put("text", ComponentSerialization.CODEC.encodeStart(level.registryAccess().createSerializationContext(NbtOps.INSTANCE), component).getOrThrow());
		tag.putString("billboard", "center");
		tag.putBoolean("see_through", true);
		tag.putBoolean("shadow", true);
		tag.putInt("background", 0x60000000);
		tag.putInt("line_width", 200);
		tag.put("transformation", transformation(0, 0, 0, scale));
		return create(level, tag, pos.getX() + 0.5, pos.getY() + yOffset, pos.getZ() + 0.5);
	}

	/** Glowing outline around a block, coloured with {@code argb}. */
	public static @Nullable Fake outline(ServerLevel level, BlockPos pos, int argb) {
		CompoundTag tag = base("minecraft:block_display");
		CompoundTag blockState = new CompoundTag();
		blockState.putString("Name", "minecraft:glass");
		tag.put("block_state", blockState);
		tag.putBoolean("Glowing", true);
		tag.putInt("glow_color_override", argb & 0xFFFFFF);
		tag.put("transformation", transformation(-0.01f, -0.01f, -0.01f, 1.02f));
		return create(level, tag, pos.getX(), pos.getY(), pos.getZ());
	}

	private static CompoundTag base(String id) {
		CompoundTag tag = new CompoundTag();
		tag.putString("id", id);
		ListTag tags = new ListTag();
		tags.add(net.minecraft.nbt.StringTag.valueOf(TAG));
		tag.put("Tags", tags);
		CompoundTag brightness = new CompoundTag();
		brightness.putInt("sky", 15);
		brightness.putInt("block", 15);
		tag.put("brightness", brightness);
		tag.putFloat("view_range", 2.0f);
		return tag;
	}

	private static Tag transformation(float tx, float ty, float tz, float scale) {
		CompoundTag t = new CompoundTag();
		t.put("translation", floats(tx, ty, tz));
		t.put("left_rotation", floats(0, 0, 0, 1));
		t.put("scale", floats(scale, scale, scale));
		t.put("right_rotation", floats(0, 0, 0, 1));
		return t;
	}

	private static ListTag floats(float... values) {
		ListTag list = new ListTag();
		for (float v : values) {
			list.add(FloatTag.valueOf(v));
		}
		return list;
	}

	private static @Nullable Fake create(ServerLevel level, CompoundTag tag, double x, double y, double z) {
		Entity entity;
		try {
			entity = EntityType.loadEntityRecursive(tag, level, EntitySpawnReason.COMMAND, e -> {
				e.setPos(x, y, z);
				return e;
			});
		} catch (RuntimeException e) {
			RedstoneLab.LOGGER.warn("Could not build marker display entity", e);
			return null;
		}
		if (entity == null) {
			return null;
		}
		List<Packet<? super ClientGamePacketListener>> packets = new ArrayList<>(2);
		packets.add(new ClientboundAddEntityPacket(entity.getId(), entity.getUUID(), x, y, z, 0.0f, 0.0f,
				entity.getType(), 0, Vec3.ZERO, 0.0));
		List<SynchedEntityData.DataValue<?>> data = entity.getEntityData().getNonDefaultValues();
		if (data != null && !data.isEmpty()) {
			packets.add(new ClientboundSetEntityDataPacket(entity.getId(), data));
		}
		return new Fake(entity.getId(), packets);
	}
}
