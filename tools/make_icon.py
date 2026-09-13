# -*- coding: utf-8 -*-
"""mcef-codec mod icon: 32x32 pixel art, scaled up 16x for Modrinth (512x512).

Design: the same embossed screen frame as doomscroll (same family), holding a dark film
strip (holes on both sides) and a blue play triangle, with a blue glow around it. Deliberately
different from doomscroll's orange-purple gradient: this is a library, a "codec".

pip install pillow
"""
import os

from PIL import Image

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                   "..", "src", "main", "resources", "assets", "mcef-codec", "icon.png")

N = 32
SCALE = 16

K = (11, 11, 15, 255)        # outer outline
D = (30, 30, 37, 255)        # frame shadow
F = (48, 48, 57, 255)        # frame
H = (78, 78, 92, 255)        # frame highlight
B = (20, 20, 25, 255)        # bottom bar
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
    """Glow in rings outside the box; corners stay empty, the last ring is thinned out in a checkerboard pattern."""
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
    """Outer outline + 1 px embossed frame + screen outline (3 px in total); returns the screen area."""
    rect(px, x0, y0, x1, y1, K)
    rect(px, x0 + 1, y0 + 1, x1 - 1, y1 - 1, F)
    rect(px, x0 + 1, y0 + 1, x1 - 2, y0 + 1, H)      # top highlight
    rect(px, x0 + 1, y0 + 1, x0 + 1, y1 - 2, H)      # left highlight
    rect(px, x0 + 1, y1 - 1, x1 - 1, y1 - 1, D)      # bottom shadow
    rect(px, x1 - 1, y0 + 1, x1 - 1, y1 - 1, D)      # right shadow
    rect(px, x0 + 2, y0 + 2, x1 - 2, y1 - 2, K)      # screen outline
    for (x, y) in ((x0, y0), (x1, y0), (x0, y1), (x1, y1)):
        put(px, x, y, (0, 0, 0, 0))
    return x0 + 3, y0 + 3, x1 - 3, y1 - 3


def stand(px, x0, y1, x1):
    cx = (x0 + x1) // 2
    rect(px, cx - 3, y1 + 1, cx + 2, y1 + 2, K)
    rect(px, cx - 2, y1 + 1, cx + 1, y1 + 2, D)
    rect(px, cx - 6, y1 + 3, cx + 5, y1 + 4, K)
    rect(px, cx - 5, y1 + 3, cx + 4, y1 + 3, F)
    rect(px, cx - 5, y1 + 4, cx + 4, y1 + 4, D)


def play(px, cx, cy, size, color, outline, aspect=0.58):
    """Right-pointing triangle; the outline is the fill grown by 1 px in every direction."""
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
    # body 26x22 with a 4-row stand below it: 26 rows in total, vertically centered on the 32 px canvas
    X0, Y0, X1, Y1 = 3, 5, 28, 26
    glow(px, X0, Y0, X1, Y1, [(120, False), (70, False), (36, True)])
    sx0, sy0, sx1, sy1 = frame(px, X0, Y0, X1, Y1)        # screen 20x16
    rect(px, sx0, sy0, sx1, sy1 - 1, FILM)
    rect(px, sx0 + 3, sy0, sx1 - 3, sy1 - 1, FILM_MID)
    for y in range(sy0 + 1, sy1 - 1, 3):
        rect(px, sx0 + 1, y, sx0 + 1, y + 1, HOLE)
        rect(px, sx1 - 1, y, sx1 - 1, y + 1, HOLE)
    rect(px, sx0, sy1, sx1, sy1, B)                       # single-row bottom bar
    rect(px, sx1 - 2, sy1, sx1 - 1, sy1, BLUE + (255,))
    play(px, (sx0 + sx1) // 2 + 1, (sy0 + sy1 - 1) // 2, 7, PLAY, K, aspect=0.6)
    stand(px, X0, Y1, X1)
    return im


if __name__ == "__main__":
    big = draw().resize((N * SCALE, N * SCALE), Image.NEAREST)
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    big.save(OUT)
    print("wrote", os.path.normpath(OUT), big.size)
