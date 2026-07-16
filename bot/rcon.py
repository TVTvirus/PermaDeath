#!/usr/bin/env python3
"""RCON minimo para el server gaturro (desde el host a la red docker)."""
import socket, struct, sys, subprocess

def container_ip():
    out = subprocess.check_output(['docker','inspect','93fdfb3f-6d0b-4cdd-8303-16a9a323b8b0',
        '--format','{{(index .NetworkSettings.Networks "pterodactyl_nw").IPAddress}}']).decode().strip()
    return out

def rcon(cmd, pw):
    s = socket.create_connection((container_ip(), 25575), timeout=8)
    def send(t, body):
        raw = body.encode('utf-8')
        s.sendall(struct.pack('<iii', 10 + len(raw), 1, t) + raw + b'\x00\x00')
    def recvexact(n):
        d = b''
        while len(d) < n:
            part = s.recv(n - len(d))
            if not part:
                raise ConnectionError("rcon: conexion cerrada por el server")
            d += part
        return d
    def recv():
        ln = struct.unpack('<i', recvexact(4))[0]
        d = recvexact(ln)
        rid, t = struct.unpack('<ii', d[:8])
        return rid, d[8:-2].decode('utf-8', errors='replace')
    send(3, pw)
    rid, _ = recv()
    if rid == -1:
        s.close()
        return 'AUTH FAIL'
    send(2, cmd)
    _, out = recv()
    s.close()
    return out

if __name__ == '__main__':
    pw = open('/root/gaturro-bot/rcon_pw.txt').read().strip()
    print(rcon(' '.join(sys.argv[1:]), pw))
