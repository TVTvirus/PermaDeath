#!/bin/bash
# Arranca/reinicia el bot. Usa pidfile: NUNCA pkill -f "bot.py" por SSH
# (el patron matchea la propia cmdline del comando remoto y mata la sesion).
cd /root/gaturro-bot
PIDFILE=/root/gaturro-bot/bot.pid
if [ -f "$PIDFILE" ] && kill -0 "$(cat $PIDFILE)" 2>/dev/null; then
    kill "$(cat $PIDFILE)"; sleep 2
fi
setsid nohup venv/bin/python bot.py > bot.log 2>&1 < /dev/null &
echo $! > "$PIDFILE"
sleep 8
if kill -0 "$(cat $PIDFILE)" 2>/dev/null; then echo "bot arriba (pid $(cat $PIDFILE))"; else echo "BOT NO ARRANCO"; tail -5 bot.log; fi
