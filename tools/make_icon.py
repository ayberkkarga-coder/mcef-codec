"""mcef-codec mod simgesi: 16x16 piksel sanati, Modrinth icin 32x buyutulur (512x512).

Tasarim: koyu bir govde icinde yesil bir "kodek" penceresi; solda film seridi delikleri,
ortada oynat ucgeni. doomscroll'un beyaz ucgen / mavi ekran simgesinden bilerek farkli.
"""
import os
from PIL import Image  # pip install pillow

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                   "..", "src", "main", "resources", "assets", "mcef-codec")

PAL = {
    ".": None,
    "K": (11, 11, 14, 255),      # kontur
    "D": (30, 30, 35, 255),      # koyu govde / golge
    "B": (44, 44, 51, 255),      # govde
    "L": (61, 61, 70, 255),      # acik govde
    "H": (89, 89, 99, 255),      # vurgu
    "S": (7, 8, 12, 255),        # pencere cami
    "s": (16, 22, 18, 255),      # cam ust ton (hafif yesil)
    "E": (79, 191, 107, 255),    # yesil
    "e": (39, 110, 58, 255),     # koyu yesil
    "W": (232, 232, 238, 255),   # beyaz
}

ICON = [
    "................",
    ".KKKKKKKKKKKKKK.",
    "KHLLLLLLLLLLLLBK",
    "KLWDSsssssssDWDK",
    "KLDDSssEEsssDDDK",
    "KLWDSssEEEsssWDK",
    "KLDDSssEEEEssDDK",
    "KLWDSssEEEEEsWDK",
    "KLDDSssEEEEssDDK",
    "KLWDSssEEEsssWDK",
    "KLDDSssEEsssDDDK",
    "KLWDSsssssssWDDK",
    "KLBBBBBBBBBBBBDK",
    "KBDDDeEEEeDDDDDK",
    ".KKKKKKKKKKKKKK.",
    "....KKKKKKKK....",
]


def paint(rows, path, scale=1):
    h, w = len(rows), len(rows[0])
    im = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    px = im.load()
    for y, row in enumerate(rows):
        assert len(row) == w, "satir genisligi: satir " + str(y)
        for x, ch in enumerate(row):
            c = PAL[ch]
            if c is not None:
                px[x, y] = c
    if scale != 1:
        im = im.resize((w * scale, h * scale), Image.NEAREST)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    im.save(path)
    print("yazildi", os.path.basename(path), im.size)


paint(ICON, os.path.join(OUT, "icon.png"), scale=32)
print("tamam")
