"""Генерирует все ресурсы иконки vtext (adaptive + legacy)."""
from PIL import Image, ImageDraw
import os

CREAM = (250, 246, 238, 255)
PAPER = (233, 224, 208, 255)
TEAL = (31, 90, 84, 255)
BRASS = (217, 168, 92, 255)

BASE = 1728
UNIT = BASE / 108.0
SAFE_SCALE = 0.92


def px(x, y):
    return (x * UNIT, y * UNIT)


def poly(d, points, fill):
    d.polygon([px(x, y) for x, y in points], fill=fill)


def draw_art() -> Image.Image:
    img = Image.new("RGBA", (BASE, BASE), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)

    # страницы открытой книги (наклон к центру)
    poly(d, [(52, 86), (15, 72), (15, 36), (52, 50)], PAPER)
    poly(d, [(56, 86), (93, 72), (93, 36), (56, 50)], CREAM)
    # корешок
    poly(d, [(52, 86), (56, 86), (56, 50), (52, 50)], TEAL)

    # play-бейдж
    d.ellipse([px(44.5, 49.5), px(63.5, 68.5)], fill=BRASS)
    d.polygon([px(51.5, 55), px(51.5, 63), px(59, 59)], fill=TEAL)
    return img


def draw_foreground(size: int) -> Image.Image:
    """Прозрачный слой 108×108 dp, арт с запасом под adaptive-маску."""
    art = draw_art()
    scaled = art.resize((int(BASE * SAFE_SCALE),) * 2, Image.LANCZOS)
    canvas = Image.new("RGBA", (BASE, BASE), (0, 0, 0, 0))
    canvas.alpha_composite(
        scaled,
        ((BASE - scaled.width) // 2, (BASE - scaled.height) // 2),
    )
    return canvas.resize((size, size), Image.LANCZOS)


def draw_legacy(size: int, round_mask: bool) -> Image.Image:
    """Иконка до Android 8: плитка с фоном и запечённой маской."""
    tile = Image.new("RGBA", (BASE, BASE), TEAL)
    tile.alpha_composite(draw_foreground(BASE))
    mask = Image.new("L", (BASE, BASE), 0)
    md = ImageDraw.Draw(mask)
    if round_mask:
        md.ellipse([0, 0, BASE - 1, BASE - 1], fill=255)
    else:
        md.rounded_rectangle([0, 0, BASE - 1, BASE - 1], radius=int(BASE * 0.22), fill=255)
    tile.putalpha(mask)
    return tile.resize((size, size), Image.LANCZOS)


DENSITIES = {
    "mdpi": 48,
    "hdpi": 72,
    "xhdpi": 96,
    "xxhdpi": 144,
    "xxxhdpi": 192,
}

RES = "app/src/main/res"


def main() -> None:
    os.makedirs(f"{RES}/drawable-nodpi", exist_ok=True)
    draw_foreground(432).save(f"{RES}/drawable-nodpi/ic_launcher_foreground.png")

    for density, size in DENSITIES.items():
        folder = f"{RES}/mipmap-{density}"
        os.makedirs(folder, exist_ok=True)
        draw_legacy(size, round_mask=False).save(f"{folder}/ic_launcher.png")
        draw_legacy(size, round_mask=True).save(f"{folder}/ic_launcher_round.png")

    # превью для проверки
    os.makedirs("build/icon", exist_ok=True)
    fg = draw_foreground(420)
    canvas = Image.new("RGBA", (420, 420), TEAL)
    canvas.alpha_composite(fg)
    canvas.save("build/icon/final_preview.png")
    draw_legacy(96, round_mask=True).save("build/icon/final_small.png")
    print("ok")


if __name__ == "__main__":
    main()
