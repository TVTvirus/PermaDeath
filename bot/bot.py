#!/usr/bin/env python3
"""
Bot de EspermaDeath para el "sotano de virus".

Recibe los eventos que le manda el mod (PermadeathGaturro) por HTTP y los publica
como anuncios bonitos (embeds) en Discord, con plantillas 100% personalizables
(templates.json) y menciones de rol para avisar a la gente.

Arquitectura:   mod  --HTTP POST-->  este bot  -->  Discord

Uso:
    pip install -r requirements.txt
    cp config.example.json config.json   # y rellena token, canales, roles
    python3 bot.py
"""

import asyncio
import json
import re
import os
import shutil
import sys
import time
import functools
import discord
from aiohttp import web

# Los print() bufferizados nos dejaban sin saber que pasaba: aqui salen ya.
print = functools.partial(print, flush=True)

HERE = os.path.dirname(os.path.abspath(__file__))
CONFIG = json.load(open(os.path.join(HERE, "config.json")))
TEMPLATES = json.load(open(os.path.join(HERE, "templates.json")))

intents = discord.Intents.default()
intents.message_content = True  # puente Discord->MC (requiere el switch en el Dev Portal)
client = discord.Client(intents=intents)


def render(text: str, data: dict) -> str:
    """Formatea una plantilla con los datos del evento, tolerante a huecos."""
    try:
        return text.format(**data)
    except Exception:
        return text


def host_path(container_path: str) -> str:
    """
    El mod corre dentro del contenedor y ve rutas tipo /home/container/world/...
    Nosotros somos el host: el mismo archivo vive en el volumen de Pterodactyl.
    clip_path_map = {"/home/container": "/var/lib/pterodactyl/volumes/<uuid>"}
    """
    for src, dst in CONFIG.get("clip_path_map", {}).items():
        if container_path.startswith(src):
            return dst.rstrip("/") + container_path[len(src):]
    return container_path


def clip_file(data: dict):
    """El clip de audio del muerto, si el evento trae uno y existe en disco."""
    path = data.get("clip")
    if not path:
        return None
    real = host_path(path)
    if not os.path.isfile(real):
        print(f"[gaturro] clip no encontrado: {real}")
        return None
    size = os.path.getsize(real)
    if size > 9 * 1024 * 1024:  # margen bajo el limite de subida de Discord
        print(f"[gaturro] clip demasiado grande ({size} bytes): {real}")
        return None
    return discord.File(real, filename=os.path.basename(real))


async def find_and_post_replay(data: dict):
    """
    El mod corto la grabacion del muerto; ServerReplay tarda unos segundos en
    comprimir y soltar el .mcpr. Esperamos a que aparezca (tamano estable) y
    lo subimos: adjunto si es pequeno, link en api.w4ve.xyz si pasa del limite.
    """
    player = data.get("player", "?")
    rec_root = CONFIG.get("replay_dir")
    if not rec_root:
        print("[gaturro] replay_dir no configurado, ignoro death_replay")
        return
    rec_dir = os.path.join(rec_root, player)
    started = time.time()
    found = None
    while time.time() - started < 300:
        try:
            cands = [os.path.join(rec_dir, f) for f in os.listdir(rec_dir) if f.endswith((".mcpr", ".zip"))]
            fresh = [f for f in cands if os.path.getmtime(f) > started - 120]
            if fresh:
                newest = max(fresh, key=os.path.getmtime)
                s1 = os.path.getsize(newest)
                await asyncio.sleep(6)
                if os.path.getsize(newest) == s1 and s1 > 0:
                    found = newest
                    break
        except FileNotFoundError:
            pass
        await asyncio.sleep(6)
    if not found:
        print(f"[gaturro] no aparecio el replay de {player} en {rec_dir}")
        return

    ch_id = CONFIG.get("channels", {}).get("deaths")
    channel = client.get_channel(int(ch_id)) if ch_id else None
    if channel is None and ch_id:
        try:
            channel = await client.fetch_channel(int(ch_id))
        except Exception:
            channel = None
    if channel is None:
        print("[gaturro] canal deaths no disponible para el replay")
        return

    size = os.path.getsize(found)
    day = data.get("day", "?")
    embed = discord.Embed(
        title=f"\U0001F3AC Replay de la muerte de {player}",
        description="Se ve con el mod Flashback (y desde ahí se exporta a video).",
        color=0x9944FF,
    )
    embed.set_footer(text=f"Dia {day} de EspermaDeath")
    try:
        if size <= 9 * 1024 * 1024:
            fname = f"{player}_{os.path.basename(found)}"
            await channel.send(embed=embed, file=discord.File(found, filename=fname))
        else:
            web_dir = CONFIG.get("web_clips_dir", "/usr/share/nginx/api/gaturro/clips")
            web_url = CONFIG.get("web_clips_url", "https://api.w4ve.xyz/v1/gaturro/clips")
            os.makedirs(web_dir, exist_ok=True)
            fname = f"{player}_{int(time.time())}{os.path.splitext(found)[1]}"
            shutil.copy2(found, os.path.join(web_dir, fname))
            embed.description += f"\n\n[Descargar replay ({size // 1048576}MB)]({web_url}/{fname})"
            await channel.send(embed=embed)
        print(f"[gaturro] replay de {player} publicado ({size} bytes)")
        # a la cola de render: el PC del admin lo convierte en MP4 (POV del muerto)
        try:
            uuid = None
            for e in json.load(open(os.path.join(VOLUME, "usercache.json"))):
                if e.get("name") == player:
                    uuid = e.get("uuid")
                    break
            if uuid:
                qdir = f"/root/gaturro-render/queue/{player}_{int(time.time())}"
                os.makedirs(qdir, exist_ok=True)
                shutil.move(found, os.path.join(qdir, "replay.zip"))
                with open(os.path.join(qdir, "job.json"), "w") as f:
                    json.dump({"player": player, "follow": uuid, "day": data.get("day", 0),
                               "seconds": 62, "width": 1280, "height": 720,
                               "fps": 30, "bitrate": 4000000}, f)
                print(f"[gaturro] replay de {player} encolado para render")
            else:
                os.remove(found)
        except Exception as e:
            print(f"[gaturro] no pude encolar el render: {e}")
    except Exception as e:
        print(f"[gaturro] ERROR subiendo replay de {player}: {type(e).__name__}: {e}")


_bridge_webhook = None

async def get_bridge_webhook():
    """Webhook del canal puente: los mensajes salen con la cara y nombre del jugador."""
    global _bridge_webhook
    if _bridge_webhook is not None:
        return _bridge_webhook
    ch_id = CONFIG.get("channels", {}).get("bridge")
    if not ch_id:
        return None
    try:
        channel = client.get_channel(int(ch_id)) or await client.fetch_channel(int(ch_id))
        for wh in await channel.webhooks():
            if wh.name == "gaturro-bridge" and wh.token:
                _bridge_webhook = wh
                return wh
        _bridge_webhook = await channel.create_webhook(name="gaturro-bridge")
        return _bridge_webhook
    except Exception as e:
        print(f"[gaturro] sin webhook (¿falta permiso Manage Webhooks?): {e}")
        return None


def face(player: str) -> str:
    return f"https://mc-heads.net/avatar/{player}/64"


ADV_RE = re.compile(r"\]: (?:\[[^\]]+\] )?(\w+) has (made the advancement|reached the goal|completed the challenge) \[(.+)\]")

async def tail_server_log():
    """Lee el log del server en vivo y publica los LOGROS en el canal puente.
    (Va por docker logs para no tener que tocar el mod ni reiniciar el server.)"""
    container = CONFIG.get("container", "93fdfb3f-6d0b-4cdd-8303-16a9a323b8b0")
    kinds = {"made the advancement": ("consiguió el logro", "🏆"),
             "reached the goal": ("alcanzó el objetivo", "🥅"),
             "completed the challenge": ("completó el DESAFÍO", "🌟")}
    while True:
        try:
            proc = await asyncio.create_subprocess_exec(
                "docker", "logs", "-f", "--tail", "0", container,
                stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.STDOUT)
            print("[gaturro] siguiendo el log del server (logros)")
            while True:
                line = await proc.stdout.readline()
                if not line:
                    break
                m = ADV_RE.search(line.decode(errors="replace"))
                if not m:
                    continue
                player, kind, adv = m.group(1), m.group(2), m.group(3)
                verb, emoji = kinds.get(kind, ("consiguió", "🏆"))
                wh = await get_bridge_webhook()
                text = f"{emoji} {verb} **{adv}**"
                if wh:
                    await wh.send(content=text, username=player, avatar_url=face(player),
                                  allowed_mentions=discord.AllowedMentions.none())
        except Exception as e:
            print(f"[gaturro] tail del log murio: {e}")
        await asyncio.sleep(10)  # el contenedor se reinicio: reengancharse


async def bridge_to_discord(etype: str, data: dict):
    """Chat/entradas/salidas del server -> canal puente (mensaje plano, sin pings)."""
    ch_id = CONFIG.get("channels", {}).get("bridge")
    if not ch_id:
        return
    channel = client.get_channel(int(ch_id))
    if channel is None:
        try:
            channel = await client.fetch_channel(int(ch_id))
        except Exception:
            return
    player = data.get("player", "?")
    try:
        if etype == "chat":
            wh = await get_bridge_webhook()
            if wh:
                # con webhook el mensaje sale con la cara y el nombre del jugador
                await wh.send(content=data.get("message", "")[:1900], username=player,
                              avatar_url=face(player),
                              allowed_mentions=discord.AllowedMentions.none())
            else:
                await channel.send(f"**{player}** » {data.get('message', '')}"[:1900],
                                   allowed_mentions=discord.AllowedMentions.none())
        else:
            text = f"→ **{player}** entró al server" if etype == "join" else f"← **{player}** salió del server"
            await channel.send(text, allowed_mentions=discord.AllowedMentions.none())
    except Exception as e:
        print(f"[gaturro] bridge->discord fallo: {e}")


def tellraw_json(author: str, msg: str) -> str:
    parts = [
        {"text": "[Discord] ", "color": "aqua"},
        {"text": f"{author} » ", "color": "yellow"},
        {"text": msg, "color": "white"},
    ]
    return "tellraw @a " + json.dumps(parts, ensure_ascii=False)


@client.event
async def on_message(message: discord.Message):
    """Puente Discord -> MC: lo que se escribe en el canal bridge sale in-game."""
    ch_id = CONFIG.get("channels", {}).get("bridge")
    if not ch_id or message.author.bot or message.channel.id != int(ch_id):
        return
    content = (message.clean_content or "").strip()
    if not content:
        return
    try:
        import rcon as rconmod
        pw = open(os.path.join(HERE, "rcon_pw.txt")).read().strip()
        cmd = tellraw_json(message.author.display_name, content[:256])
        await asyncio.to_thread(rconmod.rcon, cmd, pw)
    except Exception as e:
        print(f"[gaturro] bridge->mc fallo: {e}")


VOLUME = "/var/lib/pterodactyl/volumes/93fdfb3f-6d0b-4cdd-8303-16a9a323b8b0"

_lives_cache = {}

async def update_lives_scores():
    """Vuelca las vidas al objetivo 'vidas' del tab (RCON). Solo manda scores que
    CAMBIARON (broadcast-rcon-to-ops spamea a los ops cada comando) y usa
    usercache.json para el nombre EXACTO (la whitelist a veces difiere en mayusculas)."""
    try:
        import rcon as rconmod
        state = json.load(open(os.path.join(VOLUME, "world/permadeath_gaturro.json")))
        names = {}
        try:
            for e in json.load(open(os.path.join(VOLUME, "whitelist.json"))):
                if e.get("uuid") and e.get("name"):
                    names[e["uuid"]] = e["name"]
        except Exception:
            pass
        try:
            for e in json.load(open(os.path.join(VOLUME, "usercache.json"))):
                if e.get("uuid") and e.get("name"):
                    names[e["uuid"]] = e["name"]  # usercache pisa: nombre exacto real
        except Exception:
            pass
        pw = open(os.path.join(HERE, "rcon_pw.txt")).read().strip()
        lives = state.get("lives", {})
        eliminated = set(state.get("eliminated", []))
        cmds = []
        for uid, name in names.items():
            n = 0 if uid in eliminated else lives.get(uid, 3)
            if _lives_cache.get(name) != n:
                cmds.append((name, n))
        def run():
            for name, n in cmds:
                rconmod.rcon(f"scoreboard players set {name} vidas {n}", pw)
                # el numero se disfraza de corazones (☠ para los espermabaneados)
                icon = "☠" if n <= 0 else "❤" * n
                color = "dark_gray" if n <= 0 else "red"
                rconmod.rcon(
                    'scoreboard players display numberformat %s vidas fixed {"text":"%s","color":"%s"}'
                    % (name, icon, color), pw)
                _lives_cache[name] = n
        if cmds:
            await asyncio.to_thread(run)
    except Exception as e:
        print(f"[gaturro] update de vidas fallo: {e}")


VIDEO_INBOX = "/root/gaturro-render/inbox"

async def video_inbox_loop():
    """Publica los MP4 que el PC del admin va subiendo al inbox."""
    while True:
        try:
            if os.path.isdir(VIDEO_INBOX):
                for f in sorted(os.listdir(VIDEO_INBOX)):
                    if not f.endswith(".mp4"):
                        continue
                    mp4 = os.path.join(VIDEO_INBOX, f)
                    meta_p = mp4[:-4] + ".json"
                    if not os.path.exists(meta_p):
                        continue  # aun subiendose
                    meta = json.load(open(meta_p))
                    player = meta.get("player", "?")
                    import hashlib
                    with open(mp4, "rb") as fh:
                        md5 = hashlib.md5(fh.read()).hexdigest()
                    print(f"[gaturro] video {f}: md5={md5} size={os.path.getsize(mp4)}")
                    ch_id = CONFIG.get("channels", {}).get("deaths")
                    channel = client.get_channel(int(ch_id)) or await client.fetch_channel(int(ch_id))
                    size = os.path.getsize(mp4)
                    embed = discord.Embed(
                        title=f"\U0001F3A5 POV: la muerte de {player}",
                        description="Su ultimo minuto, visto por sus propios ojos.",
                        color=0xFF0066)
                    embed.set_footer(text=f"Dia {meta.get('day', '?')} de EspermaDeath")
                    if size <= 9 * 1024 * 1024:
                        await channel.send(embed=embed, file=discord.File(mp4, filename=f"{player}_pov.mp4"))
                    else:
                        web_dir = CONFIG.get("web_clips_dir", "/usr/share/nginx/api/gaturro/clips")
                        web_url = CONFIG.get("web_clips_url", "https://api.w4ve.xyz/v1/gaturro/clips")
                        os.makedirs(web_dir, exist_ok=True)
                        shutil.move(mp4, os.path.join(web_dir, f))
                        embed.description += f"\n\n[Ver video ({size // 1048576}MB)]({web_url}/{f})"
                        await channel.send(embed=embed)
                    print(f"[gaturro] video POV de {player} publicado")
                    for x in (mp4, meta_p):
                        try:
                            os.remove(x)
                        except Exception:
                            pass
        except Exception as e:
            print(f"[gaturro] video inbox fallo: {e}")
        await asyncio.sleep(30)


async def lives_loop():
    while True:
        await update_lives_scores()
        await asyncio.sleep(30)


async def handle_event(request: web.Request) -> web.Response:
    # Autenticacion simple por secreto compartido con el mod
    if request.headers.get("X-Gaturro-Secret", "") != CONFIG.get("secret", ""):
        print("[gaturro] RECHAZADO: secret no coincide con el del mod")
        return web.Response(status=403)
    try:
        data = await request.json()
    except Exception:
        return web.Response(status=400)

    etype = data.get("type")
    print(f"[gaturro] evento recibido: {etype} {data}")
    if etype in ("death", "eliminated", "heart_used"):
        asyncio.create_task(update_lives_scores())  # tab al dia sin esperar al ciclo
    if etype in ("chat", "join", "leave"):
        asyncio.create_task(bridge_to_discord(etype, data))
        return web.Response(status=204)
    if etype == "death_replay":
        asyncio.create_task(find_and_post_replay(data))
        return web.Response(status=204)
    tpl = TEMPLATES.get(etype)
    if not tpl:
        print(f"[gaturro] '{etype}' no tiene plantilla en templates.json: lo ignoro")
        return web.Response(status=204)  # tipo sin plantilla: se ignora

    ch_key = tpl.get("channel", "announcements")
    ch_id = CONFIG.get("channels", {}).get(ch_key)
    channel = client.get_channel(int(ch_id)) if ch_id else None
    if channel is None and ch_id:
        # get_channel solo mira la cache; si el bot acaba de arrancar puede no estar.
        try:
            channel = await client.fetch_channel(int(ch_id))
        except Exception as e:
            print(f"[gaturro] no pude traer el canal {ch_id}: {e}")
    if channel is None:
        print(f"[gaturro] canal '{ch_key}' ({ch_id}) no configurado o no encontrado")
        return web.Response(status=204)

    embed = discord.Embed(
        title=render(tpl.get("title", etype), data),
        description=render(tpl.get("description", ""), data),
        color=int(str(tpl.get("color", "0xFF0000")), 16),
    )
    if tpl.get("footer"):
        embed.set_footer(text=render(tpl["footer"], data))

    content = None
    role_key = tpl.get("ping_role")
    if role_key:
        role_id = CONFIG.get("roles", {}).get(role_key)
        if role_id:
            content = f"<@&{role_id}>"

    try:
        await channel.send(content=content, embed=embed, file=clip_file(data),
                           allowed_mentions=discord.AllowedMentions(roles=True))
        print(f"[gaturro] publicado '{etype}' en #{getattr(channel, 'name', ch_id)}")
    except Exception as e:
        print(f"[gaturro] ERROR enviando a Discord: {type(e).__name__}: {e}")
        return web.Response(status=500)
    return web.Response(status=204)


async def start_http():
    app = web.Application()
    app.router.add_post("/gaturro", handle_event)
    runner = web.AppRunner(app)
    await runner.setup()
    site = web.TCPSite(runner,
                       CONFIG.get("listen_host", "0.0.0.0"),
                       CONFIG.get("listen_port", 8099))
    await site.start()
    print(f"[gaturro] escuchando eventos del mod en "
          f"{CONFIG.get('listen_host', '0.0.0.0')}:{CONFIG.get('listen_port', 8099)}/gaturro")


@client.event
async def on_ready():
    print(f"[gaturro] bot conectado como {client.user}")
    activity = discord.Game(name=CONFIG.get("status", "EspermaDeath"))
    await client.change_presence(activity=activity)
    await start_http()
    asyncio.create_task(tail_server_log())
    asyncio.create_task(lives_loop())
    asyncio.create_task(video_inbox_loop())


if __name__ == "__main__":
    client.run(CONFIG["token"])
