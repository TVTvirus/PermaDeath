# EspermaDeath · Diseño maestro

Todo lo que queda por hacer, cómo hacerlo balanceado, e inspirado en el Permadeath
original de sebazcrc (adaptado: dura la mitad, 30 días, y la gente no es muy buena).

---

## 1. Estado actual (ya desplegado y funcionando)

- Server Fabric 26.2 + Java 25, login mixto, resource pack de terror forzado (Willows Horror Cave Sounds), Sound Physics en el modpack.
- Mod **v0.5.0** (compilado, NO desplegado todavía; en el server corre la v0.4.0):
  - 3 vidas/persona, muerte con título + sonido de wither, EspermaBaneado a spectator con oscuridad.
  - gaturro.exe: banco de tormenta insaltable (día x muerte horas reales, drena 24/7), action bar con el tiempo.
  - Baneo por AFK (6 días).
  - MOTD dinámico (Día X/30).
  - `/pdc` (status, storm, lives, revive, afk, reset, phase, day).
  - **Motor de fases config-driven** (`gaturro_phases.json`), dificultad automática, anuncios de fase, **End que se abre el día 7**.
  - **Discord notifier** (emite eventos; desactivado por defecto).

---

## 2. Calendario de fases (propuesta, editable en gaturro_phases.json)

Más suave que el original (que iba a 60-70 días). Todo se cambia editando el JSON, sin recompilar.

| Día | Fase | Dificultad | Qué se activa |
|-----|------|-----------|----------------|
| 1 | Semivanilla | normal | Solo vidas + gaturro.exe |
| 4 | Se calienta | hard | `poisoned_food` (comida podrida) |
| 7 | El End despierta | hard | `end_open` (se abre el End) |
| 10 | Aracnofobia | hard | `spider_effect` (arañas con veneno/efectos) |
| 13 | Totems traicioneros | hard | `totem_fail` (5% de fallo) |
| 16 | Sin refugio | hard | `all_hostile` (mobs de día) |
| 20 | Gatos Supernova | hard | `gatos_supernova` + `player_skulls` |
| 25 | Colapso | hard | `totem_fail_high` (15% de fallo) |
| 30 | El último día | hard | Cierre |

El motor YA reconoce estos flags y los anuncia. Falta implementar la LÓGICA de cada uno (abajo).

---

## 3. Features por flag (cómo implementarlas, inspiración y balance)

### `end_open` (HECHO)
Bloquea el End hasta el día 7 (expulsa al overworld a quien entre). Ya funciona.

### `poisoned_food`
- **Qué**: ciertas comidas envenenan al comerlas (guiño al original: "hicieron que x comidas envenenaran").
- **Cómo**: evento de "terminar de comer". En Fabric no hay evento estable de comer → mixin a `LivingEntity.eat` / `ServerPlayer` o usar `UseItemCallback`. Alternativa sin mixin: al detectar en tick que el jugador comió (comparar hambre/inventario) es frágil → **mejor un mixin** a `Item.finishUsingItem` o `FoodData`.
- **Balance**: elegir 3-4 comidas comunes (pan, manzana, carne cocida) y aplicar Poison I 5s. Configurable qué comidas.
- **Iniciativa**: hacer la lista de comidas envenenadas parte de `gaturro_phases.json` (campo por flag).

### `spider_effect`
- **Qué**: arañas con efectos (el original: "arañas con efectos de poción").
- **Cómo**: al spawnear una araña (mixin o `ServerEntityEvents.ENTITY_LOAD` de Fabric), darle un efecto (fuerza, velocidad) o hacer que al golpear apliquen veneno.
- **Balance**: Velocidad I + al picar Poison I. Suave.

### `totem_fail`
- **Qué**: probabilidad de que el tótem de la inmortalidad NO te salve (idea genial del original: escala con el día).
- **Cómo**: interceptar el uso del tótem. Mixin a `LivingEntity.checkTotemDeathProtection` (donde vanilla consume el tótem y revive). Si `random < prob`, no proteger → muerte real.
- **Balance**: día 13 = 5%, día 25 = 15%. (El original llegaba a 15% muy tarde; para 30 días esto va bien.)

### `all_hostile`
- **Qué**: los monstruos spawnan de día / a plena luz (original: "todos hostiles día +20").
- **Cómo**: mixin al chequeo de luz de spawn de mobs, o forzar spawns con un spawner custom. Lo más simple: mixin a `Monster.isDarkEnoughToSpawn` para que devuelva true.
- **Balance**: solo overworld, no en zonas techadas del jugador si se puede.

### `gatos_supernova` (temático, encaja con Gaturro)
- **Qué**: gatos que explotan (el original tenía "Gatos Supernova", explosión 200). Muy Mundo Gaturro.
- **Cómo**: spawns ocasionales de gatos (ocelote/cat) con AI de creeper, o al morir un gato explota. Requiere entidad custom o modificar la AI (mixin/goal). Es la feature más pesada.
- **Balance**: explosión potencia 10-20 (NO 200 como el original; nuestra gente no es buena). Sin destruir demasiado.

### `player_skulls`
- **Qué**: al morir, dropea tu cabeza (original: "cabezas de jugador al morir").
- **Cómo**: en `onPlayerDeath`, dropear un `PLAYER_HEAD` con el perfil del jugador en su posición. Fácil, API de items.
- **Balance**: cosmético/trofeo. Sin downside.

---

## 4. Voz global 10s al morir

- **Qué**: al morir alguien, la voz de proximidad se vuelve GLOBAL (todos se oyen) durante 10s. Momento de caos.
- **Cómo**: addon server-side de **Simple Voice Chat** (su API `de.maxhenkel.voicechat:voicechat-api`). Registrar un `VoicechatPlugin` que en `MicrophonePacketEvent`: si `now < globalVoiceUntil`, en vez de audio posicional, reenviar a TODOS (broadcast). `GaturroManager.onPlayerDeath` pone `globalVoiceUntil = now + 10s`.
- **Setup**: añadir `modCompileOnly` de voicechat-api + repo maven.maxhenkel.de + registrar el plugin (ServiceLoader `META-INF/services/de.maxhenkel.voicechat.api.VoicechatPlugin`). Preparado para hacerlo contigo delante (necesita prueba con jugadores).

## 5. Clip de audio del último minuto al morir

- **Qué**: guardar los últimos ~60s de voz de un jugador y subirlos a Discord al morir.
- **Cómo**: mismo addon de Simple Voice Chat. Bufferar por jugador los paquetes opus (anillo de 60s), y al morir decodificar a WAV y avisar al bot (`death_clip` con la ruta) para que lo suba. Es lo más avanzado; fase posterior.

## 6. Equipos

- **Ahora**: vanilla `/team` (ya creamos PMR). Funciona para tab + nametag.
- **Propuesta**: `/pdc team crear <tag> <color> <jugadores...>` en el mod, para consistencia y para que el mod "conozca" los equipos (útil para voz de equipo o board Discord). Bajo esfuerzo.
- **Pendiente tuyo**: los ~6 equipos definitivos + si tú juegas o eres game master.

## 7. Discord (ya preparado, en `~/Documentos/W4VE/gaturro-bot/`)

- Bot Python que recibe eventos del mod y publica anuncios con **plantillas personalizables** (`templates.json`) y **menciones de rol** para avisar.
- Solo falta: rellenar `config.json` (token, canales, roles) y activar `gaturro_discord.json` en el server. Se hace manualmente contigo.
- **Fase 2**: chat bridge MC↔Discord, board de vidas en vivo, subida de clips.

## 8. Largo plazo (sin prisa)

- **Jefe del End** (tipo "PermadeathDemon" del original: dragón con más vida / ataques) para cuando se abra el día 7.
- **Favicon** del server: la cabeza de Gaturro en la lista de servidores (server-icon.png 64x64).
- **Islas de spawn** custom (el original arrancaba en islas por schematic).

---

## 9. DECISIONES QUE NECESITO DE TI

1. **¿Juegas o eres game master?** (define si vas en un equipo).
2. **Equipos definitivos**: los ~6 grupos de 3 (nombres/tags/colores) con tus ~19 jugadores.
3. **Calendario**: ¿te vale la tabla del punto 2 o quieres mover días/eventos?
4. **Balance de dificultad**: ¿la subida propuesta te parece bien para "gente no muy buena", o más suave?
5. **Fecha de inicio real** del evento (para poner el Día 1 de verdad).
6. **Totem fail**: ¿te gusta la idea de tótems que pueden fallar? (a mí me parece de las mejores del original).
7. **Gatos Supernova**: ¿los quieres? (es la feature más curra, pero muy temática).
8. **Prioridad**: ¿por dónde ataco primero al volver? (mi recomendación: desplegar v0.5.0 y probar fases + End, luego voz global, luego el resto de flags).
