# PermaDeath

**A complete permadeath event system for Minecraft servers (Fabric, server-side).**
Lives, a cumulative death storm, an escalating phase calendar, a custom dragon boss
fight, death memorials, voice clips of every death, and full Discord integration.
Language: **English or Spanish**, every player-facing text is editable.

> Video renders of every death (chase-cam MP4 with the victim's real voice, posted
> to Discord automatically) live in a companion project: **[DeathCam](https://github.com/TVTvirus/DeathCam)**.

## What it does

- **Lives**: each player gets 3 lives (configurable per player with commands). On the
  last death: dramatic broadcast + permanent spectator, with darkness effect.
- **Death storm**: every death adds `server_day × 1h` of real-time thunderstorm for
  everyone. It stacks and cannot be skipped by sleeping.
- **Phase calendar** (`gaturro_phases.json`, fully editable): 30 days of escalating
  difficulty flags — buffed mobs during storms, double hostile caps, spider effects,
  failing totems, permanent heart loss with a recovery item, giant phantoms, minimum
  players to sleep, and the End sealed until a chosen day.
- **Dragon boss** (day 7 by default): the Ender Dragon fights back in three phases —
  TNT drops, exploding cats, adds, darkness screams and charge attacks. Health scales
  with players present. On death she drops **Hearts** at random lightning-struck spots
  on the island: right-click one for +1 life.
- **Death memorials**: a fence post with the victim's head, planted exactly where they fell.
- **Voice clips**: with Simple Voice Chat installed, every death produces an MP3 of the
  victim's last 50 seconds + 10 seconds of aftermath (their voice and what they heard),
  and voice goes global for 10 seconds so everyone hears the drama.
- **Discord bot** (`bot/`): death/storm/phase/boss announcements with editable templates,
  MC↔Discord chat bridge with player avatars, achievements feed, lives shown as hearts
  in the player list, and audio clips posted automatically.
- **Admin commands** (`/pdc`): status, storm control, lives, revive with dramatic
  announcement, per-player death messages, phase skipping for testing, and more.

## Language / Idioma

On first boot the mod writes `<world>/permadeath_lang.json` with every player-facing
string. Set `"lang": "en"` or `"lang": "es"` before first boot to pick defaults, then
edit anything you want — event name, storm name, taunts, all of it.

The bot ships `templates.en.json` and `templates.es.json`; copy one to `templates.json`.

## Setup

1. **Mod** (`mod/`): build with `JAVA_HOME=<jdk25> ./gradlew build` (MC 26.x requires
   Java 25) and drop the jar in your server's `mods/`. Requires Fabric API. Optional:
   Simple Voice Chat (voice features), ServerReplay (replays for DeathCam).
2. **Bot** (`bot/`): `pip install -r requirements.txt`, copy `config.example.json` to
   `config.json` (token, channels, shared secret), pick a language template, run `bot.py`.
3. In the world folder, set `gaturro_discord.json` → `enabled: true` and point the
   endpoint at the bot.

---

## En español

Sistema completo de eventos permadeath para servidores de Minecraft (Fabric, server-side):
vidas, tormenta acumulativa por muerte, calendario de fases con dificultad creciente,
jefe dragón custom, memoriales, clips de voz de cada muerte e integración con Discord.

Todos los textos que ven los jugadores son editables (`permadeath_lang.json`, con
defaults en español o inglés). Los videos automáticos de cada muerte (MP4 con la voz
real del muerto, publicados en Discord) están en el proyecto hermano
**[DeathCam](https://github.com/TVTvirus/DeathCam)**.

Nacido en producción, con jugadores reales muriéndose encima.
