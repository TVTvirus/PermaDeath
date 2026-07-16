#!/usr/bin/env python3
"""
Generador de imagenes de anuncio de EspermaDeath.

Fondo negro estilo terror. El CAMBIO (lo importante) va GRANDE; el resto, pequeno.
Puedes anadir imagenes relacionadas (armaduras, items, mobs) por fase:
pon PNGs en  relacionadas/  con el nombre  dia_07_*.png  y se pegan solos abajo.

    pip install pillow
    python3 generar_anuncios.py
"""

import glob
import os
import textwrap
from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "anuncios")
REL = os.path.join(HERE, "relacionadas")
HEAD = "/mnt/games/PrismLauncher/icons/gaturro.png"

W, H = 1200, 675
BG = (8, 6, 8)
RED = (205, 25, 25)
DARKRED = (110, 12, 12)
WHITE = (238, 233, 233)
GRAY = (140, 140, 140)

# ---- CALENDARIO (editable). Opcional 4o campo: subtitulo corto ----
FASES = [
    (1,  "Semivanilla",          "Arranca EspermaDeath.\n3 vidas. A disfrutar."),
    (3,  "Tormenta con dientes",  "Los bichos se ponen\nvalientes DURANTE\nla tormenta."),
    (5,  "Plaga",                 "El DOBLE de bichos.\nAranas con efectos."),
    (7,  "El End despierta",      "El End se ABRE.\nAlgo los espera\nadentro."),
    (9,  "Cada quien a lo suyo",  "Se acabo la ayudadera.\nSolo su equipo.\n3 para dormir."),
    (12, "Sin refugio",           "Los bichos ya no\nle temen al sol."),
    (15, "Totems traicioneros",   "Sus totems ahora\npueden FALLAR."),
    (18, "Desgaste",              "Pierden corazones\ny espacio.\nRecuperenlo con bichos."),
    (21, "Aracnofobia total",     "Las aranas traen un\nBUFFET de efectos."),
    (24, "Deslealtad",            "Los totems fallan\nEL DOBLE."),
    (27, "Los cielos caen",       "Phantoms GIGANTES.\nLos MUERTOS eligen\nel castigo."),
    (30, "El ultimo dia",         "Sobrevivan...\no mueran con estilo."),
]


def font(size, bold=True):
    paths = [
        "/usr/share/fonts/dejavu-sans-fonts/DejaVuSans-Bold.ttf",
        "/usr/share/fonts/dejavu/DejaVuSans-Bold.ttf",
        "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
        "/usr/share/fonts/liberation-sans-fonts/LiberationSans-Bold.ttf",
    ]
    if not bold:
        paths = [p.replace("-Bold", "") for p in paths]
    for p in paths:
        if os.path.exists(p):
            return ImageFont.truetype(p, size)
    return ImageFont.load_default()


def center(d, y, text, f, fill):
    w = d.textbbox((0, 0), text, font=f)[2]
    d.text(((W - w) / 2, y), text, font=f, fill=fill)


def make(dia, nombre, cambio):
    img = Image.new("RGB", (W, H), BG)
    d = ImageDraw.Draw(img)
    d.rectangle([12, 12, W - 12, H - 12], outline=DARKRED, width=4)

    # marca de agua Gaturro
    try:
        head = Image.open(HEAD).convert("RGBA").resize((300, 300))
        px = head.load()
        for x in range(head.width):
            for y in range(head.height):
                r, g, b, a = px[x, y]
                px[x, y] = (r, g, b, int(a * 0.14))
        img.paste(head, (W - 320, H - 320), head)
    except Exception:
        pass

    # --- cabecera PEQUENA (info secundaria) ---
    center(d, 34, "EspermaDeath · aviso 24h · Dia " + str(dia), font(26, bold=False), GRAY)
    center(d, 74, nombre.upper(), font(40), RED)

    # --- EL CAMBIO, GRANDE (protagonista) ---
    lines = cambio.split("\n")
    fbig = font(66)
    lh = 78
    total = len(lines) * lh
    y = 150 + (330 - total) // 2
    for ln in lines:
        center(d, y, ln, fbig, WHITE)
        y += lh

    # --- imagenes relacionadas (relacionadas/dia_XX_*.png) ---
    rel = sorted(glob.glob(os.path.join(REL, f"dia_{dia:02d}_*")))
    if rel:
        thumbs = []
        for r in rel[:5]:
            try:
                im = Image.open(r).convert("RGBA")
                ratio = 140 / im.height
                thumbs.append(im.resize((int(im.width * ratio), 140), Image.NEAREST))
            except Exception:
                pass
        if thumbs:
            gap = 20
            tw = sum(t.width for t in thumbs) + gap * (len(thumbs) - 1)
            x = (W - tw) // 2
            for t in thumbs:
                img.paste(t, (x, H - 175), t)
                x += t.width + gap

    os.makedirs(OUT, exist_ok=True)
    path = os.path.join(OUT, f"dia_{dia:02d}_{nombre.lower().replace(' ', '_')}.png")
    img.save(path)
    return path


if __name__ == "__main__":
    os.makedirs(REL, exist_ok=True)
    print("Generando anuncios de EspermaDeath...")
    for dia, nombre, cambio in FASES:
        print("  ->", os.path.basename(make(dia, nombre, cambio)))
    print(f"\nListo. {len(FASES)} imagenes en {OUT}/")
    print(f"Para imagenes relacionadas, pon PNGs en {REL}/ como  dia_07_netherite.png")
