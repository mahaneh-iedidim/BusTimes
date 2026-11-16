"""mkicon.py <out.png> <preview.png>: the 48x48 icon: an orange rounded square with a white bus front."""
import sys
from PIL import Image, ImageDraw
S, K = 48, 4
ORANGE, DEEP, WHITE, BLACK = (255, 106, 19), (217, 83, 10), (255, 255, 255), (22, 22, 22)
out, prev = sys.argv[1:3]
big = Image.new('RGBA', (S * K, S * K), (0, 0, 0, 0))
d = ImageDraw.Draw(big)
P = lambda *v: [x * K for x in v]
d.rounded_rectangle(P(0, 0, S - 1, S - 1), radius=10 * K, fill=ORANGE)
d.rounded_rectangle(P(11, 8, 37, 38), radius=5 * K, fill=WHITE)
d.rounded_rectangle(P(14, 12, 34, 24), radius=2 * K, fill=ORANGE)
d.rectangle(P(17, 9, 31, 10.5), fill=DEEP)
d.ellipse(P(14, 29, 18, 33), fill=DEEP)
d.ellipse(P(30, 29, 34, 33), fill=DEEP)
d.rounded_rectangle(P(13, 37, 18, 42), radius=1 * K, fill=BLACK)
d.rounded_rectangle(P(30, 37, 35, 42), radius=1 * K, fill=BLACK)
im = big.resize((S, S), Image.LANCZOS)
im.save(out)
im.resize((S * 6, S * 6), Image.NEAREST).save(prev)
print('icon', out)
