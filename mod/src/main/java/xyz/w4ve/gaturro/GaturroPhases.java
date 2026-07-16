package xyz.w4ve.gaturro;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Calendario del evento, dirigido por config (gaturro_phases.json).
 * Cambiar la dificultad / eventos = editar el JSON, sin tocar codigo.
 *
 * Una "fase" se activa el dia indicado y sus flags quedan ACTIVOS a partir de
 * ahi (acumulativo). La dificultad es la de la ultima fase con dia <= dia actual.
 */
public class GaturroPhases {

    public int totalDays = 30;
    public int endOpensDay = 7;
    public List<Phase> phases = new ArrayList<>();

    public static class Phase {
        public int day;
        public String name = "";
        public String announce = "";
        /** "", "peaceful", "easy", "normal", "hard" */
        public String difficulty = "";
        /** flags de features: end_open, poisoned_food, totem_fail, spider_effect,
         *  all_hostile, gatos_supernova, player_skulls, ... */
        public List<String> flags = new ArrayList<>();

        Phase(int day, String name, String announce, String difficulty, String... flags) {
            this.day = day; this.name = name; this.announce = announce; this.difficulty = difficulty;
            for (String f : flags) this.flags.add(f);
        }
        Phase() {}
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static GaturroPhases loadOrCreate(Path file) {
        try {
            if (Files.exists(file)) {
                GaturroPhases p = GSON.fromJson(Files.readString(file), GaturroPhases.class);
                if (p != null && p.phases != null && !p.phases.isEmpty()) return p;
            }
        } catch (Exception e) {
            PermadeathGaturro.LOGGER.error("[gaturro] gaturro_phases.json invalido, uso el default", e);
        }
        GaturroPhases def = createDefault();
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(def));
        } catch (Exception e) {
            PermadeathGaturro.LOGGER.error("[gaturro] no pude escribir gaturro_phases.json", e);
        }
        return def;
    }

    /**
     * Calendario por defecto: 30 dias, mas suave que el Permadeath original
     * (dura la mitad y la gente no es muy buena). Todo editable en el JSON.
     */
    static GaturroPhases createDefault() {
        GaturroPhases p = new GaturroPhases();
        p.totalDays = 30;
        p.endOpensDay = 7;
        // Calendario curado con el usuario. Dificultad SIEMPRE hard. Tono tico y con guasa.
        p.phases.add(new Phase(1, "Semivanilla",
                "Arranca EspermaDeath. 3 vidas. Disfruten mientras dure.", "hard"));
        p.phases.add(new Phase(3, "Tormenta con dientes",
                "gaturro.exe ya no es solo lluvia: los bichos se ponen valientes durante la tormenta.",
                "hard", "storm_buff_mobs"));
        p.phases.add(new Phase(5, "Plaga",
                "El doble de bichos. Y las aranas... digamos que fueron al gimnasio.",
                "hard", "double_mobs", "spider_effect"));
        p.phases.add(new Phase(7, "El End despierta",
                "El End se abrio. Algo los espera adentro. Suerte con eso.",
                "hard", "end_open", "end_battle"));
        p.phases.add(new Phase(9, "Cada quien a lo suyo",
                "Se acabo la ayudadera: solo su equipo. Y necesitan 3 para dormir, dormilones.",
                "hard", "no_cross_team_help", "min_sleep_3"));
        p.phases.add(new Phase(12, "Sin refugio",
                "Los bichos ya no le temen al sol. Ni de dia estan tranquilos.",
                "hard", "all_hostile"));
        p.phases.add(new Phase(15, "Totems traicioneros",
                "Sus totems ahora pueden fallar. Confien, confien.",
                "hard", "totem_fail"));
        p.phases.add(new Phase(18, "Desgaste",
                "El mundo los desgasta: pierden corazones y espacio. A farmear bichos para recuperarlo.",
                "hard", "heart_slot_loss", "super_golden_apple"));
        p.phases.add(new Phase(21, "Aracnofobia total",
                "Las aranas ahora traen un buffet de efectos encima. Buen provecho.",
                "hard", "spider_effect_high"));
        p.phases.add(new Phase(24, "Deslealtad",
                "Los totems fallan el doble. Ya ni en eso pueden confiar.",
                "hard", "totem_fail_high"));
        p.phases.add(new Phase(27, "Los cielos caen",
                "Phantoms gigantes. Y los MUERTOS ahora eligen el castigo. Gracias, muertos.",
                "hard", "giant_phantoms", "dead_vote"));
        p.phases.add(new Phase(30, "El ultimo dia",
                "Ultimo dia. Sobrevivan o mueran con estilo.", "hard"));
        return p;
    }

    /** Fase activa (la ultima cuyo dia <= dia actual). */
    public Phase current(int day) {
        Phase cur = null;
        for (Phase ph : phases) if (ph.day <= day && (cur == null || ph.day >= cur.day)) cur = ph;
        return cur;
    }

    /** Dificultad vigente (ultima fase con dia<=day que la define). */
    public String difficultyFor(int day) {
        String diff = "normal";
        for (Phase ph : phases) if (ph.day <= day && ph.difficulty != null && !ph.difficulty.isEmpty()) diff = ph.difficulty;
        return diff;
    }

    /** ¿El flag esta activo hoy? (alguna fase con dia<=day lo tiene). */
    public boolean flagActive(String flag, int day) {
        for (Phase ph : phases) if (ph.day <= day && ph.flags != null && ph.flags.contains(flag)) return true;
        return false;
    }
}
