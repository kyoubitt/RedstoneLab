package dev.redstonelab.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.SignalGetter;
import net.minecraft.world.level.block.DiodeBlock;
import net.minecraft.world.level.block.state.BlockState;

/** Reads a comparator's or repeater's back and side inputs like the game does. */
@Mixin(DiodeBlock.class)
public interface DiodeBlockInvoker {
	@Invoker("getInputSignal")
	int redstonelab$backInput(Level level, BlockPos pos, BlockState state);

	@Invoker("getAlternateSignal")
	int redstonelab$sideInput(SignalGetter level, BlockPos pos, BlockState state);
}
