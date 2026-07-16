package xyz.w4ve.gaturro;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Emisor de eventos a Discord. NO habla con Discord directamente: hace un POST
 * a un endpoint HTTP (el bot del "sotano de virus"), que ya formatea los anuncios
 * bonitos y avisa a la gente. Todo async, nunca bloquea el hilo del server.
 *
 * Config: gaturro_discord.json en la carpeta del mundo.
 *   { "enabled": false, "endpoint": "http://127.0.0.1:8099/gaturro", "secret": "cambiame" }
 */
public class DiscordNotifier {

    public boolean enabled = false;
    public String endpoint = "http://127.0.0.1:8099/gaturro";
    public String secret = "cambiame";

    private static DiscordNotifier instance;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private final transient HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3)).build();
    private final transient ExecutorService pool = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "gaturro-discord");
        t.setDaemon(true);
        return t;
    });

    public static void init(Path file) {
        DiscordNotifier n = null;
        try {
            if (Files.exists(file)) {
                n = GSON.fromJson(Files.readString(file), DiscordNotifier.class);
            }
        } catch (Exception e) {
            PermadeathGaturro.LOGGER.error("[gaturro] gaturro_discord.json invalido", e);
        }
        if (n == null) {
            n = new DiscordNotifier();
            try {
                Files.createDirectories(file.getParent());
                Files.writeString(file, GSON.toJson(n));
            } catch (Exception ignored) {}
        }
        instance = n;
        PermadeathGaturro.LOGGER.info("[gaturro] Discord notifier {}", n.enabled ? "activo -> " + n.endpoint : "desactivado");
    }

    public static DiscordNotifier get() {
        return instance;
    }

    private void send(String type, Map<String, Object> data) {
        if (!enabled || endpoint == null || endpoint.isBlank()) return;
        data.put("type", type);
        String body = GSON.toJson(data);
        pool.submit(() -> {
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(endpoint))
                        .timeout(Duration.ofSeconds(4))
                        .header("Content-Type", "application/json")
                        .header("X-Gaturro-Secret", secret == null ? "" : secret)
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();
                http.send(req, HttpResponse.BodyHandlers.discarding());
            } catch (Exception e) {
                PermadeathGaturro.LOGGER.warn("[gaturro] no pude avisar a Discord: {}", e.getMessage());
            }
        });
    }

    private static Map<String, Object> m() {
        return new HashMap<>();
    }

    // --- eventos que el bot convierte en anuncios ---

    public void death(String player, int livesLeft, int day, int stormHoursAdded) {
        Map<String, Object> d = m();
        d.put("player", player); d.put("lives", livesLeft);
        d.put("day", day); d.put("stormHours", stormHoursAdded);
        send("death", d);
    }

    public void eliminated(String player, int day) {
        Map<String, Object> d = m();
        d.put("player", player); d.put("day", day);
        send("eliminated", d);
    }

    public void stormStart(long secondsLeft, int day) {
        Map<String, Object> d = m();
        d.put("secondsLeft", secondsLeft); d.put("day", day);
        send("storm_start", d);
    }

    public void phase(int day, String name, String announce) {
        Map<String, Object> d = m();
        d.put("day", day); d.put("name", name); d.put("announce", announce);
        send("phase", d);
    }

    public void endOpen(int day) {
        Map<String, Object> d = m();
        d.put("day", day);
        send("end_open", d);
    }

    /** La Dragona cayo: cuantos Corazones soltó y donde. */
    public void dragonDown(int day, int hearts, String coords) {
        Map<String, Object> d = m();
        d.put("day", day); d.put("hearts", hearts); d.put("coords", coords);
        send("dragon_down", d);
    }

    /** Alguien se comio un Corazon de Gaturro (+1 vida). */
    public void heartUsed(String player, int livesNow, int day) {
        Map<String, Object> d = m();
        d.put("player", player); d.put("lives", livesNow); d.put("day", day);
        send("heart_used", d);
    }

    /** Un admin revivio a alguien. */
    public void revived(String player, int lives, int day) {
        Map<String, Object> d = m();
        d.put("player", player); d.put("lives", lives); d.put("day", day);
        send("revived", d);
    }

    /** Chat del server -> canal puente de Discord. */
    public void chat(String player, String message) {
        Map<String, Object> d = m();
        d.put("player", player); d.put("message", message);
        send("chat", d);
    }

    /** Entradas/salidas del server -> canal puente. */
    public void joinLeave(String player, boolean joined) {
        Map<String, Object> d = m();
        d.put("player", player); d.put("joined", joined);
        send(joined ? "join" : "leave", d);
    }

    /** Replay de la muerte cortado: el bot busca el .mcpr nuevo del jugador y lo sube. */
    public void deathReplay(String player, int day) {
        Map<String, Object> d = m();
        d.put("player", player); d.put("day", day);
        send("death_replay", d);
    }

    /** Clip de audio del ultimo minuto al morir (ruta del .wav que sube el bot). */
    public void deathClip(String player, String clipPath) {
        Map<String, Object> d = m();
        d.put("player", player); d.put("clip", clipPath);
        send("death_clip", d);
    }
}
