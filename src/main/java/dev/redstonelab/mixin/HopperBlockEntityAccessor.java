package dev.redstonelab.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.world.level.block.entity.HopperBlockEntity;

/** Ticks until the hopper moves items again, for item labels ("hopper in 3t"). */
@Mixin(HopperBlockEntity.class)
public interface HopperBlockEntityAccessor {
	@Accessor("cooldownTime")
	int redstonelab$cooldown();
}
