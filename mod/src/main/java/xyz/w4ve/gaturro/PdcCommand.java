package xyz.w4ve.gaturro;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.level.GameType;

import java.util.Collection;
import java.util.UUID;

/** Comando /pdc: administra el permadeath del Mundo Gaturro. */
public final class PdcCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("pdc")
            .then(Commands.literal("status").executes(PdcCommand::status))
            .then(Commands.literal("storm")
                .executes(PdcCommand::stormView)
                .then(Commands.literal("add").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                    .then(Commands.argument("hours", IntegerArgumentType.integer(1))
                        .executes(PdcCommand::stormAdd)))
                .then(Commands.literal("clear").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                    .executes(PdcCommand::stormClear)))
            .then(Commands.literal("lives").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.argument("target", GameProfileArgument.gameProfile())
                    .executes(PdcCommand::livesView)
                    .then(Commands.argument("amount", IntegerArgumentType.integer(0))
                        .executes(PdcCommand::livesSet))))
            .then(Commands.literal("revive").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.argument("target", GameProfileArgument.gameProfile())
                    .executes(ctx -> revive(ctx, GaturroState.DEFAULT_LIVES))
                    .then(Commands.argument("amount", IntegerArgumentType.integer(1))
                        .executes(ctx -> revive(ctx, IntegerArgumentType.getInteger(ctx, "amount"))))))
            .then(Commands.literal("afk").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .executes(PdcCommand::afkList))
            .then(Commands.literal("phase").executes(PdcCommand::phaseInfo))
            .then(Commands.literal("day").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.argument("n", IntegerArgumentType.integer(1))
                    .executes(PdcCommand::setDay)))
            // Un solo nodo: "all" se resuelve DENTRO del executor. Con un literal "all"
            // + un argument hermano, Brigadier se comia el literal y buscaba un jugador
            // llamado "all" en la API de Mojang (404).
            .then(Commands.literal("reset").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .executes(PdcCommand::resetSelf)
                .then(Commands.argument("target", StringArgumentType.word())
                    .suggests(PdcCommand::suggestTargets)
                    .executes(PdcCommand::resetByName)))
            .then(Commands.literal("voice").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .executes(PdcCommand::voiceInfo))
            .then(Commands.literal("dragon").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .executes(PdcCommand::dragonInfo)
                .then(Commands.literal("heart").executes(PdcCommand::giveHeart))
                .then(Commands.literal("apple").executes(PdcCommand::giveApple)))
            .then(Commands.literal("deathmsg").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.argument("target", GameProfileArgument.gameProfile())
                    .then(Commands.argument("mensaje", StringArgumentType.greedyString())
                        .executes(PdcCommand::setDeathMsg)))));
    }

    private static GaturroManager mgr(CommandSourceStack src) {
        GaturroManager m = GaturroManager.get();
        if (m == null) {
            src.sendFailure(Component.literal("El permadeath aun no esta listo."));
        }
        return m;
    }

    private static long now() {
        return System.currentTimeMillis() / 1000L;
    }

    private static int status(CommandContext<CommandSourceStack> ctx) {
        GaturroManager m = mgr(ctx.getSource());
        if (m == null) return 0;
        long left = m.state().stormSecondsLeft(now());
        String storm = left > 0 ? "gaturro.exe activa (" + GaturroManager.humanTime(left) + " restantes)" : "cielo despejado";
        ctx.getSource().sendSuccess(() -> Component.literal("Dia " + m.day() + " | " + storm)
                .withStyle(ChatFormatting.GOLD), false);
        ServerPlayer p = ctx.getSource().getPlayer();
        if (p != null) {
            int lives = m.state().getLives(p.getUUID());
            ctx.getSource().sendSuccess(() -> Component.literal("Te quedan " + lives + " vidas.")
                    .withStyle(ChatFormatting.YELLOW), false);
        }
        return 1;
    }

    private static int stormView(CommandContext<CommandSourceStack> ctx) {
        GaturroManager m = mgr(ctx.getSource());
        if (m == null) return 0;
        long left = m.state().stormSecondsLeft(now());
        ctx.getSource().sendSuccess(() -> Component.literal("Banco de gaturro.exe: "
                + GaturroManager.humanTime(left)).withStyle(ChatFormatting.AQUA), false);
        return 1;
    }

    private static int stormAdd(CommandContext<CommandSourceStack> ctx) {
        GaturroManager m = mgr(ctx.getSource());
        if (m == null) return 0;
        int hours = IntegerArgumentType.getInteger(ctx, "hours");
        m.state().addStormHours(now(), hours);
        m.save();
        ctx.getSource().sendSuccess(() -> Component.literal("gaturro.exe +" + hours + "h.")
                .withStyle(ChatFormatting.DARK_RED), true);
        return 1;
    }

    private static int stormClear(CommandContext<CommandSourceStack> ctx) {
        GaturroManager m = mgr(ctx.getSource());
        if (m == null) return 0;
        m.state().stormEndsAtEpoch = 0;
        m.save();
        ctx.getSource().sendSuccess(() -> Component.literal("gaturro.exe cancelada.")
                .withStyle(ChatFormatting.GREEN), true);
        return 1;
    }

    private static int livesView(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        GaturroManager m = mgr(ctx.getSource());
        if (m == null) return 0;
        NameAndId gp = one(ctx);
        int lives = m.state().getLives(gp.id());
        boolean out = m.state().isEliminated(gp.id());
        ctx.getSource().sendSuccess(() -> Component.literal(gp.name() + ": " + lives + " vidas"
                + (out ? " (ELIMINADO)" : "")).withStyle(ChatFormatting.YELLOW), false);
        return 1;
    }

    private static int livesSet(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        GaturroManager m = mgr(ctx.getSource());
        if (m == null) return 0;
        NameAndId gp = one(ctx);
        int amount = IntegerArgumentType.getInteger(ctx, "amount");
        m.state().setLives(gp.id(), amount);
        m.save();
        ctx.getSource().sendSuccess(() -> Component.literal(gp.name() + " ahora tiene " + amount + " vidas.")
                .withStyle(ChatFormatting.GREEN), true);
        return 1;
    }

    private static int revive(CommandContext<CommandSourceStack> ctx, int lives) throws CommandSyntaxException {
        GaturroManager m = mgr(ctx.getSource());
        if (m == null) return 0;
        NameAndId gp = one(ctx);
        m.state().revive(gp.id(), lives);
        m.save();
        m.announceRevive(gp.name(), lives);
        ServerPlayer online = ctx.getSource().getServer().getPlayerList().getPlayer(gp.id());
        if (online != null) online.setGameMode(GameType.SURVIVAL);
        ctx.getSource().sendSuccess(() -> Component.literal(gp.name() + " revivido con " + lives + " vidas.")
                .withStyle(ChatFormatting.GREEN), true);
        return 1;
    }

    private static int afkList(CommandContext<CommandSourceStack> ctx) {
        GaturroManager m = mgr(ctx.getSource());
        if (m == null) return 0;
        long now = now();
        long limit = (long) GaturroState.AFK_ELIMINATE_DAYS * GaturroState.SECONDS_PER_DAY;
        StringBuilder sb = new StringBuilder("En riesgo de AFK (limite "
                + GaturroState.AFK_ELIMINATE_DAYS + "d): ");
        boolean any = false;
        for (var e : m.state().lastSeen.entrySet()) {
            if (m.state().eliminated.contains(e.getKey())) continue;
            long idle = now - e.getValue();
            if (idle > limit / 2) {
                any = true;
                sb.append(e.getKey(), 0, 8).append("(").append(idle / GaturroState.SECONDS_PER_DAY).append("d) ");
            }
        }
        String out = any ? sb.toString() : "Nadie en riesgo de AFK.";
        ctx.getSource().sendSuccess(() -> Component.literal(out).withStyle(ChatFormatting.AQUA), false);
        return 1;
    }

    private static int resetSelf(CommandContext<CommandSourceStack> ctx) {
        GaturroManager m = mgr(ctx.getSource());
        if (m == null) return 0;
        ServerPlayer p = ctx.getSource().getPlayer();
        if (p == null) {
            ctx.getSource().sendFailure(Component.literal("Desde consola usa /pdc reset <jugador> o /pdc reset all."));
            return 0;
        }
        m.resetPlayer(p.getUUID());
        ctx.getSource().sendSuccess(() -> Component.literal("Tus stats reiniciadas: 3 vidas, survival.")
                .withStyle(ChatFormatting.GREEN), false);
        return 1;
    }

    /** Sugiere "all" y los jugadores conectados. */
    private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestTargets(
            CommandContext<CommandSourceStack> ctx, com.mojang.brigadier.suggestion.SuggestionsBuilder b) {
        b.suggest("all");
        for (ServerPlayer p : ctx.getSource().getServer().getPlayerList().getPlayers()) {
            b.suggest(p.getGameProfile().name());
        }
        return b.buildFuture();
    }

    /** /pdc reset <all|jugador>: "all" reinicia el evento entero. */
    private static int resetByName(CommandContext<CommandSourceStack> ctx) {
        GaturroManager m = mgr(ctx.getSource());
        if (m == null) return 0;
        String target = StringArgumentType.getString(ctx, "target");

        if (target.equalsIgnoreCase("all")) {
            return resetAll(ctx);
        }
        // Solo jugadores conectados: resolver a alguien offline exigiria consultar
        // a Mojang (lento y revienta si el nombre no existe).
        ServerPlayer p = ctx.getSource().getServer().getPlayerList().getPlayerByName(target);
        if (p == null) {
            ctx.getSource().sendFailure(Component.literal("'" + target + "' no esta conectado. "
                    + "Usa /pdc reset all, o /pdc lives <jugador> 3 si esta offline."));
            return 0;
        }
        m.resetPlayer(p.getUUID());
        ctx.getSource().sendSuccess(() -> Component.literal(p.getGameProfile().name()
                + " reiniciado: 3 vidas, survival.").withStyle(ChatFormatting.GREEN), true);
        return 1;
    }

    private static int resetAll(CommandContext<CommandSourceStack> ctx) {
        GaturroManager m = mgr(ctx.getSource());
        if (m == null) return 0;
        m.resetAll();
        ctx.getSource().sendSuccess(() -> Component.literal("EspermaDeath reiniciado: Dia 1, sin tormenta, todos con 3 vidas.")
                .withStyle(ChatFormatting.GREEN), true);
        return 1;
    }

    /** Diagnostico de la voz: habla 10s y mira si los paquetes de tu micro suben. */
    private static int voiceInfo(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer p = ctx.getSource().getPlayerOrException();
        String info;
        try {
            GaturroVoice v = GaturroVoice.get();
            info = v == null ? "GaturroVoice NO cargado (¿falta Simple Voice Chat?)" : v.debug(p.getUUID());
        } catch (Throwable t) {
            info = "Simple Voice Chat no esta: " + t;
        }
        final String out = info;
        ctx.getSource().sendSuccess(() -> Component.literal(out).withStyle(ChatFormatting.AQUA), false);
        return 1;
    }

    /** Estado de la Dragona: si el flag esta activo y como va la pelea. */
    private static int dragonInfo(CommandContext<CommandSourceStack> ctx) {
        GaturroManager m = mgr(ctx.getSource());
        if (m == null) return 0;
        boolean on = m.flag("end_battle");
        var end = ctx.getSource().getServer().getLevel(net.minecraft.world.level.Level.END);
        var dragons = end == null ? java.util.List.<EnderDragon>of() : end.getDragons();
        String hp = dragons.isEmpty() ? "no hay dragona cargada"
                : (int) dragons.get(0).getHealth() + "/" + (int) dragons.get(0).getMaxHealth() + " HP";
        ctx.getSource().sendSuccess(() -> Component.literal("end_battle: " + (on ? "ACTIVO" : "inactivo")
                + " · " + hp).withStyle(on ? ChatFormatting.LIGHT_PURPLE : ChatFormatting.GRAY), false);
        return 1;
    }

    /** Te da un Corazon de Gaturro (para probar el canje sin matar a la dragona). */
    private static int giveHeart(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer p = ctx.getSource().getPlayerOrException();
        p.getInventory().placeItemBackInInventory(DragonBattle.heart());
        ctx.getSource().sendSuccess(() -> Component.literal("Toma tu Corazón de Gaturro.")
                .withStyle(ChatFormatting.RED), false);
        return 1;
    }

    /** Manzana de Gaturro de prueba (recupera un corazon perdido). */
    private static int giveApple(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer p = ctx.getSource().getPlayerOrException();
        p.getInventory().placeItemBackInInventory(GaturroMobs.superApple());
        ctx.getSource().sendSuccess(() -> Component.literal("Manzana de Gaturro entregada.")
                .withStyle(ChatFormatting.GOLD), false);
        return 1;
    }

    private static int phaseInfo(CommandContext<CommandSourceStack> ctx) {
        GaturroManager m = mgr(ctx.getSource());
        if (m == null) return 0;
        int day = m.day();
        GaturroPhases ph = m.phases();
        GaturroPhases.Phase cur = ph.current(day);
        String name = cur != null ? cur.name : "?";
        StringBuilder flags = new StringBuilder();
        for (String f : new String[]{"storm_buff_mobs", "double_mobs", "spider_effect", "end_open",
                "end_battle", "no_cross_team_help", "min_sleep_3", "all_hostile", "totem_fail",
                "heart_slot_loss", "super_golden_apple", "spider_effect_high", "totem_fail_high",
                "giant_phantoms", "dead_vote"}) {
            if (ph.flagActive(f, day)) flags.append(f).append(" ");
        }
        ctx.getSource().sendSuccess(() -> Component.literal("Día " + day + "/" + ph.totalDays
                + " · Fase: " + name + " · Dificultad: " + ph.difficultyFor(day))
                .withStyle(ChatFormatting.GOLD), false);
        String fl = flags.length() == 0 ? "(ninguno)" : flags.toString().trim();
        ctx.getSource().sendSuccess(() -> Component.literal("Flags activos: " + fl)
                .withStyle(ChatFormatting.GRAY), false);
        return 1;
    }

    private static int setDay(CommandContext<CommandSourceStack> ctx) {
        GaturroManager m = mgr(ctx.getSource());
        if (m == null) return 0;
        int n = IntegerArgumentType.getInteger(ctx, "n");
        m.setDay(n);
        ctx.getSource().sendSuccess(() -> Component.literal("Evento saltado al día " + n + ".")
                .withStyle(ChatFormatting.GREEN), true);
        return 1;
    }

    private static int setDeathMsg(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        GaturroManager m = mgr(ctx.getSource());
        if (m == null) return 0;
        NameAndId gp = one(ctx);
        String msg = StringArgumentType.getString(ctx, "mensaje");
        m.state().deathMessages.put(gp.id().toString(), msg);
        m.save();
        ctx.getSource().sendSuccess(() -> Component.literal("Mensaje de muerte de " + gp.name() + " puesto.")
                .withStyle(ChatFormatting.GREEN), true);
        return 1;
    }

    private static NameAndId one(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Collection<NameAndId> profiles = GameProfileArgument.getGameProfiles(ctx, "target");
        return profiles.iterator().next();
    }

    private PdcCommand() {}
}
