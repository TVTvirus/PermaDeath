package xyz.w4ve.gaturro.mixin;

import net.minecraft.server.players.SleepStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import xyz.w4ve.gaturro.GaturroManager;

/**
 * min_sleep_3 (D9): hacen falta MINIMO 3 durmiendo para saltar la noche
 * (ademas del porcentaje vanilla). Si hay menos de 3 jugadores online,
 * exige a todos los que esten.
 */
@Mixin(SleepStatus.class)
public abstract class SleepStatusMixin {

    @Shadow private int activePlayers;
    @Shadow private int sleepingPlayers;

    @Inject(method = "areEnoughSleeping", at = @At("RETURN"), cancellable = true)
    private void gaturro$minSleep(int pct, CallbackInfoReturnable<Boolean> cir) {
        GaturroManager m = GaturroManager.get();
        if (m == null || !m.flag("min_sleep_3") || !cir.getReturnValue()) return;
        int needed = Math.min(3, Math.max(1, this.activePlayers));
        if (this.sleepingPlayers < needed) cir.setReturnValue(false);
    }
}
