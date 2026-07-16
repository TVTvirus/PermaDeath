#!/usr/bin/env python3
"""
Crea la estructura de canales de EspermaDeath en Discord.
One-shot: se conecta, crea los canales con permisos y sale imprimiendo los IDs.

  python3 crear_canales.py <categoria_o_guild_id> <rol_id>

Permisos:
  - Canales de solo lectura (anuncios, muertes, info): todos leen, solo el BOT escribe.
  - Canales de chat (chat, chat-minecraft): todos leen, solo el ROL escribe.
"""

import json
import os
import sys
import discord

HERE = os.path.dirname(os.path.abspath(__file__))
CONFIG = json.load(open(os.path.join(HERE, "config.json")))

TARGET_ID = int(sys.argv[1])
ROLE_ID = int(sys.argv[2])

intents = discord.Intents.default()
client = discord.Client(intents=intents)

# (nombre, tipo)  tipo: "botonly" | "chat"
CANALES = [
    ("info",           "botonly"),
    ("anuncios",       "botonly"),
    ("muertes",        "botonly"),
    ("chat",           "chat"),
    ("chat-minecraft", "chat"),
]


@client.event
async def on_ready():
    try:
        # localizar guild + categoria
        category = None
        guild = None
        ch = client.get_channel(TARGET_ID)
        if isinstance(ch, discord.CategoryChannel):
            category = ch
            guild = ch.guild
        else:
            guild = client.get_guild(TARGET_ID)
        if guild is None:
            print("ERROR: no encuentro el guild/categoria", TARGET_ID,
                  "(el bot esta en ese server?)")
            await client.close(); return

        role = guild.get_role(ROLE_ID)
        me = guild.me
        everyone = guild.default_role
        if category is None:
            category = await guild.create_category("EspermaDeath")
            print("categoria creada:", category.id)

        results = {}
        for name, kind in CANALES:
            ow = {
                everyone: discord.PermissionOverwrite(view_channel=True, send_messages=False),
                me: discord.PermissionOverwrite(view_channel=True, send_messages=True),
            }
            if kind == "chat" and role is not None:
                ow[role] = discord.PermissionOverwrite(view_channel=True, send_messages=True)
            channel = await guild.create_text_channel(name, category=category, overwrites=ow)
            results[name] = channel.id
            print(f"  canal '{name}' -> {channel.id}")

        print("\nIDS_JSON=" + json.dumps(results))
    except discord.Forbidden:
        print("ERROR: el bot no tiene permiso 'Manage Channels'/'Manage Roles' en el server.")
    except Exception as e:
        print("ERROR:", type(e).__name__, e)
    await client.close()


client.run(CONFIG["token"])
