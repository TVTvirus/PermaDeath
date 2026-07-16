# gaturro-bot · el bot del sotano de virus

Bot de Discord para **EspermaDeath**. Recibe los eventos del mod `PermadeathGaturro`
por HTTP y publica anuncios bonitos y personalizables en Discord.

```
  Minecraft (mod PermadeathGaturro)
        │  POST JSON  (muerte, tormenta, fase, End...)
        ▼
  gaturro-bot  (este)  ──►  Discord (embeds + menciones)
```

## Puesta en marcha (cuando quieras activarlo)

1. Instalar deps:
   ```bash
   pip install -r requirements.txt
   ```
2. Copiar y rellenar la config:
   ```bash
   cp config.example.json config.json
   ```
   - `token`: el del bot del sotano de virus (Developer Portal → Bot).
   - `secret`: una palabra secreta; **la misma** que pongas en el mod.
   - `channels`: IDs de los canales (activa modo desarrollador en Discord → clic derecho → Copiar ID).
   - `roles`: ID del rol que quieres mencionar para avisar a la gente.
3. Lanzar:
   ```bash
   python3 bot.py
   ```

## Activar el lado del mod

En la carpeta del mundo del server hay `gaturro_discord.json` (lo crea el mod). Ponlo:
```json
{ "enabled": true, "endpoint": "http://IP_DEL_BOT:8099/gaturro", "secret": "el-mismo-secret" }
```
Si el bot corre en la misma maquina que el server (contenedor aparte), usa la IP de la red docker.

## Personalizar los anuncios

Todo esta en **`templates.json`**. Cada evento tiene su plantilla editable:
`title`, `description`, `color`, `channel`, `footer` y `ping_role` (opcional, para avisar).
Placeholders disponibles por evento:

| Evento | Placeholders |
|--------|--------------|
| `death` | `{player}` `{lives}` `{day}` `{stormHours}` |
| `eliminated` | `{player}` `{day}` |
| `storm_start` | `{day}` `{secondsLeft}` |
| `phase` | `{day}` `{name}` `{announce}` |
| `end_open` | `{day}` |
| `death_clip` | `{player}` `{clip}` |

## Pendiente / ideas (fase 2)
- **Chat bridge** MC ↔ Discord (leer el chat del server y reenviarlo, y viceversa).
- **Board de vidas/equipos** en vivo (mensaje que se auto-edita).
- **Clip de audio** del ultimo minuto al morir (lo genera un addon de Simple Voice Chat y el bot lo sube al canal `deaths`).
