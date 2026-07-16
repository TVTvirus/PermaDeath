package xyz.w4ve.gaturro.mixin;

import net.minecraft.world.entity.MobCategory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import xyz.w4ve.gaturro.GaturroManager;

/**
 * double_mobs (dia 5): el "doble de bichos" real = doblar el cap de hostiles
 * por chunk. El spawner natural del juego hace el resto solo.
 */
@Mixin(MobCategory.class)
public abstract class MobCategoryMixin {

    @Inject(method = "getMaxInstancesPerChunk", at = @At("RETURN"), cancellable = true)
    private void gaturro$doubleMobs(CallbackInfoReturnable<Integer> cir) {
        if ((Object) this != MobCategory.MONSTER) return;
        GaturroManager m = GaturroManager.get();
        if (m != null && m.flag("double_mobs")) {
            cir.setReturnValue(cir.getReturnValue() * 2);
        }
    }
}
