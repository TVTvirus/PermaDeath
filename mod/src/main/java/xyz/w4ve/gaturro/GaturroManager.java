package xyz.w4ve.gaturro;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.WeatherData;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Path;
import java.util.UUID;

/**
 * Cerebro del permadeath: vidas, gaturro.exe (tormenta) y baneo por AFK.
 */
public class GaturroManager {
    private static GaturroManager instance;

    private final MinecraftServer server;
    private final GaturroState state;
    private final Path file;
    private final GaturroPhases phases;

    private int tickCounter = 0;
    private boolean stormWasActive = false;
    /** Comandos programados para dentro de N segundos (epoch -> comando). */
    private final java.util.List<Object[]> scheduledCmds = new java.util.ArrayList<>();
    private final boolean hasServerReplay =
            net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("server-replay");
    private DragonBattle battle;
    private boolean clipDirReady = false;

    private GaturroManager(MinecraftServer server) {
        this.server = server;
        this.file = server.getWorldPath(LevelResource.ROOT).resolve("permadeath_gaturro.json");
        this.state = GaturroState.load(file);
        this.phases = GaturroPhases.loadOrCreate(
                server.getWorldPath(LevelResource.ROOT).resolve("gaturro_phases.json"));
        DiscordNotifier.init(server.getWorldPath(LevelResource.ROOT).resolve("gaturro_discord.json"));
        long now = now();
        if (state.eventStartEpoch <= 0) {
            state.eventStartEpoch = now;
            PermadeathGaturro.LOGGER.info("[gaturro] Evento arrancado. Dia 1.");
        }
        state.save(file);
        applyDifficulty(phases.difficultyFor(day())); // por si reinicia a mitad
        GaturroScores.ensureObjectives(server);
        this.battle = new DragonBattle(server, this);
    }

    /** SVC es opcional: si no esta el mod, la clase ni carga y devolvemos null. */
    private GaturroVoice voice() {
        try {
            return GaturroVoice.get();
        } catch (Throwable t) {
            return null;
        }
    }

    public DragonBattle battle() {
        return battle;
    }

    public GaturroPhases phases() {
        return phases;
    }

    /** ¿Esta activo un flag de fase hoy? (end_open, poisoned_food, totem_fail, ...) */
    public boolean flag(String f) {
        return phases.flagActive(f, day());
    }

    public static void init(MinecraftServer server) {
        instance = new GaturroManager(server);
    }

    public static GaturroManager get() {
        return instance;
    }

    public static void clear() {
        if (instance != null) {
            instance.state.save(instance.file);
        }
        instance = null;
    }

    private static long now() {
        return System.currentTimeMillis() / 1000L;
    }

    public GaturroState state() {
        return state;
    }

    public void save() {
        state.save(file);
    }

    public Path stateFile() {
        return file;
    }

    public int day() {
        return state.dayOfServer(now());
    }

    /** Salta el evento al dia indicado (para pruebas): mueve la fecha de inicio. */
    public void setDay(int day) {
        state.eventStartEpoch = now() - (long) (Math.max(1, day) - 1) * GaturroState.SECONDS_PER_DAY;
        state.lastAnnouncedPhaseDay = 0; // deja re-anunciar la fase
        save();
    }

    // ---------------------------------------------------------------- muerte

    public void onPlayerDeath(ServerPlayer player) {
        UUID id = player.getUUID();
        String name = player.getGameProfile().name();
        if (state.isEliminated(id)) return;

        String custom = state.deathMessages.get(id.toString());
        String deathText = custom != null ? custom.replace("%player%", name) : name + " ha muerto";

        int lives = state.getLives(id) - 1;
        state.setLives(id, lives);

        long now = now();
        int day = state.dayOfServer(now);
        state.addStormHours(now, day); // +1h por dia del servidor, por esta muerte

        if (DiscordNotifier.get() != null) DiscordNotifier.get().death(name, lives, day, day);

        // Voz global 10s + clip del ultimo minuto (50s antes + estos 10s de reaccion).
        GaturroVoice v = voice();
        if (v != null) v.onDeath(id, name);

        // Replay de la muerte: cortar la grabacion 12s despues (que capture la
        // reaccion) y re-arrancarla. El bot recoge el .mcpr y lo sube al sotano.
        if (hasServerReplay) {
            long cutAt = now + 12;
            scheduledCmds.add(new Object[]{cutAt, "replay stop players " + name + " true"});
            scheduledCmds.add(new Object[]{cutAt + 2, "replay start players " + name});
            final int fday = day;
            scheduledCmds.add(new Object[]{cutAt + 2, (Runnable) () -> {
                if (DiscordNotifier.get() != null) DiscordNotifier.get().deathReplay(name, fday);
            }});
        }

        MutableComponent msg = Component.literal("gaturro.exe ")
                .withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD)
                .append(Component.literal(deathText + ". ")
                        .withStyle(ChatFormatting.RED))
                .append(Component.literal("+" + day + "h de tormenta")
                        .withStyle(ChatFormatting.GRAY));
        broadcast(msg);

        placeDeathMarker(player); // cabeza en un palo donde cayo

        if (flag("heart_slot_loss")) loseHeart(player);

        if (lives <= 0) {
            eliminate(player);
        } else {
            broadcastTitle(
                    Component.literal("☠ " + deathText).withStyle(ChatFormatting.RED, ChatFormatting.BOLD),
                    Component.literal("gaturro.exe +" + day + "h - le quedan " + lives + " vidas")
                            .withStyle(ChatFormatting.GRAY),
                    10, 80, 25);
            broadcastSound(SoundEvents.WITHER_SPAWN, 1.0F, 0.9F);
        }
        state.save(file);
    }

    private void eliminate(ServerPlayer player) {
        state.eliminate(player.getUUID());
        player.setGameMode(GameType.SPECTATOR);
        if (DiscordNotifier.get() != null) DiscordNotifier.get().eliminated(player.getGameProfile().name(), day());
        broadcast(Component.literal("Este es el comienzo del sufrimiento eterno de "
                + player.getGameProfile().name() + ". HA SIDO ESPERMABANEADO!")
                .withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD));
        dramaEliminate(player);
    }

    /** Pantalla dramatica (title) + sonido de explosion para todos. */
    private void dramaEliminate(ServerPlayer dead) {
        broadcastTitle(
                Component.literal("¡EspermaDeath!").withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD),
                Component.literal(dead.getGameProfile().name() + " ha sido EspermaBaneado").withStyle(ChatFormatting.RED),
                10, 120, 30);
        broadcastSound(SoundEvents.WITHER_SPAWN, 1.0F, 0.5F);
        broadcastSound(SoundEvents.ENDER_DRAGON_GROWL, 1.0F, 0.7F);
    }

    private void sendTitle(ServerPlayer p, Component title, Component sub, int fadeIn, int stay, int fadeOut) {
        p.connection.send(new ClientboundSetTitlesAnimationPacket(fadeIn, stay, fadeOut));
        p.connection.send(new ClientboundSetTitleTextPacket(title));
        p.connection.send(new ClientboundSetSubtitleTextPacket(sub));
    }

    public void broadcastTitle(Component title, Component sub, int fadeIn, int stay, int fadeOut) {
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            sendTitle(p, title, sub, fadeIn, stay, fadeOut);
        }
    }

    /** Muestra el anuncio de la fase actual a un jugador (al conectarse). */
    private void showCurrentPhase(ServerPlayer player) {
        GaturroPhases.Phase cur = phases.current(day());
        if (cur == null) return;
        sendTitle(player,
                Component.literal("Día " + day() + " - " + cur.name).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD),
                Component.literal(cur.announce).withStyle(ChatFormatting.GRAY),
                10, 90, 20);
    }

    /** Envia el sonido como paquete directo a cada jugador: se oye si o si, a todo volumen. */
    public void broadcastSound(SoundEvent sound, float vol, float pitch) {
        Holder<SoundEvent> holder = Holder.direct(sound);
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            p.connection.send(new ClientboundSoundPacket(holder, SoundSource.MASTER,
                    p.getX(), p.getY(), p.getZ(), vol, pitch, 0L));
        }
    }

    /**
     * Memorial: cabeza del muerto en un palo, en el lugar exacto de la muerte.
     * Busca suelo hacia abajo (max 24 bloques) y solo pisa bloques reemplazables:
     * nunca rompe construcciones. En el void no hay memorial que valga.
     */
    private void placeDeathMarker(ServerPlayer p) {
        if (!(p.level() instanceof ServerLevel level)) return;
        BlockPos base = findMarkerSpot(level, p.blockPosition());
        if (base == null) return;
        level.setBlock(base, net.minecraft.world.level.block.Blocks.OAK_FENCE.defaultBlockState(), 3);
        var head = net.minecraft.world.level.block.Blocks.PLAYER_HEAD.defaultBlockState()
                .setValue(net.minecraft.world.level.block.SkullBlock.ROTATION, level.getRandom().nextInt(16));
        BlockPos hp = base.above();
        level.setBlock(hp, head, 3);
        if (level.getBlockEntity(hp) instanceof net.minecraft.world.level.block.entity.SkullBlockEntity skull) {
            skull.applyComponents(net.minecraft.core.component.DataComponentMap.EMPTY,
                    net.minecraft.core.component.DataComponentPatch.builder()
                            .set(net.minecraft.core.component.DataComponents.PROFILE,
                                    net.minecraft.world.item.component.ResolvableProfile.createResolved(p.getGameProfile()))
                            .build());
            skull.setChanged();
            level.sendBlockUpdated(hp, head, head, 3);
        }
    }

    private BlockPos findMarkerSpot(ServerLevel level, BlockPos death) {
        if (death.getY() <= level.getMinY()) return null; // void: no hay donde clavar el palo
        BlockPos.MutableBlockPos cur = death.mutable();
        for (int i = 0; i < 24 && cur.getY() > level.getMinY() + 1; i++) {
            if (level.getBlockState(cur.below()).isSolid()
                    && level.getBlockState(cur).canBeReplaced()
                    && level.getBlockState(cur.above()).canBeReplaced()) {
                return cur.immutable();
            }
            cur.move(0, -1, 0);
        }
        return null;
    }

    /** heart_slot_loss: aplica la vida maxima que le toca (20 - lo perdido). */
    public void applyHearts(ServerPlayer p) {
        int lost = state.hpLost.getOrDefault(p.getUUID().toString(), 0);
        var attr = p.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH);
        if (attr == null) return;
        double target = Math.max(6, 20 - lost); // suelo: 3 corazones
        if (attr.getBaseValue() != target) {
            attr.setBaseValue(target);
            if (p.getHealth() > target) p.setHealth((float) target);
        }
    }

    /** Muerte con heart_slot_loss: -1 corazon permanente (se recupera con la Manzana). */
    private void loseHeart(ServerPlayer p) {
        String id = p.getUUID().toString();
        int lost = Math.min(14, state.hpLost.getOrDefault(id, 0) + 2);
        state.hpLost.put(id, lost);
        p.sendSystemMessage(Component.literal("Perdiste un corazón. Te quedan "
                + ((20 - lost) / 2) + ". Las Manzanas de Gaturro los devuelven.")
                .withStyle(ChatFormatting.DARK_RED));
    }

    /** Manzana de Gaturro: devuelve un corazon perdido. */
    public boolean eatSuperApple(ServerPlayer p) {
        String id = p.getUUID().toString();
        int lost = state.hpLost.getOrDefault(id, 0);
        if (lost <= 0) {
            p.sendSystemMessage(Component.literal("No te falta ningún corazón. Guárdala para cuando duela.")
                    .withStyle(ChatFormatting.GRAY));
            return false;
        }
        state.hpLost.put(id, lost - 2);
        save();
        applyHearts(p);
        p.sendSystemMessage(Component.literal("Recuperaste un corazón. Ahora tienes "
                + ((20 - lost + 2) / 2) + ".").withStyle(ChatFormatting.GREEN));
        return true;
    }

    /** Anuncio dramatico de revive (para /pdc revive): que nadie flipe con el zombi. */
    public void announceRevive(String name, int lives) {
        broadcastTitle(
                Component.literal("✟ " + name + " HA VUELTO").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD),
                Component.literal("Los dioses del Mundo Gaturro le dieron " + lives + " vida" + (lives == 1 ? "" : "s"))
                        .withStyle(ChatFormatting.GRAY),
                10, 90, 25);
        broadcastSound(SoundEvents.TOTEM_USE, 1.0F, 1.0F);
        if (DiscordNotifier.get() != null) DiscordNotifier.get().revived(name, lives, day());
    }

    /** El totem le fallo a alguien (mixin totem_fail): que se sepa. */
    public void onTotemFailed(ServerPlayer player) {
        String name = player.getGameProfile().name();
        broadcast(Component.literal("El tótem de " + name + " FALLÓ. ")
                .withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD)
                .append(Component.literal("Les dijimos que no confiaran.")
                        .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC)));
        broadcastSound(SoundEvents.ITEM_BREAK.value(), 1.0F, 0.6F);
    }

    /** Reinicia los stats de un jugador (3 vidas, quita eliminado, survival). */
    public void resetPlayer(UUID id) {
        state.revive(id, GaturroState.DEFAULT_LIVES);
        state.seen(id, now());
        ServerPlayer p = server.getPlayerList().getPlayer(id);
        if (p != null) p.setGameMode(GameType.SURVIVAL);
        save();
    }

    /** Reinicia TODO el evento: Dia 1, sin tormenta, todos con 3 vidas. */
    public void resetAll() {
        long n = now();
        state.lives.clear();
        state.eliminated.clear();
        state.lastSeen.clear();
        state.stormEndsAtEpoch = 0;
        state.eventStartEpoch = n;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            p.setGameMode(GameType.SURVIVAL);
            state.seen(p.getUUID(), n);
        }
        ServerLevel ow = server.overworld();
        if (ow != null) clearStorm(ow);
        stormWasActive = false;
        save();
    }

    public void onRespawn(ServerPlayer player) {
        if (state.isEliminated(player.getUUID())) {
            player.setGameMode(GameType.SPECTATOR);
        }
        if (flag("heart_slot_loss")) applyHearts(player);
    }

    // ---------------------------------------------------------------- conexion

    public void onJoin(ServerPlayer player) {
        UUID id = player.getUUID();
        state.seen(id, now());
        if (state.isEliminated(id)) {
            player.setGameMode(GameType.SPECTATOR);
            player.sendSystemMessage(Component.literal("Estas eliminado. Solo espectador.")
                    .withStyle(ChatFormatting.DARK_RED));
        } else {
            player.sendSystemMessage(Component.literal("Dia " + day() + " - Te quedan "
                    + state.getLives(id) + " vidas.").withStyle(ChatFormatting.GOLD));
            showCurrentPhase(player); // por si se perdio el cambio de fase estando desconectado
            if (flag("heart_slot_loss")) applyHearts(player);
        }
        if (DiscordNotifier.get() != null) {
            DiscordNotifier.get().joinLeave(player.getGameProfile().name(), true);
        }
        state.save(file);
    }

    public void onDisconnect(ServerPlayer player) {
        state.seen(player.getUUID(), now());
        state.save(file);
        if (DiscordNotifier.get() != null) {
            DiscordNotifier.get().joinLeave(player.getGameProfile().name(), false);
        }
    }

    // ---------------------------------------------------------------- tick

    public void onServerTick() {
        if (++tickCounter < 20) return; // ~1 vez por segundo
        tickCounter = 0;

        updateMotd();
        checkPhase();
        runScheduledCmds();
        GaturroScores.tick(server, this);
        if (battle != null) battle.tick();

        GaturroVoice v = voice();
        if (v != null) {
            if (!clipDirReady) {
                v.setClipDir(server.getWorldPath(LevelResource.ROOT).resolve(GaturroVoice.CLIP_DIR));
                clipDirReady = true;
            }
            v.tickClips();
        }

        long now = now();
        ServerLevel overworld = server.overworld();

        // gaturro.exe: mantener la tormenta mientras haya banco (insaltable)
        boolean active = state.stormActive(now);
        if (active) {
            if (!stormWasActive) {
                broadcastTitle(
                        Component.literal("gaturro.exe").withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD),
                        Component.literal("¡Empieza la tormenta!").withStyle(ChatFormatting.RED),
                        10, 50, 15);
                broadcastSound(SoundEvents.LIGHTNING_BOLT_THUNDER, 4.0F, 0.5F);
                if (DiscordNotifier.get() != null) DiscordNotifier.get().stormStart(state.stormSecondsLeft(now), day());
            }
            if (overworld != null) {
                forceStorm(overworld);
                GaturroMobs.tickStormBuffs(overworld, this); // hostiles buffeados durante gaturro.exe
            }
        } else if (stormWasActive) {
            if (overworld != null) clearStorm(overworld);
            broadcast(Component.literal("gaturro.exe se ha calmado... por ahora.")
                    .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
        }
        stormWasActive = active;

        // Actionbar con el tiempo de gaturro.exe + oscuridad a los eliminados (spectator con poca vision)
        Component actionBar = active
                ? Component.literal("gaturro.exe - Quedan " + humanTime(state.stormSecondsLeft(now)) + " de tormenta")
                    .withStyle(ChatFormatting.GRAY)
                : null;
        boolean endOpen = flag("end_open");
        BlockPos owSpawn = overworld != null ? overworld.getRespawnData().pos() : null;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (actionBar != null) {
                p.connection.send(new ClientboundSetActionBarTextPacket(actionBar));
            }
            if (state.isEliminated(p.getUUID())) {
                if (!p.isSpectator()) p.setGameMode(GameType.SPECTATOR); // red de seguridad: espermabaneado SIEMPRE acaba en spectator
                p.addEffect(new MobEffectInstance(MobEffects.DARKNESS, 40, 0, false, false));
            }
            if (!endOpen && overworld != null && owSpawn != null && p.level().dimension() == Level.END) {
                evictFromEnd(p, overworld, owSpawn);
            }
        }

        // Baneo por AFK: quien no aparece en N dias, eliminado
        checkAfk(now);
    }

    private void checkAfk(long now) {
        long limit = (long) GaturroState.AFK_ELIMINATE_DAYS * GaturroState.SECONDS_PER_DAY;
        for (var entry : state.lastSeen.entrySet()) {
            String sid = entry.getKey();
            if (state.eliminated.contains(sid)) continue;
            UUID id;
            try { id = UUID.fromString(sid); } catch (Exception e) { continue; }
            // Si esta conectado, no cuenta como AFK
            if (server.getPlayerList().getPlayer(id) != null) continue;
            if (now - entry.getValue() > limit) {
                state.eliminate(id);
                broadcast(Component.literal("Un jugador fue ELIMINADO por no conectarse en "
                        + GaturroState.AFK_ELIMINATE_DAYS + " dias.")
                        .withStyle(ChatFormatting.DARK_RED));
                state.save(file);
            }
        }
    }

    private void forceStorm(ServerLevel level) {
        WeatherData wd = level.getWeatherData();
        wd.setClearWeatherTime(0);
        wd.setRaining(true);
        wd.setRainTime(20 * 60);
        wd.setThundering(true);
        wd.setThunderTime(20 * 60);
        wd.setDirty();
    }

    private void clearStorm(ServerLevel level) {
        WeatherData wd = level.getWeatherData();
        wd.setThundering(false);
        wd.setThunderTime(0);
        wd.setRaining(false);
        wd.setRainTime(0);
        wd.setClearWeatherTime(20 * 300);
        wd.setDirty();
    }

    /** MOTD dinamico: nombre + dia actual y dias que faltan. */
    private void updateMotd() {
        int day = day();
        int left = Math.max(0, GaturroState.TOTAL_DAYS - day);
        String motd = "§d§lEspermaDeath §8» §e§lMundo Gaturro\n"
                + "§cDía §f" + day + "§7/§c" + GaturroState.TOTAL_DAYS
                + " §8· §ffaltan §e" + left + " §fdías §8· §4gaturro.exe";
        server.setMotd(motd);
    }

    // ------------------------------------------------------------- fases

    private void checkPhase() {
        int day = day();
        GaturroPhases.Phase cur = phases.current(day);
        if (cur == null) return;
        if (state.lastAnnouncedPhaseDay != cur.day) {
            state.lastAnnouncedPhaseDay = cur.day;
            save();
            applyDifficulty(phases.difficultyFor(day));
            announcePhase(cur, day);
        }
    }

    private void announcePhase(GaturroPhases.Phase ph, int day) {
        broadcastTitle(
                Component.literal("Día " + day + " - " + ph.name).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD),
                Component.literal(ph.announce).withStyle(ChatFormatting.GRAY),
                10, 90, 20);
        broadcast(Component.literal("» " + ph.name + ": " + ph.announce).withStyle(ChatFormatting.GOLD));
        broadcastSound(SoundEvents.WITHER_SPAWN, 1.0F, 1.2F);
        if (DiscordNotifier.get() != null) {
            DiscordNotifier.get().phase(day, ph.name, ph.announce);
            if (ph.flags != null && ph.flags.contains("end_open")) DiscordNotifier.get().endOpen(day);
        }
    }

    private void applyDifficulty(String diff) {
        Difficulty d = switch (diff == null ? "" : diff.toLowerCase()) {
            case "peaceful" -> Difficulty.PEACEFUL;
            case "easy" -> Difficulty.EASY;
            case "hard" -> Difficulty.HARD;
            default -> Difficulty.NORMAL;
        };
        server.setDifficulty(d, true);
    }

    /** Saca del End a quien entre antes de que se abra (dia 7). */
    private void evictFromEnd(ServerPlayer p, ServerLevel overworld, BlockPos spawn) {
        p.teleportTo(overworld, spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5,
                java.util.Set.of(), 0f, 0f, false);
        p.sendSystemMessage(Component.literal("El End está sellado. Se abre el día "
                + phases.endOpensDay + ".").withStyle(ChatFormatting.RED));
    }

    // ---------------------------------------------------------------- util

    public void broadcast(Component msg) {
        server.getPlayerList().broadcastSystemMessage(msg, false);
    }

    /** Ejecuta un comando como consola, sin spamear el chat. */
    private void runCommand(String cmd) {
        try {
            server.getCommands().performPrefixedCommand(
                    server.createCommandSourceStack().withSuppressedOutput(), cmd);
        } catch (Throwable t) {
            PermadeathGaturro.LOGGER.warn("[gaturro] fallo el comando '{}': {}", cmd, t.toString());
        }
    }

    private void runScheduledCmds() {
        if (scheduledCmds.isEmpty()) return;
        long now = now();
        var it = scheduledCmds.iterator();
        while (it.hasNext()) {
            Object[] e = it.next();
            if ((long) e[0] > now) continue;
            it.remove();
            if (e[1] instanceof Runnable r) r.run();
            else runCommand((String) e[1]);
        }
    }

    public static String humanTime(long seconds) {
        long h = seconds / 3600;
        long m = (seconds % 3600) / 60;
        return h + "h " + m + "m";
    }
}
