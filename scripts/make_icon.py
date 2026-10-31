# -*- coding: utf-8 -*-
"""WiFiADB 启动图标生成：白底圆角方块 + 青→蓝渐变 Wi-Fi 扇形（信号弧+圆点）。
4x 超采样抗锯齿，输出 5 个密度 ic_launcher / ic_launcher_round。"""
from PIL import Image, ImageDraw
import math, os

RES = r"G:\Projects\wifiadb-app\app\src\main\res"
S = 512            # 逻辑画布
SS = 4             # 超采样倍数
C = S * SS

# ---- 配色（与 TTS 图标的紫蓝区分：青绿 → 蓝）----
GRAD_TOP = (32, 199, 187)     # 青 teal
GRAD_BOT = (37, 99, 235)      # 蓝 blue-600
BG = (255, 255, 255, 255)
BORDER = (226, 232, 240, 255) # 浅灰描边

def arc_stroke(draw, cx, cy, radius, a0, a1, width):
    """用密集圆点描出带圆头的弧。角度为 Pillow 体系（0=三点钟方向，顺时针）。"""
    r_px = radius * SS
    w = width * SS
    n = max(8, int(abs(a1 - a0) / 2))
    pts = []
    for i in range(n + 1):
        a = math.radians(a0 + (a1 - a0) * i / n)
        pts.append((cx + r_px * math.cos(a), cy + r_px * math.sin(a)))
    for (x, y) in pts:
        draw.ellipse([x - w/2, y - w/2, x + w/2, y + w/2], fill=255)

def glyph_mask():
    """Wi-Fi 扇形：圆点 + 三道弧，整体位于 512 逻辑坐标。返回 L 模式蒙版。"""
    m = Image.new("L", (C, C), 0)
    d = ImageDraw.Draw(m)
    cx, cy = 256, 348
    dot_r, arc_w = 36, 38
    # 圆点
    r = dot_r * SS
    d.ellipse([cx*SS - r, cy*SS - r, cx*SS + r, cy*SS + r], fill=255)
    # 三道弧（开口向上：210°→330° 经过正上方 270°）
    for radius in (92, 150, 208):
        arc_stroke(d, cx*SS, cy*SS, radius, 210, 330, arc_w)
    return m

def gradient_glyph(mask):
    """把青→蓝竖向渐变穿过蒙版，返回 RGBA。"""
    bbox = mask.getbbox()
    top, bot = bbox[1], bbox[3]
    grad = Image.new("RGBA", (C, C))
    px = grad.load()
    for y in range(top, bot + 1):
        t = (y - top) / max(1, bot - top)
        r = int(GRAD_TOP[0] + (GRAD_BOT[0] - GRAD_TOP[0]) * t)
        g = int(GRAD_TOP[1] + (GRAD_BOT[1] - GRAD_TOP[1]) * t)
        b = int(GRAD_TOP[2] + (GRAD_BOT[2] - GRAD_TOP[2]) * t)
        for x in range(C):
            px[x, y] = (r, g, b, 255)
    out = Image.new("RGBA", (C, C), (0, 0, 0, 0))
    out.paste(grad, (0, 0), mask)
    return out

def rounded_bg():
    img = Image.new("RGBA", (C, C), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    rad = int(115 * SS)
    d.rounded_rectangle([0, 0, C - 1, C - 1], radius=rad, fill=BG)
    d.rounded_rectangle([0, 0, C - 1, C - 1], radius=rad, outline=BORDER, width=3 * SS)
    return img

def circle_bg():
    img = Image.new("RGBA", (C, C), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.ellipse([0, 0, C - 1, C - 1], fill=BG)
    d.ellipse([0, 0, C - 1, C - 1], outline=BORDER, width=3 * SS)
    return img

glyph = gradient_glyph(glyph_mask())

def compose(bg):
    img = bg.copy()
    img.alpha_composite(glyph)
    return img.resize((S, S), Image.LANCZOS)

DENSITIES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
square, circle = compose(rounded_bg()), compose(circle_bg())

for dpi, size in DENSITIES.items():
    d = os.path.join(RES, f"mipmap-{dpi}")
    square.resize((size, size), Image.LANCZOS).save(os.path.join(d, "ic_launcher.png"))
    circle.resize((size, size), Image.LANCZOS).save(os.path.join(d, "ic_launcher_round.png"))
    print(f"mipmap-{dpi}: {size}x{size} OK")

# 预览图（给用户看）
prev = Image.new("RGBA", (S * 2 + 24, S + 16), (248, 250, 252, 255))
prev.paste(square, (8, 8), square)
prev.paste(circle, (S + 16, 8), circle)
prev.save(os.path.join(os.environ["TEMP"], "wifiadb_icon_preview.png"))
print("预览: %TEMP%\\wifiadb_icon_preview.png")
