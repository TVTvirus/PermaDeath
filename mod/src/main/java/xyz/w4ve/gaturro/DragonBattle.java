package xyz.w4ve.gaturro;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.enderdragon.phases.EnderDragonPhase;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * La Batalla de la Dragona (flag "end_battle", dia 7).
 *
 * No es un jefe aparte: es LA dragona del End, pero con gaturro.exe adentro.
 * Tres fases segun su vida; al morir suelta Corazones de Gaturro que caen
 * con un rayo en puntos random de la isla (no encima del cadaver) para que
 * todos tengan la misma oportunidad de correr por ellos.
 *
 * Todo corre a ~1 tick/seg (lo llama GaturroManager.onServerTick).
 */
public class DragonBattle {

    /** Vida por jugador presente en el End, con suelo y techo. */
    private static final int HP_PER_PLAYER = 150;
    private static final int HP_MIN = 800;
    private static final int HP_MAX = 2500;

    /** Cuantos Corazones suelta al morir. */
    private static final int HEARTS_ON_DEATH = 3;
    /** Radio (en bloques, desde el portico) donde caen los Corazones. */
    private static final int HEART_SCATTER_RADIUS = 45;

    private static final String HEART_KEY = "gaturro_heart";

    /** Gatos bomba vivos: UUID -> segundos que le quedan de mecha. */
    private final Map<UUID, Integer> catFuses = new HashMap<>();

    private final MinecraftServer server;
    private final GaturroManager mgr;

    private int bossPhase = 0;   // 0 = sin pelea, 1/2/3 = fase del jefe
    private int tntCd = 0;
    private int catCd = 0;
    private int addsCd = 0;

    public DragonBattle(MinecraftServer server, GaturroManager mgr) {
        this.server = server;
        this.mgr = mgr;
    }

    // ------------------------------------------------------------ tick

    /** Llamado ~1 vez por segundo desde GaturroManager. */
    public void tick() {
        ServerLevel end = server.getLevel(Level.END);
        if (end == null) return;

        List<? extends EnderDragon> dragons = end.getDragons();
        if (dragons.isEmpty() || !mgr.flag("end_battle")) {
            bossPhase = 0;
            catFuses.clear();
            return;
        }
        EnderDragon dragon = dragons.get(0);
        if (!dragon.isAlive()) return;

        scaleHealth(dragon, end);
        tickCats(end);

        float ratio = dragon.getHealth() / dragon.getMaxHealth();
        int phase = ratio > 0.66f ? 1 : (ratio > 0.33f ? 2 : 3);
        if (phase != bossPhase) {
            bossPhase = phase;
            announcePhase(phase, end);
        }

        switch (phase) {
            case 1 -> {
                // "Todavia juega limpio": TNT de vez en cuando, nada mas.
                if (--tntCd <= 0) { dropTnt(end, dragon, 1); tntCd = 8; }
            }
            case 2 -> {
                // "Los gatos": TNT mas seguido + gatos bomba + endermites.
                if (--tntCd <= 0) { dropTnt(end, dragon, 1); tntCd = 4; }
                if (--catCd <= 0) { spawnCats(end, 2); catCd = 15; }
                if (--addsCd <= 0) { spawnAdds(end, EntityType.ENDERMITE, 3); addsCd = 25; }
            }
            case 3 -> {
                // "gaturro.exe": salvas de TNT, gatos seguidos, shulkers y cargas.
                if (--tntCd <= 0) { dropTnt(end, dragon, 3); tntCd = 3; }
                if (--catCd <= 0) { spawnCats(end, 3); catCd = 10; }
                if (--addsCd <= 0) { spawnAdds(end, EntityType.SHULKER, 2); addsCd = 40; }
                // Se pone pesada: carga a un jugador en vez de dar vueltas.
                if (dragon.getRandom().nextInt(6) == 0) {
                    dragon.getPhaseManager().setPhase(EnderDragonPhase.CHARGING_PLAYER);
                }
            }
            default -> { }
        }
    }

    /**
     * Escala la vida al numero de jugadores en el End, pero SOLO mientras esté
     * intacta: al primer golpe la barra queda congelada. Asi la gente puede ir
     * llegando y la dragona se ajusta sola, sin curarse a mitad de pelea.
     */
    private void scaleHealth(EnderDragon dragon, ServerLevel end) {
        if (dragon.getHealth() < dragon.getMaxHealth() - 0.01f) return; // ya empezo la pelea
        AttributeInstance attr = dragon.getAttribute(Attributes.MAX_HEALTH);
        if (attr == null) return;

        int players = 0;
        for (ServerPlayer p : end.players()) {
            if (!p.isSpectator() && !mgr.state().isEliminated(p.getUUID())) players++;
        }
        int target = Math.clamp((long) players * HP_PER_PLAYER, HP_MIN, HP_MAX);
        if ((int) attr.getBaseValue() != target) {
            attr.setBaseValue(target);
            dragon.setHealth(target);
            PermadeathGaturro.LOGGER.info("[gaturro] Dragona escalada a {} HP ({} jugadores en el End)",
                    target, players);
        }
    }

    private void announcePhase(int phase, ServerLevel end) {
        switch (phase) {
            case 2 -> {
                mgr.broadcast(Component.literal(Lang.get("dragon.phase2"))
                        .withStyle(ChatFormatting.LIGHT_PURPLE));
                mgr.broadcastSound(SoundEvents.ENDER_DRAGON_GROWL, 1.0F, 1.2F);
            }
            case 3 -> {
                mgr.broadcast(Component.literal(Lang.get("dragon.phase3"))
                        .withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD));
                mgr.broadcastSound(SoundEvents.ENDER_DRAGON_GROWL, 1.0F, 0.5F);
                // Ceguera de bienvenida a los que estan en el End.
                for (ServerPlayer p : end.players()) {
                    p.addEffect(new MobEffectInstance(MobEffects.DARKNESS, 60, 0, false, false));
                }
            }
            default -> { }
        }
    }

    // ------------------------------------------------------------ ataques

    /** Suelta TNT cebada desde la panza de la dragona. */
    private void dropTnt(ServerLevel end, EnderDragon dragon, int count) {
        for (int i = 0; i < count; i++) {
            double ox = (dragon.getRandom().nextDouble() - 0.5) * 4.0;
            double oz = (dragon.getRandom().nextDouble() - 0.5) * 4.0;
            PrimedTnt tnt = new PrimedTnt(end, dragon.getX() + ox, dragon.getY() - 1.5,
                    dragon.getZ() + oz, null);
            tnt.setFuse(60); // 3s: da tiempo a apartarse
            end.addFreshEntity(tnt);
        }
    }

    /** Gatos bomba cerca de jugadores random. Explotan al acercarse o al agotar la mecha. */
    private void spawnCats(ServerLevel end, int count) {
        List<ServerPlayer> targets = livingPlayers(end);
        if (targets.isEmpty()) return;
        for (int i = 0; i < count; i++) {
            ServerPlayer target = targets.get(end.getRandom().nextInt(targets.size()));
            BlockPos at = target.blockPosition().offset(
                    end.getRandom().nextInt(11) - 5, 1, end.getRandom().nextInt(11) - 5);
            Entity cat = EntityType.CAT.spawn(end, at, EntitySpawnReason.EVENT);
            if (cat != null) {
                cat.setCustomName(Component.literal("gaturro.exe").withStyle(ChatFormatting.DARK_RED));
                catFuses.put(cat.getUUID(), 8);
            }
        }
    }

    /** Mecha de los gatos: explotan si te alcanzan o cuando se acaba el tiempo. */
    private void tickCats(ServerLevel end) {
        if (catFuses.isEmpty()) return;
        List<UUID> done = new ArrayList<>();
        for (Map.Entry<UUID, Integer> e : catFuses.entrySet()) {
            Entity cat = end.getEntity(e.getKey());
            if (cat == null || !cat.isAlive()) { done.add(e.getKey()); continue; }

            boolean close = false;
            for (ServerPlayer p : livingPlayers(end)) {
                if (p.distanceToSqr(cat) < 6.25) { close = true; break; } // 2.5 bloques
            }
            int fuse = e.getValue() - 1;
            if (close || fuse <= 0) {
                // Potencia 3: duele y rompe algo, pero no es el 200 del mod original.
                end.explode(cat, cat.getX(), cat.getY(), cat.getZ(), 3.0F, Level.ExplosionInteraction.MOB);
                cat.discard();
                done.add(e.getKey());
            } else {
                e.setValue(fuse);
            }
        }
        for (UUID id : done) catFuses.remove(id);
    }

    /** Bichos de acompañamiento cerca de jugadores random. */
    private void spawnAdds(ServerLevel end, EntityType<?> type, int count) {
        List<ServerPlayer> targets = livingPlayers(end);
        if (targets.isEmpty()) return;
        for (int i = 0; i < count; i++) {
            ServerPlayer target = targets.get(end.getRandom().nextInt(targets.size()));
            BlockPos at = target.blockPosition().offset(
                    end.getRandom().nextInt(9) - 4, 0, end.getRandom().nextInt(9) - 4);
            type.spawn(end, at, EntitySpawnReason.EVENT);
        }
    }

    private List<ServerPlayer> livingPlayers(ServerLevel end) {
        List<ServerPlayer> out = new ArrayList<>();
        for (ServerPlayer p : end.players()) {
            if (!p.isSpectator() && !mgr.state().isEliminated(p.getUUID())) out.add(p);
        }
        return out;
    }

    // ------------------------------------------------------------ muerte y premio

    /** La dragona murio: los Corazones caen con un rayo en puntos random de la isla. */
    public void onDragonDeath(EnderDragon dragon) {
        if (!mgr.flag("end_battle")) return;
        ServerLevel end = server.getLevel(Level.END);
        if (end == null) return;

        bossPhase = 0;
        catFuses.clear();

        BlockPos origin = dragon.getFightOrigin();
        StringBuilder coords = new StringBuilder();
        for (int i = 0; i < HEARTS_ON_DEATH; i++) {
            BlockPos at = randomIslandSpot(end, origin);
            strike(end, at);
            ItemEntity drop = new ItemEntity(end, at.getX() + 0.5, at.getY() + 1.0, at.getZ() + 0.5, heart());
            drop.setUnlimitedLifetime(); // que no se despawnee mientras corren por el
            end.addFreshEntity(drop);
            if (i > 0) coords.append("  ·  ");
            coords.append("[").append(at.getX()).append(", ").append(at.getZ()).append("]");
        }

        mgr.broadcastTitle(
                Component.literal(Lang.get("dragon.down.title")).withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD),
                Component.literal(Lang.f("dragon.down.subtitle", HEARTS_ON_DEATH))
                        .withStyle(ChatFormatting.GRAY),
                10, 100, 25);
        mgr.broadcastSound(SoundEvents.ENDER_DRAGON_DEATH, 1.0F, 0.8F);
        mgr.broadcast(Component.literal(Lang.f("dragon.down.chat", HEARTS_ON_DEATH))
                .withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD)
                .append(Component.literal(coords.toString()).withStyle(ChatFormatting.GOLD)));
        mgr.broadcast(Component.literal(Lang.f("dragon.down.hint", HEARTS_ON_DEATH))
                .withStyle(ChatFormatting.RED, ChatFormatting.ITALIC));

        if (DiscordNotifier.get() != null) {
            DiscordNotifier.get().dragonDown(mgr.day(), HEARTS_ON_DEATH, coords.toString());
        }
    }

    /** Punto random de la isla con suelo, para que nadie campee el drop. */
    private BlockPos randomIslandSpot(ServerLevel end, BlockPos origin) {
        for (int attempt = 0; attempt < 40; attempt++) {
            int x = origin.getX() + end.getRandom().nextInt(HEART_SCATTER_RADIUS * 2) - HEART_SCATTER_RADIUS;
            int z = origin.getZ() + end.getRandom().nextInt(HEART_SCATTER_RADIUS * 2) - HEART_SCATTER_RADIUS;
            int y = end.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
            if (y > end.getMinY() + 1) return new BlockPos(x, y, z);
        }
        return origin.above(2); // la isla esta rarisima: que caiga en el portico
    }

    private void strike(ServerLevel end, BlockPos at) {
        LightningBolt bolt = EntityType.LIGHTNING_BOLT.create(end, EntitySpawnReason.EVENT);
        if (bolt == null) return;
        bolt.setPos(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        bolt.setVisualOnly(true); // marca el sitio, no incendia ni mata a nadie
        end.addFreshEntity(bolt);
    }

    // ------------------------------------------------------------ el Corazon

    public static ItemStack heart() {
        ItemStack s = new ItemStack(Items.NETHER_STAR);
        s.set(DataComponents.CUSTOM_NAME, Component.literal(Lang.get("heart.name"))
                .withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
        s.set(DataComponents.LORE, new ItemLore(List.of(
                Component.literal(Lang.get("heart.lore1")).withStyle(ChatFormatting.GRAY),
                Component.literal(Lang.get("heart.lore2")).withStyle(ChatFormatting.DARK_GRAY))));
        CompoundTag tag = new CompoundTag();
        tag.putBoolean(HEART_KEY, true);
        s.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        return s;
    }

    public static boolean isHeart(ItemStack s) {
        if (s == null || s.isEmpty()) return false;
        CustomData cd = s.get(DataComponents.CUSTOM_DATA);
        return cd != null && cd.copyTag().getBooleanOr(HEART_KEY, false);
    }

    /** Canje: click derecho con el Corazon en la mano = +1 vida. */
    public boolean consumeHeart(ServerPlayer player, ItemStack stack) {
        if (!isHeart(stack)) return false;
        UUID id = player.getUUID();
        if (mgr.state().isEliminated(id)) return false; // los espermabaneados no vuelven asi

        int lives = mgr.state().getLives(id) + 1;
        mgr.state().setLives(id, lives);
        mgr.save();
        stack.shrink(1);

        String name = player.getGameProfile().name();
        mgr.broadcast(Component.literal(Lang.f("heart.used", name))
                .withStyle(ChatFormatting.RED, ChatFormatting.BOLD)
                .append(Component.literal(Lang.f("heart.lives_now", lives)).withStyle(ChatFormatting.GRAY)));
        mgr.broadcastSound(SoundEvents.WITHER_SPAWN, 1.0F, 1.4F);
        if (DiscordNotifier.get() != null) DiscordNotifier.get().heartUsed(name, lives, mgr.day());
        return true;
    }
}
