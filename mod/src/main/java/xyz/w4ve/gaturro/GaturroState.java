package xyz.w4ve.gaturro;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Estado persistente del permadeath. Se serializa a JSON en la carpeta del mundo.
 * Todos los tiempos en segundos epoch (reloj real).
 */
public class GaturroState {
    // Config
    public static final int DEFAULT_LIVES = 3;
    public static final int AFK_ELIMINATE_DAYS = 6;
    public static final int TOTAL_DAYS = 30;
    public static final long SECONDS_PER_DAY = 86400L;

    // Momento en que arranco el evento (para calcular "dia del servidor")
    public long eventStartEpoch = 0L;
    // Ultima fase anunciada (para no repetir el anuncio)
    public int lastAnnouncedPhaseDay = 0;
    // Banco de gaturro.exe: epoch en el que la tormenta termina. Si > now => tormenta activa.
    public long stormEndsAtEpoch = 0L;

    // Vidas por jugador (UUID -> vidas restantes)
    public Map<String, Integer> lives = new HashMap<>();
    // Ultima vez visto (UUID -> epoch)
    public Map<String, Long> lastSeen = new HashMap<>();
    // Jugadores eliminados (fuera del juego -> spectator)
    public Set<String> eliminated = new HashSet<>();
    // Mensaje de muerte personalizado por jugador (UUID -> texto). %player% se sustituye.
    public Map<String, String> deathMessages = new HashMap<>();
    // Corazones perdidos por heart_slot_loss (UUID -> puntos de vida perdidos, 2 = 1 corazon)
    public Map<String, Integer> hpLost = new HashMap<>();

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static GaturroState load(Path file) {
        try {
            if (Files.exists(file)) {
                GaturroState s = GSON.fromJson(Files.readString(file), GaturroState.class);
                if (s != null) {
                    if (s.lives == null) s.lives = new HashMap<>();
                    if (s.lastSeen == null) s.lastSeen = new HashMap<>();
                    if (s.eliminated == null) s.eliminated = new HashSet<>();
                    if (s.deathMessages == null) s.deathMessages = new HashMap<>();
                    if (s.hpLost == null) s.hpLost = new HashMap<>();
                    return s;
                }
            }
        } catch (Exception e) {
            PermadeathGaturro.LOGGER.error("[gaturro] No pude cargar el estado, empiezo limpio", e);
        }
        return new GaturroState();
    }

    public void save(Path file) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(this));
        } catch (IOException e) {
            PermadeathGaturro.LOGGER.error("[gaturro] No pude guardar el estado", e);
        }
    }

    // --- helpers ---
    public int getLives(UUID id) {
        return lives.getOrDefault(id.toString(), DEFAULT_LIVES);
    }

    public void setLives(UUID id, int n) {
        lives.put(id.toString(), Math.max(0, n));
    }

    public boolean isEliminated(UUID id) {
        return eliminated.contains(id.toString());
    }

    public void eliminate(UUID id) {
        eliminated.add(id.toString());
        lives.put(id.toString(), 0);
    }

    public void revive(UUID id, int n) {
        eliminated.remove(id.toString());
        lives.put(id.toString(), Math.max(1, n));
    }

    public void seen(UUID id, long epoch) {
        lastSeen.put(id.toString(), epoch);
    }

    /** Dia del servidor, empezando en 1 el primer dia. */
    public int dayOfServer(long now) {
        if (eventStartEpoch <= 0) return 1;
        return (int) ((now - eventStartEpoch) / SECONDS_PER_DAY) + 1;
    }

    /** Suma horas al banco de tormenta (drena en tiempo real). */
    public void addStormHours(long now, int hours) {
        long base = Math.max(now, stormEndsAtEpoch);
        stormEndsAtEpoch = base + (long) hours * 3600L;
    }

    public boolean stormActive(long now) {
        return stormEndsAtEpoch > now;
    }

    public long stormSecondsLeft(long now) {
        return Math.max(0, stormEndsAtEpoch - now);
    }
}
