#!/bin/bash
# Reset TOTAL del Mundo Gaturro: mundo de 0 (seed fija de server.properties),
# preserva el puente a Discord, limpia clips/replays viejos y recrea los equipos.
set -e
V=/var/lib/pterodactyl/volumes/93fdfb3f-6d0b-4cdd-8303-16a9a323b8b0
C=93fdfb3f-6d0b-4cdd-8303-16a9a323b8b0
P=/root/gaturro-bot/rcon.py

echo "[1/6] online ahora: $(python3 $P list 2>/dev/null || echo server apagado)"
echo "[2/6] parando..."
docker stop $C >/dev/null

echo "[3/6] wipe del mundo (preservando gaturro_discord.json)..."
cp $V/world/gaturro_discord.json /tmp/gaturro_discord.json
rm -rf $V/world
mkdir -p $V/world
cp /tmp/gaturro_discord.json $V/world/
rm -rf $V/recordings/players/* /usr/share/nginx/api/gaturro/clips/*
chown -R 994:993 $V/world

echo "[4/6] arrancando..."
docker start $C >/dev/null
for i in $(seq 1 30); do sleep 3; python3 $P list >/dev/null 2>&1 && break; done

echo "[5/6] recreando equipos..."
declare -A NAMES=( [PMR]="Pezones MUY Rabiosos" [CHP]="Chavalines pro" [GF]="Grupo Fimosis" [MOMO]="momo" [ITM]="ITM bois" [FUR]="Las furritas peluditas" [MYM]="Mediocres y Meneitos" )
declare -A COLORS=( [PMR]=red [CHP]=gold [GF]=green [MOMO]=blue [ITM]=aqua [FUR]=light_purple [MYM]=yellow )
for t in PMR CHP GF MOMO ITM FUR MYM; do
  python3 $P "team add $t" >/dev/null
  python3 $P "team modify $t displayName {\"text\":\"${NAMES[$t]}\"}" >/dev/null
  python3 $P "team modify $t prefix {\"text\":\"[$t] \",\"color\":\"${COLORS[$t]}\",\"bold\":true}" >/dev/null
  python3 $P "team modify $t color ${COLORS[$t]}" >/dev/null
  python3 $P "team modify $t friendlyFire false" >/dev/null
done
for n in TVTvirus Hugolol89 Chonete; do python3 $P "team join PMR $n" >/dev/null; done

echo "[6/6] estado:"
python3 $P "pdc status"
python3 $P "team list"
