package xyz.w4ve.espermarender;

import com.google.gson.Gson;
import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.combo_options.AudioCodec;
import com.moulberry.flashback.combo_options.ExportProjection;
import com.moulberry.flashback.combo_options.TrackingBodyPart;
import com.moulberry.flashback.combo_options.VideoCodec;
import com.moulberry.flashback.combo_options.VideoContainer;
import com.moulberry.flashback.exporting.ExportJob;
import com.moulberry.flashback.exporting.ExportSettings;
import com.moulberry.flashback.keyframe.impl.TrackEntityKeyframe;
import com.moulberry.flashback.keyframe.types.TrackEntityKeyframeType;
import com.moulberry.flashback.playback.ReplayServer;
import com.moulberry.flashback.state.EditorScene;
import com.moulberry.flashback.state.EditorState;
import com.moulberry.flashback.state.EditorStateManager;
import com.moulberry.flashback.state.KeyframeTrack;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * EspermaRender: renderizador automatico de muertes de EspermaDeath.
 *
 * Arranca Minecraft con un job pendiente, abre el replay de Flashback, planta
 * una camara que sigue al muerto (POV: pegada a su cabeza), exporta a MP4 y
 * cierra el juego. Sin humanos. El worker (gaturro-render-worker) se encarga
 * de bajar el replay, lanzar esto y subir el resultado al sotano.
 *
 * Job en JSON (ruta = -Despermarender.job=... o ~/.local/share/espermarender/job.json):
 *   { "replay": "/abs/replay.zip", "output": "/abs/out.mp4", "follow": "<uuid del muerto>",
 *     "seconds": 62, "width": 1280, "height": 720, "fps": 30, "bitrate": 4000000 }
 */
public class EspermaRender implements ClientModInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger("espermarender");
    private static final Gson GSON = new Gson();

    static class Job {
        String replay;
        String output;
        String follow;
        int seconds = 62;
        int width = 1280;
        int height = 720;
        int fps = 30;
        int bitrate = 4_000_000;
    }

    private enum Phase { IDLE, WAIT_MENU, WAIT_REPLAY, WAIT_EXPORT, DONE }

    private Job job;
    private Phase phase = Phase.IDLE;
    private int settleTicks = 0;
    private boolean exportStarted = false;

    @Override
    public void onInitializeClient() {
        Path jobPath = resolveJobPath();
        if (jobPath == null || !Files.exists(jobPath)) {
            LOGGER.info("[espermarender] sin job pendiente, modo pasivo.");
            return;
        }
        try {
            job = GSON.fromJson(Files.readString(jobPath), Job.class);
        } catch (Exception e) {
            LOGGER.error("[espermarender] job.json invalido", e);
            return;
        }
        LOGGER.info("[espermarender] job cargado: replay={} follow={} -> {}", job.replay, job.follow, job.output);
        phase = Phase.WAIT_MENU;

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            try {
                tick(mc);
            } catch (Throwable t) {
                LOGGER.error("[espermarender] fallo el automata, cerrando", t);
                fail(mc);
            }
        });
    }

    private static Path resolveJobPath() {
        String prop = System.getProperty("espermarender.job");
        if (prop != null) return Path.of(prop);
        String home = System.getProperty("user.home");
        return Path.of(home, ".local", "share", "espermarender", "job.json");
    }

    private void tick(Minecraft mc) {
        switch (phase) {
            case WAIT_MENU -> {
                // Menu principal listo: abrir el replay
                if (mc.screen instanceof TitleScreen) {
                    if (++settleTicks < 40) return; // dejar respirar al menu
                    LOGGER.info("[espermarender] abriendo replay...");
                    Flashback.openReplayWorld(Path.of(job.replay));
                    settleTicks = 0;
                    phase = Phase.WAIT_REPLAY;
                }
            }
            case WAIT_REPLAY -> {
                if (!Flashback.isInReplay()) return;
                ReplayServer rs = Flashback.getReplayServer();
                if (rs == null || rs.getTotalReplayTicks() <= 0 || mc.level == null) return;
                if (++settleTicks < 100) return; // ~5s para que cargue mundo/chunks
                startExport(rs);
                phase = Phase.WAIT_EXPORT;
            }
            case WAIT_EXPORT -> {
                if (!exportStarted) return;
                if (Flashback.EXPORT_JOB == null) {
                    boolean ok = Files.exists(Path.of(job.output));
                    LOGGER.info("[espermarender] export terminado, output {}", ok ? "OK" : "AUSENTE");
                    writeMarker(ok ? "done" : "failed");
                    phase = Phase.DONE;
                    mc.stop();
                }
            }
            default -> { }
        }
    }

    private void startExport(ReplayServer rs) {
        EditorState editorState = EditorStateManager.getCurrent();
        if (editorState == null) throw new IllegalStateException("sin EditorState");

        int total = rs.getTotalReplayTicks();
        int end = Math.max(1, total - 5);
        int start = Math.max(0, end - job.seconds * 20);

        // Camara POV: pegada a la cabeza del muerto todo el clip
        long stamp = editorState.acquireWrite();
        try {
            EditorScene scene = editorState.getCurrentScene(stamp);
            KeyframeTrack track = new KeyframeTrack(TrackEntityKeyframeType.INSTANCE);
            track.keyframesByTick.put(start, new TrackEntityKeyframe(
                    UUID.fromString(job.follow), TrackingBodyPart.HEAD,
                    0f, 0f, new Vector3d(0, 0, 0), new Vector3d(0, 0, 0), 0f));
            scene.keyframeTracks.add(track);
        } finally {
            editorState.release(stamp);
        }

        ExportSettings settings = new ExportSettings(
                "espermadeath", editorState,
                Vec3.ZERO, 0f, 0f,
                job.width, job.height, start, end,
                ExportProjection.PERSPECTIVE, 0f,
                job.fps, false,
                VideoContainer.MP4, VideoCodec.H264, null, job.bitrate, false, false, false,
                true, true, AudioCodec.AAC,
                Path.of(job.output), null);

        LOGGER.info("[espermarender] exportando ticks {}-{} ({}x{}@{})",
                start, end, job.width, job.height, job.fps);
        Flashback.EXPORT_JOB = new ExportJob(settings);
        exportStarted = true;
    }

    private void writeMarker(String status) {
        try {
            Files.writeString(Path.of(job.output + ".status"), status);
        } catch (Exception ignored) {}
    }

    private void fail(Minecraft mc) {
        writeMarker("failed");
        phase = Phase.DONE;
        mc.stop();
    }
}
