package dev.redstonelab.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.world.entity.item.ItemEntity;

/** The item's pickup delay (only the server knows it), for item labels. */
@Mixin(ItemEntity.class)
public interface ItemEntityAccessor {
	@Accessor("pickupDelay")
	int redstonelab$pickupDelay();
}
