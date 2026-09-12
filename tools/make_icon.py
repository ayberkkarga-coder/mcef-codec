# -*- coding: utf-8 -*-
"""mcef-codec mod simgesi: 32x32 piksel sanati, Modrinth icin 16x buyutulur (512x512).

Tasarim: doomscroll ile ayni kabartmali ekran cercevesi (ayni aile), icinde koyu bir film
seridi (iki yanda delikler) ve mavi oynat ucgeni, cevresinde mavi parlama. doomscroll'un
turuncu-mor gradyanindan bilerek farkli: bu bir kutuphane, bir "kodek".

pip install pillow
"""
import os

from PIL import Image

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                   "..", "src", "main", "resources", "assets", "mcef-codec", "icon.png")

N = 32
SCALE = 16

K = (11, 11, 15, 255)        # dis kontur
D = (30, 30, 37, 255)        # cerceve golge
F = (48, 48, 57, 255)        # cerceve
H = (78, 78, 92, 255)        # cerceve isik
B = (20, 20, 25, 255)        # alt bant
BLUE = (70, 175, 255)
PLAY = (110, 200, 255, 255)
FILM = (12, 18, 26, 255)
FILM_MID = (18, 30, 44, 255)
HOLE = (236, 240, 248, 255)


def put(px, x, y, c):
    if 0 <= x < N and 0 <= y < N:
        px[x, y] = c


def rect(px, x0, y0, x1, y1, c):
    for y in range(y0, y1 + 1):
        for x in range(x0, x1 + 1):
            put(px, x, y, c)


def glow(px, x0, y0, x1, y1, rings):
    """Kutunun disina halka halka parlama; koseler bos, son halka dama deseniyle seyrek."""
    for i, (alpha, dither) in enumerate(rings, start=1):
        for y in range(y0 - i, y1 + i + 1):
            for x in range(x0 - i, x1 + i + 1):
                if x0 - i + 1 <= x <= x1 + i - 1 and y0 - i + 1 <= y <= y1 + i - 1:
                    continue
                cx = x < x0 - i + 1 or x > x1 + i - 1
                cy = y < y0 - i + 1 or y > y1 + i - 1
                if cx and cy:
                    continue
                if dither and (x + y) % 2:
                    continue
                put(px, x, y, BLUE + (alpha,))


def frame(px, x0, y0, x1, y1):
    """Dis kontur + 2 px kabartmali cerceve + ekran konturu; ekran alanini dondurur."""
    rect(px, x0, y0, x1, y1, K)
    rect(px, x0 + 1, y0 + 1, x1 - 1, y1 - 1, F)
    rect(px, x0 + 1, y0 + 1, x1 - 2, y0 + 1, H)
    rect(px, x0 + 1, y0 + 1, x0 + 1, y1 - 2, H)
    rect(px, x0 + 1, y1 - 1, x1 - 1, y1 - 1, D)
    rect(px, x1 - 1, y0 + 1, x1 - 1, y1 - 1, D)
    rect(px, x0 + 2, y0 + 2, x1 - 2, y1 - 2, F)
    rect(px, x0 + 3, y0 + 3, x1 - 3, y1 - 3, K)
    for (x, y) in ((x0, y0), (x1, y0), (x0, y1), (x1, y1)):
        put(px, x, y, (0, 0, 0, 0))
    return x0 + 4, y0 + 4, x1 - 4, y1 - 4


def stand(px, x0, y1, x1):
    cx = (x0 + x1) // 2
    rect(px, cx - 3, y1 + 1, cx + 2, y1 + 2, K)
    rect(px, cx - 2, y1 + 1, cx + 1, y1 + 2, D)
    rect(px, cx - 7, y1 + 3, cx + 6, y1 + 4, K)
    rect(px, cx - 6, y1 + 3, cx + 5, y1 + 3, F)
    rect(px, cx - 6, y1 + 4, cx + 5, y1 + 4, D)


def play(px, cx, cy, size, color, outline, aspect=0.58):
    """Saga bakan ucgen; kontur dolgunun her yone 1 px genisletilmisi."""
    pts = []
    for col in range(size + 1):
        h = round(size * aspect * (size - col) / size)
        for y in range(cy - h, cy + h + 1):
            pts.append((cx - size // 2 + col, y))
    for x, y in pts:
        for dx in (-1, 0, 1):
            for dy in (-1, 0, 1):
                put(px, x + dx, y + dy, outline)
    for x, y in pts:
        put(px, x, y, color)


def draw():
    im = Image.new("RGBA", (N, N), (0, 0, 0, 0))
    px = im.load()
    X0, Y0, X1, Y1 = 3, 3, 28, 24
    glow(px, X0, Y0, X1, Y1, [(120, False), (70, False), (36, True)])
    sx0, sy0, sx1, sy1 = frame(px, X0, Y0, X1, Y1)
    rect(px, sx0, sy0, sx1, sy1 - 2, FILM)
    rect(px, sx0 + 3, sy0, sx1 - 3, sy1 - 2, FILM_MID)
    for y in range(sy0 + 1, sy1 - 2, 3):
        rect(px, sx0 + 1, y, sx0 + 1, y + 1, HOLE)
        rect(px, sx1 - 1, y, sx1 - 1, y + 1, HOLE)
    rect(px, sx0, sy1 - 1, sx1, sy1, B)
    rect(px, sx1 - 3, sy1 - 1, sx1 - 2, sy1, BLUE + (255,))
    play(px, (sx0 + sx1) // 2 + 1, (sy0 + sy1 - 2) // 2, 6, PLAY, K)
    stand(px, X0, Y1, X1)
    return im


if __name__ == "__main__":
    big = draw().resize((N * SCALE, N * SCALE), Image.NEAREST)
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    big.save(OUT)
    print("yazildi", os.path.normpath(OUT), big.size)
