# EspermaDeath 🐭⚡

*A fully automated Minecraft permadeath event system: custom server mod, Discord bot, and an automatic death-replay-to-MP4 render pipeline. Built live during the event it runs.*

Sistema completo del evento **EspermaDeath** (permadeath de 30 días en el "Mundo Gaturro"):
3 vidas por persona, tormenta acumulativa cuando alguien muere (gaturro.exe), calendario
de fases que escala la dificultad, batalla final contra la Dragona, y un pipeline que
convierte cada muerte en contenido para Discord sin que nadie mueva un dedo.

## Qué pasa cuando alguien muere

1. **In-game**: pierde una vida, tormenta global +N horas, title dramático, y su cabeza
   queda clavada en un palo en el lugar exacto de la muerte.
2. **Discord (automático)**: embed de la muerte con su mensaje personalizado, **clip de
   audio MP3** de su último minuto de voz (50s antes + 10s de reacción, vía Simple Voice
   Chat), y el **replay** de su muerte (ServerReplay, formato Flashback).
3. **Video (automático)**: un PC worker baja el replay, lo abre en un Minecraft headless
   con Flashback, planta una cámara POV en la cabeza del muerto, exporta MP4 y lo publica
   en Discord. Nadie toca nada.

## Piezas

| Carpeta | Qué es |
|---|---|
| `mod/` | **PermadeathGaturro**: mod Fabric server-side (MC 26.2). Vidas, tormenta, fases, flags de dificultad (mixins), batalla de la Dragona, memoriales, voz global + clips de audio (API de Simple Voice Chat), eventos HTTP al bot. |
| `bot/` | Bot de Discord (discord.py + aiohttp). Publica muertes/anuncios/clips/replays, chat bridge MC↔Discord con caras por webhook, logros desde el log, vidas como ❤ en el tab vía RCON, cola de render. |
| `render/mod/` | **EspermaRender**: mod Fabric cliente que automatiza Flashback: abre el replay, cámara TrackEntity al muerto, exporta MP4 y cierra el juego. |
| `render/worker/` | Worker (bash + systemd user timer) que corre en un PC con GPU: baja replays pendientes, lanza la instancia de render y sube los MP4. |

## Notas de compilación

- Mods: `JAVA_HOME=<jdk25> ./gradlew build` (MC 26.x exige Java 25; sin `mappings` en
  gradle: el runtime de Fabric 26.x usa mojang mappings, mixins sin refmap).
- `render/mod` compila contra el jar de Flashback como `compileOnly`: descárgalo de
  [Modrinth](https://modrinth.com/mod/flashback) a `render/mod/libs/` (su licencia no
  permite redistribuirlo).
- `bot/config.example.json` → `config.json` con tu token/canales/secret.

Nacido en producción un 15 de julio, con 14 personas muriéndose encima.
