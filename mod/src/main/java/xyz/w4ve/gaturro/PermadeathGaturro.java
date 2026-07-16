package xyz.w4ve.gaturro;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PermadeathGaturro implements ModInitializer {
    public static final String MOD_ID = "permadeathgaturro";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("[gaturro.exe] Permadeath Gaturro cargando...");

        ServerLifecycleEvents.SERVER_STARTED.register(GaturroManager::init);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> GaturroManager.clear());

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            GaturroManager m = GaturroManager.get();
            if (m != null) m.onServerTick();
        });

        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            GaturroManager m = GaturroManager.get();
            if (m == null) return;
            if (entity instanceof ServerPlayer sp) {
                m.onPlayerDeath(sp);
            } else if (entity instanceof EnderDragon dragon && m.battle() != null) {
                m.battle().onDragonDeath(dragon);
            } else if (entity instanceof net.minecraft.world.entity.monster.Monster mob
                    && source.getEntity() instanceof ServerPlayer
                    && mob.level() instanceof net.minecraft.server.level.ServerLevel sl) {
                GaturroMobs.onMonsterKilled(mob, sl, m); // puede soltar Manzana de Gaturro
            }
        });

        // Corazón de Gaturro (+1 vida) y Manzana de Gaturro (+1 corazón): click derecho.
        UseItemCallback.EVENT.register((player, level, hand) -> {
            GaturroManager m = GaturroManager.get();
            ItemStack stack = player.getItemInHand(hand);
            if (m == null || level.isClientSide() || !(player instanceof ServerPlayer sp)) {
                return InteractionResult.PASS;
            }
            if (DragonBattle.isHeart(stack) && m.battle() != null && m.battle().consumeHeart(sp, stack)) {
                return InteractionResult.SUCCESS_SERVER;
            }
            if (GaturroMobs.isSuperApple(stack) && m.eatSuperApple(sp)) {
                stack.shrink(1);
                return InteractionResult.SUCCESS_SERVER;
            }
            return InteractionResult.PASS;
        });

        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
            GaturroManager m = GaturroManager.get();
            if (m != null) m.onRespawn(newPlayer);
        });

        // Chat bridge: el chat del server viaja al canal de Discord
        net.fabricmc.fabric.api.message.v1.ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) -> {
            if (DiscordNotifier.get() != null) {
                DiscordNotifier.get().chat(sender.getGameProfile().name(), message.signedContent());
            }
        });

        // Flags de mobs: aranas con efectos, phantoms gigantes (al spawnear/cargar)
        ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
            GaturroManager m = GaturroManager.get();
            if (m != null) GaturroMobs.onEntityLoad(entity, level, m);
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            GaturroManager m = GaturroManager.get();
            if (m != null) m.onJoin(handler.player);
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            GaturroManager m = GaturroManager.get();
            if (m != null) m.onDisconnect(handler.player);
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                PdcCommand.register(dispatcher));

        LOGGER.info("[gaturro.exe] Listo. Que empiece la matanza.");
    }
}
