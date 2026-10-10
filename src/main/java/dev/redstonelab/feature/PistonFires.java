package dev.redstonelab.feature;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import dev.redstonelab.net.LabNetworking;
import dev.redstonelab.net.LabPayloads;
import dev.redstonelab.server.Sessions;

/**
 * Pistons starting to extend, in the exact order the game runs them, for the firing order of
 * shown pistons: to modded clients that show the piston, and to the server-side previews.
 */
public final class PistonFires {
	/** Goes up by one per extension, so it also orders extensions inside one tick. */
	private static long seq;

	private PistonFires() {
	}

	public static void onExtend(ServerLevel level, BlockPos pos) {
		long time = level.getGameTime();
		long n = ++seq;
		PistorderFeature.onExtend(level, pos, time, n);
		for (ServerPlayer player : level.players()) {
			if (Sessions.get(player).watchedPistons.contains(pos)) {
				LabNetworking.send(player, new LabPayloads.PistonFireS2C(pos.immutable(), time, n));
			}
		}
	}
}
