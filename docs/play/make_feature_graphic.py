"""Draws the Play Store feature graphic (1024x500) from the app icon's two rings.

Run: python docs/play/make_feature_graphic.py
Uses Pillow's bundled default font (Aileron, public domain), so no font licence is involved.
"""
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

W, H = 1024, 500
TEAL = (0, 105, 107)
TEAL_DARK = (0, 74, 76)
WHITE = (255, 255, 255)
MINT = (178, 235, 235)

img = Image.new("RGB", (W, H), TEAL)
draw = ImageDraw.Draw(img)

# Soft diagonal band for depth.
for x in range(W):
    shade = int(18 * x / W)
    draw.line([(x, 0), (x, H)], fill=(TEAL[0], TEAL[1] - shade, TEAL[2] - shade))

# The icon's two interlocking rings, large, on the left.
cx, cy, r, w = 250, 250, 105, 26
draw.ellipse([cx - 70 - r, cy - r, cx - 70 + r, cy + r], outline=WHITE, width=w)
draw.ellipse([cx + 70 - r, cy - r, cx + 70 + r, cy + r], outline=MINT, width=w)

title = ImageFont.load_default(size=96)
tagline = ImageFont.load_default(size=38)
small = ImageFont.load_default(size=26)
draw.text((520, 140), "Pairloom", font=title, fill=WHITE)
draw.text((524, 262), "Talk between two phones", font=tagline, fill=WHITE)
draw.text((524, 310), "over Wi-Fi Direct", font=tagline, fill=WHITE)
draw.text((524, 380), "No internet. No accounts. Encrypted.", font=small, fill=MINT)

out = Path(__file__).with_name("feature-graphic.png")
img.save(out)
print(out, img.size)
