"""Build AirLens adaptive layers and legacy launcher PNGs from the supplied artwork.

Run from any directory with: python3 tools/generate_android_launcher_icons.py
Requires Pillow. Android's adaptive canvas is 108 dp; its circular mask is 72 dp.
"""

from pathlib import Path

from PIL import Image, ImageDraw


ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "android" / "icon-source"
RES = ROOT / "android" / "app" / "src" / "main" / "res"
CANVAS = 432  # 108 dp at xxxhdpi
ADAPTIVE_MASK_DIAMETER = 288  # 72 dp at xxxhdpi
FOREGROUND_WIDTH = 220  # Fills the circular mask without clipping the A's feet
LEGACY_SQUARE_WIDTH = 360
LEGACY_ROUND_WIDTH = 330
DENSITIES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
RESAMPLE = Image.Resampling.LANCZOS


def emblem(width: int) -> Image.Image:
    source = Image.open(SOURCE / "airlens_foreground.png").convert("RGBA")
    alpha = source.getchannel("A")
    # The export contains a few nearly invisible stray pixels outside the mark.
    visible = alpha.point(lambda value: 255 if value > 10 else 0)
    mark = source.crop(visible.getbbox())
    mark.putalpha(mark.getchannel("A").point(lambda value: value if value > 10 else 0))
    height = round(mark.height * width / mark.width)
    return mark.resize((width, height), RESAMPLE)


def centered_mark(width: int) -> Image.Image:
    layer = Image.new("RGBA", (CANVAS, CANVAS))
    mark = emblem(width)
    layer.alpha_composite(mark, ((CANVAS - mark.width) // 2, (CANVAS - mark.height) // 2))
    return layer


def main() -> None:
    background = Image.open(SOURCE / "airlens_background.png").convert("RGBA")
    background = background.resize((CANVAS, CANVAS), RESAMPLE)
    adaptive_foreground = centered_mark(FOREGROUND_WIDTH)

    def fits_circle(mark: Image.Image, radius: float) -> bool:
        alpha = mark.getchannel("A")
        return all(
            (x + 0.5 - CANVAS / 2) ** 2 + (y + 0.5 - CANVAS / 2) ** 2 < radius**2
            for y in range(CANVAS)
            for x in range(CANVAS)
            if alpha.getpixel((x, y)) > 10
        )

    assert fits_circle(adaptive_foreground, ADAPTIVE_MASK_DIAMETER / 2), (
        "Foreground extends beyond the adaptive circular mask"
    )

    drawable = RES / "drawable-nodpi"
    drawable.mkdir(parents=True, exist_ok=True)
    adaptive_foreground.save(drawable / "airlens_launcher_foreground.png", optimize=True)
    background.save(drawable / "airlens_launcher_background.png", optimize=True)

    for name, size in DENSITIES.items():
        directory = RES / f"mipmap-{name}"
        directory.mkdir(parents=True, exist_ok=True)
        square = Image.alpha_composite(background, centered_mark(LEGACY_SQUARE_WIDTH))
        square.resize((size, size), RESAMPLE).save(directory / "ic_launcher.png", optimize=True)

        round_mark = centered_mark(LEGACY_ROUND_WIDTH)
        assert fits_circle(round_mark, CANVAS / 2), "Legacy round icon clips the emblem"
        round_icon = Image.alpha_composite(background, round_mark)
        mask = Image.new("L", (CANVAS, CANVAS))
        ImageDraw.Draw(mask).ellipse((0, 0, CANVAS - 1, CANVAS - 1), fill=255)
        round_icon.putalpha(mask)
        round_icon.resize((size, size), RESAMPLE).save(
            directory / "ic_launcher_round.png", optimize=True
        )


if __name__ == "__main__":
    main()
