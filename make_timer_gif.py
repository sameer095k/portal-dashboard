from PIL import Image, ImageDraw, ImageFont
W, H = 1200, 675
BG = (16, 18, 22)        # dashboard dark
GOLD = (232, 184, 75)    # dashboard gold
SOFT = (150, 155, 165)
font_big = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf", 340)
font_small = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", 44)
frames, durations = [], []
def centered(d, text, font, y, fill):
    bbox = d.textbbox((0, 0), text, font=font)
    tw = bbox[2] - bbox[0]
    d.text(((W - tw) / 2 - bbox[0], y), text, font=font, fill=fill)
for n in range(10, 0, -1):
    im = Image.new("RGB", (W, H), BG)
    d = ImageDraw.Draw(im)
    centered(d, str(n), font_big, 110, GOLD)
    # progress bar: shrinks as time runs out
    bar_w = int((W - 160) * n / 10)
    d.rounded_rectangle([80, H - 90, 80 + bar_w, H - 66], radius=12, fill=GOLD)
    frames.append(im); durations.append(1000)
im = Image.new("RGB", (W, H), BG)
d = ImageDraw.Draw(im)
centered(d, "0", font_big, 110, GOLD)
centered(d, "Time's up", font_small, H - 170, SOFT)
frames.append(im); durations.append(3000)
frames[0].save("/tmp/timer10.gif", save_all=True, append_images=frames[1:],
               duration=durations, loop=0)
print("gif written")
