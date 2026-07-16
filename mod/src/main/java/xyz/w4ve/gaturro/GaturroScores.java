package xyz.w4ve.gaturro;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.numbers.FixedFormat;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.ScoreAccess;
import net.minecraft.world.scores.ScoreHolder;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;

/**
 * Marcadores del evento, mantenidos por el MOD (nada de RCON, cero spam):
 *  - "vidas" en el TAB, disfrazado de corazones (☠ para los espermabaneados).
 *  - "salud" bajo el nombre (criterio health, vanilla se encarga).
 * Sobrevive a resets de mundo: se recrea todo al arrancar.
 */
public final class GaturroScores {

    private GaturroScores() {}

    public static void ensureObjectives(MinecraftServer server) {
        var sb = server.getScoreboard();
        Objective vidas = sb.getObjective("vidas");
        if (vidas == null) {
            vidas = sb.addObjective("vidas", ObjectiveCriteria.DUMMY,
                    Component.literal("Vidas").withStyle(ChatFormatting.GOLD),
                    ObjectiveCriteria.RenderType.INTEGER, true, null);
        }
        sb.setDisplayObjective(DisplaySlot.LIST, vidas);

        Objective salud = sb.getObjective("salud");
        if (salud == null) {
            salud = sb.addObjective("salud", ObjectiveCriteria.HEALTH,
                    Component.literal("❤").withStyle(ChatFormatting.RED),
                    ObjectiveCriteria.RenderType.INTEGER, false, null);
        }
        sb.setDisplayObjective(DisplaySlot.BELOW_NAME, salud);
    }

    /** Refresca las vidas de los conectados (el tab solo muestra online). */
    public static void tick(MinecraftServer server, GaturroManager m) {
        Objective obj = server.getScoreboard().getObjective("vidas");
        if (obj == null) return;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            String name = p.getGameProfile().name();
            boolean out = m.state().isEliminated(p.getUUID());
            int n = out ? 0 : m.state().getLives(p.getUUID());
            ScoreAccess sa = server.getScoreboard()
                    .getOrCreatePlayerScore(ScoreHolder.forNameOnly(name), obj);
            if (sa.get() != n) {
                sa.set(n);
                String icon = n <= 0 ? "☠" : "❤".repeat(n);
                sa.numberFormatOverride(new FixedFormat(Component.literal(icon)
                        .withStyle(n <= 0 ? ChatFormatting.DARK_GRAY : ChatFormatting.RED)));
            }
        }
    }
}
