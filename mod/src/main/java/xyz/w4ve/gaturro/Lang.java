package xyz.w4ve.gaturro;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Textos del mod, editables por el admin.
 *
 * Al primer arranque se crea <world>/permadeath_lang.json con los textos por
 * defecto del idioma elegido en "lang" ("es" o "en"). Todo lo que ven los
 * jugadores sale de ahi: cambia el archivo, reinicia, y el evento habla como
 * tu quieras (nombres de la tormenta, del evento, mensajes, todo).
 */
public final class Lang {

    private static Map<String, String> texts = new LinkedHashMap<>();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private Lang() {}

    /** Texto plano por clave. */
    public static String get(String key) {
        return texts.getOrDefault(key, key);
    }

    /** Texto con parametros (String.format). */
    public static String f(String key, Object... args) {
        try {
            return String.format(get(key), args);
        } catch (Exception e) {
            return get(key);
        }
    }

    public static void load(Path file) {
        Map<String, String> defaults = defaults("es");
        try {
            if (Files.exists(file)) {
                Map<String, String> user = GSON.fromJson(Files.readString(file),
                        new TypeToken<Map<String, String>>(){}.getType());
                if (user != null) {
                    String lang = user.getOrDefault("lang", "es");
                    defaults = defaults(lang);
                    defaults.putAll(user); // lo del admin manda
                    texts = defaults;
                    // completar claves nuevas que falten tras actualizar el mod
                    if (user.size() < texts.size()) {
                        Files.writeString(file, GSON.toJson(texts));
                    }
                    return;
                }
            }
        } catch (Exception e) {
            PermadeathGaturro.LOGGER.error("[permadeath] permadeath_lang.json invalido, uso defaults", e);
        }
        texts = defaults;
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(texts));
        } catch (Exception ignored) {}
    }

    static Map<String, String> defaults(String lang) {
        Map<String, String> m = new LinkedHashMap<>();
        boolean en = "en".equalsIgnoreCase(lang);
        m.put("lang", en ? "en" : "es");

        // Identidad del evento
        m.put("event.name", en ? "PermaDeath" : "PermaDeath");
        m.put("storm.name", en ? "The Storm" : "La Tormenta");
        m.put("eliminated.word", en ? "PERMABANNED" : "PERMABANEADO");

        // Muerte
        m.put("death.default", en ? "%s died" : "%s ha muerto");
        m.put("death.storm_added", en ? "+%dh of storm" : "+%dh de tormenta");
        m.put("death.lives_left", en ? "%s - %d lives left" : "%s - le quedan %d vidas");
        m.put("eliminated.chat", en
                ? "This is the beginning of %s's eternal suffering. They have been %s!"
                : "Este es el comienzo del sufrimiento eterno de %s. ¡HA SIDO %s!");
        m.put("eliminated.subtitle", en ? "%s has been %s" : "%s ha sido %s");

        // Tormenta
        m.put("storm.start.title", en ? "%s begins!" : "¡Empieza %s!");
        m.put("storm.calm", en ? "%s has calmed down... for now." : "%s se ha calmado... por ahora.");
        m.put("storm.actionbar", en ? "%s - %s of storm remaining" : "%s - Quedan %s de tormenta");

        // Conexion / estado
        m.put("join.eliminated", en ? "You are eliminated. Spectator only." : "Estas eliminado. Solo espectador.");
        m.put("join.lives", en ? "Day %d - You have %d lives left." : "Dia %d - Te quedan %d vidas.");
        m.put("end.sealed", en ? "The End is sealed. It opens on day %d." : "El End esta sellado. Se abre el dia %d.");
        m.put("phase.day", en ? "Day %d - %s" : "Dia %d - %s");

        // Revive / totem / corazones
        m.put("revive.title", en ? "✟ %s IS BACK" : "✟ %s HA VUELTO");
        m.put("revive.subtitle", en ? "The gods gave them %d life(s)" : "Los dioses le dieron %d vida(s)");
        m.put("totem.failed", en ? "%s's totem FAILED. " : "El totem de %s FALLO. ");
        m.put("totem.taunt", en ? "We told you not to trust them." : "Les dijimos que no confiaran.");
        m.put("heart.lost", en
                ? "You lost a heart. %d left. Golden Hearts bring them back."
                : "Perdiste un corazon. Te quedan %d. Las Manzanas los devuelven.");
        m.put("apple.name", en ? "Golden Heart Apple" : "Manzana Dorada");
        m.put("apple.lore", en ? "Right click: +1 lost heart" : "Click derecho: +1 corazon perdido");
        m.put("apple.full", en
                ? "You are not missing any hearts. Save it for when it hurts."
                : "No te falta ningun corazon. Guardala para cuando duela.");
        m.put("apple.used", en ? "You recovered a heart. Now you have %d." : "Recuperaste un corazon. Ahora tienes %d.");

        // Dragona
        m.put("dragon.phase2", en
                ? "The Dragon starts spitting cats. Yes, cats."
                : "La Dragona empieza a escupir gatos. Si, gatos.");
        m.put("dragon.phase3", en
                ? "Something took over the Dragon. Run."
                : "Algo se metio en la Dragona. Corran.");
        m.put("dragon.down.title", en ? "The Dragon has fallen" : "La Dragona cayo");
        m.put("dragon.down.subtitle", en
                ? "%d Hearts fell somewhere on the island"
                : "%d Corazones cayeron en la isla");
        m.put("dragon.down.chat", en
                ? "The Dragon has fallen. %d Hearts dropped with lightning at: "
                : "La Dragona cayo. %d Corazones cayeron con un rayo en: ");
        m.put("dragon.down.hint", en
                ? "There are only %d. Run. Right click = +1 life."
                : "Solo hay %d. Corran. Click derecho = +1 vida.");
        m.put("heart.name", en ? "Dragon Heart" : "Corazon de Dragona");
        m.put("heart.lore1", en ? "Right click: +1 life" : "Click derecho: +1 vida");
        m.put("heart.lore2", en ? "Torn from the Dragon herself." : "Se lo arrancaron a la Dragona.");
        m.put("heart.used", en ? "%s ate a Dragon Heart. " : "%s se comio un Corazon de Dragona. ");
        m.put("heart.lives_now", en ? "Now they have %d lives." : "Ahora tiene %d vidas.");

        return m;
    }
}
