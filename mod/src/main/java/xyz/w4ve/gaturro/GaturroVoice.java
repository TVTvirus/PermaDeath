package xyz.w4ve.gaturro;

import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.audiochannel.StaticAudioChannel;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import de.maxhenkel.voicechat.api.events.PlayerConnectedEvent;
import de.maxhenkel.voicechat.api.events.PlayerDisconnectedEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStartedEvent;
import de.maxhenkel.voicechat.api.mp3.Mp3Encoder;
import de.maxhenkel.voicechat.api.opus.OpusDecoder;

import javax.sound.sampled.AudioFormat;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Voz del EspermaDeath, sobre la API de Simple Voice Chat.
 *
 * Hace dos cosas, que son la misma cosa:
 *   1. Voz GLOBAL 10s al morir alguien: todos se oyen con todos, sin importar la distancia.
 *   2. Clip del ultimo minuto: 50s ANTES de morir (lo que el muerto oia + su propia voz)
 *      + 10s DESPUES (su reaccion en vivo, que es justo la ventana de voz global).
 *      Sale en MP3 al volumen del server; el bot lo recoge del disco y lo sube a Discord.
 *
 * Se registra por el entrypoint "voicechat" de fabric.mod.json (lo carga SVC, no nosotros).
 * OJO: todos los callbacks de audio entran por el hilo de audio de SVC, no por el del
 * server -> todo aqui es concurrente y el render pesado va a su propio executor.
 */
public class GaturroVoice implements VoicechatPlugin {

    /** SVC trabaja en 48kHz mono, frames de 20ms (960 samples). */
    private static final int SAMPLE_RATE = 48000;
    private static final int FRAME_MS = 20;

    /** El clip: 50s antes de morir + 10s despues (la ventana de voz global). */
    private static final int CLIP_BEFORE_MS = 50_000;
    private static final int CLIP_AFTER_MS = 10_000;
    /** Cuanto dura la voz global al morir alguien. */
    public static final int GLOBAL_VOICE_MS = 10_000;
    /** Margen del buffer, para no quedarnos cortos al recortar. */
    private static final int BUFFER_MS = CLIP_BEFORE_MS + CLIP_AFTER_MS + 10_000;

    /** MP3 mono de voz: 64kbps sobra, quality 5 = equilibrio de LAME. */
    private static final int MP3_BITRATE = 64;
    private static final int MP3_QUALITY = 5;

    public static final String CLIP_DIR = "gaturro_clips";

    private static GaturroVoice instance;

    private VoicechatApi api;
    private VoicechatServerApi serverApi;
    private Path clipDir;

    /** Un frame de voz tal como llego: opus crudo, sin decodificar (barato de guardar). */
    private record Frame(long t, UUID sender, byte[] opus) {}

    /** Lo que OYE cada jugador: uuid del oyente -> frames recientes. */
    private final Map<UUID, ConcurrentLinkedDeque<Frame>> heard = new ConcurrentHashMap<>();
    /** Canales de voz global, uno por hablante (se reusan). */
    private final Map<UUID, StaticAudioChannel> globalChannels = new ConcurrentHashMap<>();
    /** Clips en curso: uuid del muerto -> cuando toca renderizar. */
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();

    private record Pending(String name, long deathT, long renderAt) {}

    private volatile long globalVoiceUntil = 0L;
    /** Cuantos paquetes de micro hemos visto por jugador (diagnostico). */
    private final Map<UUID, Integer> micPackets = new ConcurrentHashMap<>();
    /** Listeners registrados de verdad (diagnostico). */
    private final java.util.Set<UUID> listening = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private final ExecutorService renderPool = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "gaturro-clip");
        t.setDaemon(true);
        return t;
    });

    public GaturroVoice() {
        instance = this;
    }

    public static GaturroVoice get() {
        return instance;
    }

    @Override
    public String getPluginId() {
        return PermadeathGaturro.MOD_ID;
    }

    @Override
    public void initialize(VoicechatApi api) {
        this.api = api;
        PermadeathGaturro.LOGGER.info("[gaturro] Voz enganchada a Simple Voice Chat.");
    }

    @Override
    public void registerEvents(EventRegistration reg) {
        reg.registerEvent(VoicechatServerStartedEvent.class, e -> serverApi = e.getVoicechat());
        reg.registerEvent(PlayerConnectedEvent.class, this::onConnect);
        reg.registerEvent(PlayerDisconnectedEvent.class, this::onDisconnect);
        reg.registerEvent(MicrophonePacketEvent.class, this::onMicrophone);
    }

    /** Donde escribir los clips (dentro del world, que el bot lee desde el host). */
    public void setClipDir(Path dir) {
        this.clipDir = dir;
        try {
            Files.createDirectories(dir);
        } catch (Exception e) {
            PermadeathGaturro.LOGGER.warn("[gaturro] no pude crear {}: {}", dir, e.getMessage());
        }
    }

    // ------------------------------------------------------------ captura

    /**
     * Por cada jugador registramos un listener: SVC nos entrega los paquetes de voz
     * que ESA persona oye (ya filtrados por distancia/grupo). Eso es exactamente
     * el clip "desde su oreja".
     */
    private void onConnect(PlayerConnectedEvent e) {
        VoicechatConnection conn = e.getConnection();
        if (conn == null || serverApi == null) return;
        UUID id = conn.getPlayer().getUuid();
        heard.put(id, new ConcurrentLinkedDeque<>());
        boolean ok = serverApi.registerAudioListener(serverApi.playerAudioListenerBuilder()
                .setPlayer(id)
                .setPacketListener(pkt -> push(id, pkt.getSender(), pkt.getOpusEncodedData()))
                .build());
        if (ok) listening.add(id);
        PermadeathGaturro.LOGGER.info("[gaturro] escuchando a {}: {}", id, ok ? "OK" : "FALLO el listener");
    }

    private void onDisconnect(PlayerDisconnectedEvent e) {
        UUID id = e.getPlayerUuid();
        if (id == null) return;
        if (serverApi != null) serverApi.unregisterAudioListener(id);
        // El buffer NO se borra: si murio y se desconecto, el clip todavia se renderiza.
    }

    /**
     * Nuestro propio micro. Sirve para dos cosas: uno NO se oye a si mismo en su
     * listener (hay que meter su voz a mano en su propio buffer), y si la voz
     * global esta abierta, este paquete va para todo el mundo.
     */
    private void onMicrophone(MicrophonePacketEvent e) {
        VoicechatConnection sender = e.getSenderConnection();
        if (sender == null) return;
        UUID id = sender.getPlayer().getUuid();
        byte[] opus = e.getPacket().getOpusEncodedData();
        if (opus == null || opus.length == 0) return;

        push(id, id, opus); // su voz, en su propio clip
        micPackets.merge(id, 1, Integer::sum);

        if (System.currentTimeMillis() < globalVoiceUntil) {
            broadcastGlobal(e, id, opus);
        }
    }

    private void push(UUID listener, UUID sender, byte[] opus) {
        if (opus == null || opus.length == 0 || sender == null) return;
        ConcurrentLinkedDeque<Frame> buf = heard.computeIfAbsent(listener, k -> new ConcurrentLinkedDeque<>());
        long now = System.currentTimeMillis();
        buf.addLast(new Frame(now, sender, opus));
        // Poda: nos quedamos solo con la ventana util.
        Frame head;
        while ((head = buf.peekFirst()) != null && now - head.t() > BUFFER_MS) {
            buf.pollFirst();
        }
    }

    // ------------------------------------------------------------ voz global

    /** Abre la voz global (todos se oyen) durante GLOBAL_VOICE_MS. */
    public void openGlobalVoice() {
        globalVoiceUntil = System.currentTimeMillis() + GLOBAL_VOICE_MS;
    }

    /**
     * Manda el paquete a TODOS por un canal estatico (sin distancia) y cancela el
     * envio normal, para que los que estan cerca no lo oigan dos veces.
     *
     * Un canal por hablante, reusado y filtrado: el filtro decide a quien llega,
     * asi no hay que crear un canal por oyente.
     */
    private void broadcastGlobal(MicrophonePacketEvent e, UUID senderId, byte[] opus) {
        if (serverApi == null) return;
        try {
            StaticAudioChannel ch = globalChannels.computeIfAbsent(senderId, id -> {
                StaticAudioChannel c = serverApi.createStaticAudioChannel(id);
                if (c != null) {
                    c.setBypassGroupIsolation(true); // el drama es de todos, aunque estes en un grupo
                    c.setFilter(p -> !p.getUuid().equals(id)); // a todos menos a si mismo
                }
                return c;
            });
            if (ch == null || ch.isClosed()) {
                globalChannels.remove(senderId);
                return;
            }
            ch.send(opus);
            if (e.isCancellable()) e.cancel(); // ya lo mandamos nosotros, que no vaya tambien por proximidad
        } catch (Throwable t) {
            PermadeathGaturro.LOGGER.warn("[gaturro] fallo la voz global: {}", t.toString());
        }
    }

    // ------------------------------------------------------------ clip

    /**
     * Alguien murio: abrimos la voz global y programamos el clip para dentro de
     * 10s (hay que esperar a grabar su reaccion antes de renderizar).
     */
    public void onDeath(UUID id, String name) {
        long now = System.currentTimeMillis();
        openGlobalVoice();
        pending.put(id, new Pending(name, now, now + CLIP_AFTER_MS + 500));
        PermadeathGaturro.LOGGER.info("[gaturro] clip de {} programado (+{}s). Buffer: {} frames, {} paquetes de micro suyos.",
                name, (CLIP_AFTER_MS + 500) / 1000,
                heard.getOrDefault(id, new ConcurrentLinkedDeque<>()).size(),
                micPackets.getOrDefault(id, 0));
    }

    /** Diagnostico para /pdc voice. */
    public String debug(UUID id) {
        boolean svc = serverApi != null && serverApi.getConnectionOf(id) != null;
        int frames = heard.getOrDefault(id, new ConcurrentLinkedDeque<>()).size();
        return "SVC api=" + (serverApi != null ? "ok" : "NULL")
                + " · conectado a voz=" + svc
                + " · listener=" + listening.contains(id)
                + " · frames que oyes=" + frames
                + " (~" + (frames * FRAME_MS / 1000) + "s)"
                + " · paquetes de TU micro=" + micPackets.getOrDefault(id, 0)
                + " · carpeta=" + (clipDir != null ? "ok" : "NULL");
    }

    /** Lo llama GaturroManager cada segundo: renderiza los clips que ya cumplieron su espera. */
    public void tickClips() {
        long now = System.currentTimeMillis();
        for (var entry : new ArrayList<>(pending.entrySet())) {
            Pending p = entry.getValue();
            if (now < p.renderAt()) continue;
            UUID id = entry.getKey();
            pending.remove(id);
            List<Frame> snapshot = new ArrayList<>(heard.getOrDefault(id, new ConcurrentLinkedDeque<>()));
            renderPool.submit(() -> render(id, p, snapshot));
        }
    }

    /**
     * Mezcla los frames en una pista de 60s y la escribe a MP3.
     * Cada hablante necesita SU decoder: Opus tiene estado entre frames.
     */
    private void render(UUID id, Pending p, List<Frame> frames) {
        try {
            if (api == null || clipDir == null || frames.isEmpty()) {
                PermadeathGaturro.LOGGER.warn("[gaturro] sin clip para {}: api={} carpeta={} frames={}",
                        p.name(), api != null, clipDir != null, frames.size());
                return;
            }
            PermadeathGaturro.LOGGER.info("[gaturro] armando clip de {}: {} frames en buffer", p.name(), frames.size());
            long t0 = p.deathT() - CLIP_BEFORE_MS;
            long t1 = p.deathT() + CLIP_AFTER_MS;
            int total = (int) ((t1 - t0) / 1000L) * SAMPLE_RATE;
            int[] mix = new int[total];

            Map<UUID, List<Frame>> bySender = new HashMap<>();
            for (Frame f : frames) {
                if (f.t() < t0 || f.t() > t1) continue;
                bySender.computeIfAbsent(f.sender(), k -> new ArrayList<>()).add(f);
            }
            if (bySender.isEmpty()) {
                PermadeathGaturro.LOGGER.info("[gaturro] {} murio en silencio (habia {} frames, ninguno en la ventana del clip).",
                        p.name(), frames.size());
                return;
            }
            PermadeathGaturro.LOGGER.info("[gaturro] clip de {}: {} hablante(s)", p.name(), bySender.size());

            for (var e : bySender.entrySet()) {
                List<Frame> list = e.getValue();
                list.sort(Comparator.comparingLong(Frame::t));
                OpusDecoder dec = api.createDecoder();
                try {
                    // El reloj de llegada trae jitter (+-100ms): si colocamos cada frame
                    // por su hora, el audio queda a trompicones. En su lugar: cada RACHA
                    // de voz se ancla una vez, y sus frames se pegan contiguos.
                    long lastT = Long.MIN_VALUE;
                    int writePos = 0;
                    for (Frame f : list) {
                        short[] pcm = dec.decode(f.opus());
                        if (pcm == null || pcm.length == 0) continue;
                        if (f.t() - lastT > 400) { // silencio: nueva racha, re-anclar
                            writePos = (int) ((f.t() - t0) * SAMPLE_RATE / 1000L);
                            dec.resetState();
                        }
                        lastT = f.t();
                        for (int i = 0; i < pcm.length; i++) {
                            int at = writePos + i;
                            if (at >= 0 && at < total) mix[at] += pcm[i];
                        }
                        writePos += pcm.length;
                    }
                } finally {
                    dec.close();
                }
            }

            short[] out = new short[total];
            for (int i = 0; i < total; i++) {
                out[i] = (short) Math.clamp(mix[i], Short.MIN_VALUE, Short.MAX_VALUE); // suma con tope
            }

            String safe = p.name().replaceAll("[^A-Za-z0-9_.-]", "_");
            Path file = writeAudio(out, safe + "_" + p.deathT());
            if (file == null) return;

            PermadeathGaturro.LOGGER.info("[gaturro] Clip de {} listo: {}", p.name(), file.getFileName());
            if (DiscordNotifier.get() != null) {
                DiscordNotifier.get().deathClip(p.name(), file.toAbsolutePath().toString());
            }
        } catch (Throwable t) {
            PermadeathGaturro.LOGGER.error("[gaturro] no pude armar el clip de " + p.name(), t);
        }
    }

    /** MP3 si LAME esta disponible (lo trae SVC); si no, WAV crudo para no perder el clip. */
    private Path writeAudio(short[] pcm, String base) {
        AudioFormat fmt = new AudioFormat(SAMPLE_RATE, 16, 1, true, false);
        Path mp3 = clipDir.resolve(base + ".mp3");
        try (OutputStream os = Files.newOutputStream(mp3)) {
            Mp3Encoder enc = api.createMp3Encoder(fmt, MP3_BITRATE, MP3_QUALITY, os);
            if (enc == null) throw new IllegalStateException("LAME no disponible");
            // De a un frame: LAME no quiere bloques gigantes.
            int chunk = SAMPLE_RATE / (1000 / FRAME_MS);
            for (int i = 0; i < pcm.length; i += chunk) {
                int len = Math.min(chunk, pcm.length - i);
                short[] part = new short[len];
                System.arraycopy(pcm, i, part, 0, len);
                enc.encode(part);
            }
            enc.close();
            return mp3;
        } catch (Throwable t) {
            PermadeathGaturro.LOGGER.warn("[gaturro] MP3 fallo ({}), tiro de WAV", t.toString());
            try {
                Files.deleteIfExists(mp3);
            } catch (Exception ignored) {}
        }
        Path wav = clipDir.resolve(base + ".wav");
        try {
            Files.write(wav, wav(pcm));
            return wav;
        } catch (Exception e) {
            PermadeathGaturro.LOGGER.error("[gaturro] tampoco pude escribir el WAV", e);
            return null;
        }
    }

    /** WAV 48kHz mono 16-bit a mano (44 bytes de cabecera y a correr). */
    private static byte[] wav(short[] pcm) {
        int dataLen = pcm.length * 2;
        ByteBuffer b = ByteBuffer.allocate(44 + dataLen).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes()).putInt(36 + dataLen).put("WAVE".getBytes());
        b.put("fmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) 1);
        b.putInt(SAMPLE_RATE).putInt(SAMPLE_RATE * 2).putShort((short) 2).putShort((short) 16);
        b.put("data".getBytes()).putInt(dataLen);
        for (short s : pcm) b.putShort(s);
        return b.array();
    }
}
