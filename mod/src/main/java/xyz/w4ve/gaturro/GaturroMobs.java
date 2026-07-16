package xyz.w4ve.gaturro;

import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.Phantom;
import net.minecraft.world.entity.monster.spider.Spider;
import net.minecraft.world.level.entity.EntityTypeTest;

import java.util.ArrayList;
import java.util.List;

/**
 * Flags de mobs del calendario:
 *  - storm_buff_mobs (D3): durante gaturro.exe los hostiles llevan Speed+Strength.
 *  - spider_effect (D5) / spider_effect_high (D21): arañas con efectos al spawnear.
 *  - giant_phantoms (D27): phantoms gigantes con el triple de vida.
 *  (double_mobs va aparte, en MobCategoryMixin: dobla el cap de hostiles.)
 */
public final class GaturroMobs {

    /** Pool de efectos araña "normal" (uno al azar). */
    private static final List<Holder<MobEffect>> SPIDER_POOL = List.of(
            MobEffects.SPEED, MobEffects.STRENGTH, MobEffects.REGENERATION);
    /** Pool "high": mas fuerte, y caen 2-3 a la vez. */
    private static final List<Holder<MobEffect>> SPIDER_POOL_HIGH = List.of(
            MobEffects.SPEED, MobEffects.STRENGTH, MobEffects.REGENERATION,
            MobEffects.INVISIBILITY, MobEffects.RESISTANCE);

    private GaturroMobs() {}

    /** Nuevos bichos (spawn o carga de chunk). Reaplicar en carga es inofensivo. */
    public static void onEntityLoad(Entity entity, ServerLevel level, GaturroManager m) {
        if (entity instanceof Spider spider) {
            if (m.flag("spider_effect_high")) {
                int n = 2 + level.getRandom().nextInt(2); // 2-3 efectos
                for (int i = 0; i < n; i++) {
                    Holder<MobEffect> ef = SPIDER_POOL_HIGH.get(level.getRandom().nextInt(SPIDER_POOL_HIGH.size()));
                    spider.addEffect(new MobEffectInstance(ef, MobEffectInstance.INFINITE_DURATION, 1, false, true));
                }
            } else if (m.flag("spider_effect")) {
                Holder<MobEffect> ef = SPIDER_POOL.get(level.getRandom().nextInt(SPIDER_POOL.size()));
                spider.addEffect(new MobEffectInstance(ef, MobEffectInstance.INFINITE_DURATION, 0, false, true));
            }
        } else if (entity instanceof Phantom phantom && m.flag("giant_phantoms")) {
            phantom.setPhantomSize(5); // gordo de verdad (vanilla natural = 0)
            AttributeInstance hp = phantom.getAttribute(Attributes.MAX_HEALTH);
            if (hp != null && hp.getBaseValue() < 60) {
                hp.setBaseValue(60); // x3 de lo normal (20)
                phantom.setHealth(60);
            }
        }
    }

    /**
     * Buff de tormenta: cada segundo, mientras gaturro.exe este activa, todos los
     * hostiles cargados llevan Speed+Strength por 3s. Al parar la tormenta los
     * efectos caducan solos: no hay que limpiar nada.
     */
    public static void tickStormBuffs(ServerLevel overworld, GaturroManager m) {
        if (!m.flag("storm_buff_mobs")) return;
        List<Monster> monsters = new ArrayList<>();
        overworld.getEntities(EntityTypeTest.forClass(Monster.class), e -> true, monsters);
        for (Monster mob : monsters) {
            mob.addEffect(new MobEffectInstance(MobEffects.SPEED, 70, 0, true, false));
            mob.addEffect(new MobEffectInstance(MobEffects.STRENGTH, 70, 0, true, false));
        }
    }

    private static final String SGA_KEY = "gaturro_sga";

    /** Manzana de Gaturro: golden apple marcada, devuelve un corazon perdido. */
    public static net.minecraft.world.item.ItemStack superApple() {
        var s = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.GOLDEN_APPLE);
        s.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
                net.minecraft.network.chat.Component.literal("Manzana de Gaturro")
                        .withStyle(net.minecraft.ChatFormatting.GOLD, net.minecraft.ChatFormatting.BOLD));
        s.set(net.minecraft.core.component.DataComponents.LORE,
                new net.minecraft.world.item.component.ItemLore(List.of(
                        net.minecraft.network.chat.Component.literal("Click derecho: +1 corazón perdido")
                                .withStyle(net.minecraft.ChatFormatting.GRAY))));
        var tag = new net.minecraft.nbt.CompoundTag();
        tag.putBoolean(SGA_KEY, true);
        s.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                net.minecraft.world.item.component.CustomData.of(tag));
        return s;
    }

    public static boolean isSuperApple(net.minecraft.world.item.ItemStack s) {
        if (s == null || s.isEmpty()) return false;
        var cd = s.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
        return cd != null && cd.copyTag().getBooleanOr(SGA_KEY, false);
    }

    /** Drop de Manzana: 8% cuando un JUGADOR mata un hostil (con heart_slot_loss activo). */
    public static void onMonsterKilled(Monster mob, ServerLevel level, GaturroManager m) {
        if (!m.flag("heart_slot_loss")) return;
        if (level.getRandom().nextInt(100) >= 8) return;
        var drop = new net.minecraft.world.entity.item.ItemEntity(level,
                mob.getX(), mob.getY() + 0.3, mob.getZ(), superApple());
        level.addFreshEntity(drop);
    }

    /** Para /pdc: cuantos hostiles hay buffeados ahora mismo (diagnostico). */
    public static int countBuffed(ServerLevel overworld) {
        List<Monster> monsters = new ArrayList<>();
        overworld.getEntities(EntityTypeTest.forClass(Monster.class),
                e -> ((LivingEntity) e).hasEffect(MobEffects.STRENGTH), monsters);
        return monsters.size();
    }
}
