"""Generate the AirLens Google Play listing icon from existing brand assets.

Run: python tools/generate_play_store_icon.py
Requires Pillow. Does not modify Android launcher resources.
"""

from io import BytesIO
from pathlib import Path

from PIL import Image, ImageCms, PngImagePlugin

from generate_android_launcher_icons import emblem


ROOT = Path(__file__).resolve().parents[1]
BACKGROUND = ROOT / "android" / "icon-source" / "airlens_background.png"
OUTPUT = ROOT / "android" / "play-store" / "airlens_store_icon_512.png"
SIZE = 512
EMBLEM_WIDTH = 384  # Keyline: keep the mark clear of Play's rounded corners.
MAX_BYTES = 1024 * 1024
RESAMPLE = Image.Resampling.LANCZOS


def to_srgb(image: Image.Image) -> Image.Image:
    """Honor a source ICC profile when present; untagged artwork is assumed sRGB."""
    profile = image.info.get("icc_profile")
    if not profile:
        return image.convert("RGBA")
    alpha = image.getchannel("A") if "A" in image.getbands() else None
    converted = ImageCms.profileToProfile(
        image.convert("RGB"),
        ImageCms.ImageCmsProfile(BytesIO(profile)),
        ImageCms.createProfile("sRGB"),
        outputMode="RGB",
    ).convert("RGBA")
    if alpha is not None:
        converted.putalpha(alpha)
    return converted


def generate(background: Path = BACKGROUND, output: Path = OUTPUT) -> Path:
    with Image.open(background) as original:
        base = to_srgb(original)
    base = base.resize((SIZE, SIZE), RESAMPLE)
    # Composite any transparent background onto an opaque brand navy base.
    base = Image.alpha_composite(Image.new("RGBA", (SIZE, SIZE), (3, 13, 42, 255)), base)
    mark = emblem(EMBLEM_WIDTH)
    x = (SIZE - mark.width) // 2
    y = (SIZE - mark.height) // 2
    base.alpha_composite(mark, (x, y))
    output.parent.mkdir(parents=True, exist_ok=True)
    # PNG remains 32-bit RGBA while every alpha pixel is opaque.
    png_info = PngImagePlugin.PngInfo()
    png_info.add(b"sRGB", bytes([0]))
    base.save(output, format="PNG", optimize=True, pnginfo=png_info)
    if output.stat().st_size > MAX_BYTES:
        raise ValueError(f"Store icon exceeds 1024 KiB: {output.stat().st_size} bytes")
    return output


if __name__ == "__main__":
    result = generate()
    with Image.open(result) as image:
        print(f"{result}: {image.width}x{image.height}, {image.mode}, {result.stat().st_size} bytes")
