"""Собирает иконки приложения из готовой квадратной картинки.

Пример:
    python tools/icon/make_photo_icon.py C:\\Users\\me\\Downloads\\icon.jpeg

Генерирует legacy-иконки (mipmap-*dpi), круглые иконки и foreground
для adaptive icon (drawable-nodpi). Цвет фона берётся из краёв картинки.
"""

import sys
from pathlib import Path

from PIL import Image, ImageChops, ImageDraw

ROOT = Path(__file__).resolve().parents[2]
RES = ROOT / 'app' / 'src' / 'main' / 'res'

# Плотность -> размер legacy-иконки в пикселях.
LEGACY = {
    'mipmap-mdpi': 48,
    'mipmap-hdpi': 72,
    'mipmap-xhdpi': 96,
    'mipmap-xxhdpi': 144,
    'mipmap-xxxhdpi': 192,
}

# Adaptive icon: canvas 108dp, картинка вписана почти целиком.
ADAPTIVE_CANVAS = 432
ADAPTIVE_IMAGE = 400


def edge_color(image: Image.Image) -> tuple[int, int, int]:
    small = image.resize((64, 64), Image.LANCZOS)
    pixels = small.load()
    total = [0, 0, 0]
    count = 0
    for x in range(64):
        for y in (0, 1, 62, 63):
            r, g, b = pixels[x, y]
            total[0] += r
            total[1] += g
            total[2] += b
            count += 1
    for y in range(64):
        for x in (0, 1, 62, 63):
            r, g, b = pixels[x, y]
            total[0] += r
            total[1] += g
            total[2] += b
            count += 1
    return tuple(channel // count for channel in total)


def trim_to_content(image: Image.Image, background: tuple[int, int, int]) -> Image.Image:
    """Обрезает однотонные поля вокруг рисунка и добавляет небольшой отступ."""
    reference = Image.new('RGB', image.size, background)
    diff = ImageChops.difference(image, reference).convert('L')
    mask = diff.point(lambda value: 255 if value > 18 else 0)
    bbox = mask.getbbox()
    if bbox is None:
        return image
    cropped = image.crop(bbox)
    side = max(cropped.size)
    squared = Image.new('RGB', (side, side), background)
    squared.paste(
        cropped,
        ((side - cropped.width) // 2, (side - cropped.height) // 2),
    )
    margin = int(side * 0.08)
    result = Image.new('RGB', (side + margin * 2, side + margin * 2), background)
    result.paste(squared, (margin, margin))
    return result


def circle_icon(image: Image.Image, size: int) -> Image.Image:
    scaled = image.resize((size, size), Image.LANCZOS).convert('RGBA')
    mask = Image.new('L', (size * 4, size * 4), 0)
    ImageDraw.Draw(mask).ellipse((0, 0, size * 4 - 1, size * 4 - 1), fill=255)
    mask = mask.resize((size, size), Image.LANCZOS)
    result = Image.new('RGBA', (size, size), (0, 0, 0, 0))
    result.paste(scaled, (0, 0), mask)
    return result


def main() -> None:
    if len(sys.argv) < 2:
        raise SystemExit(
            'Укажите путь к картинке: '
            'python tools/icon/make_photo_icon.py <file> [--trim] [--fill]',
        )
    source = Path(sys.argv[1])
    trim = '--trim' in sys.argv[2:]
    # --fill: картинка занимает весь canvas adaptive icon (края срежет маска).
    fill = '--fill' in sys.argv[2:]
    image = Image.open(source).convert('RGB')
    side = min(image.size)
    image = image.crop(
        (
            (image.width - side) // 2,
            (image.height - side) // 2,
            (image.width + side) // 2,
            (image.height + side) // 2,
        ),
    )

    background = edge_color(image)
    print('фон иконки:', '#%02X%02X%02X' % background)
    if trim:
        before = image.size
        image = trim_to_content(image, background)
        print('обрезка полей:', before, '→', image.size)

    for folder, size in LEGACY.items():
        target = RES / folder
        target.mkdir(parents=True, exist_ok=True)
        image.resize((size, size), Image.LANCZOS).save(target / 'ic_launcher.png')
        circle_icon(image, size).save(target / 'ic_launcher_round.png')

    adaptive = Image.new('RGB', (ADAPTIVE_CANVAS, ADAPTIVE_CANVAS), background)
    image_side = ADAPTIVE_CANVAS if fill else ADAPTIVE_IMAGE
    inset = (ADAPTIVE_CANVAS - image_side) // 2
    adaptive.paste(
        image.resize((image_side, image_side), Image.LANCZOS),
        (inset, inset),
    )
    foreground = RES / 'drawable-nodpi' / 'ic_launcher_foreground.png'
    foreground.parent.mkdir(parents=True, exist_ok=True)
    adaptive.save(foreground)

    colors = RES / 'values' / 'colors.xml'
    colors.write_text(
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<resources>\n'
        '    <color name="ic_launcher_background">#%02X%02X%02X</color>\n'
        '</resources>\n' % background,
        encoding='utf-8',
    )
    print('готово:', foreground, 'и', len(LEGACY), 'наборов mipmap')


if __name__ == '__main__':
    main()
