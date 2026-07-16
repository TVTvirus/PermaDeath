package xyz.w4ve.gaturro.mixin;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import xyz.w4ve.gaturro.GaturroManager;

/**
 * totem_fail (D15) / totem_fail_high (D24): el totem puede fallar.
 * Se corta ANTES de que el juego lo gaste: si falla, mueres CON el totem
 * en la mano (mas cruel, y el drama de "¡me fallo el totem!" es real).
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {

    @Inject(method = "checkTotemDeathProtection", at = @At("HEAD"), cancellable = true)
    private void gaturro$totemFail(DamageSource source, CallbackInfoReturnable<Boolean> cir) {
        if (!((Object) this instanceof ServerPlayer player)) return; // solo jugadores, los mobs que sufran lo suyo
        GaturroManager m = GaturroManager.get();
        if (m == null) return;
        int failPct = m.flag("totem_fail_high") ? 30 : (m.flag("totem_fail") ? 15 : 0);
        if (failPct > 0 && player.getRandom().nextInt(100) < failPct) {
            m.onTotemFailed(player);
            cir.setReturnValue(false); // el totem "no estaba de humor"
        }
    }
}
