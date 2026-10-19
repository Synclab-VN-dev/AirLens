"""Google Play PNG contract and deterministic AirLens source checks."""

from pathlib import Path
import sys

from PIL import Image


ROOT = Path(__file__).resolve().parents[1]
ICON = ROOT / "android" / "play-store" / "airlens_store_icon_512.png"


def test_play_store_icon_spec():
    assert ICON.is_file(), "Generate the Play Store listing PNG and commit it"
    assert ICON.stat().st_size <= 1024 * 1024
    with Image.open(ICON) as image:
        assert image.format == "PNG"
        assert image.mode == "RGBA"  # 32-bit PNG with alpha channel.
        assert image.size == (512, 512)
        assert image.info.get("icc_profile"), "Store icon must be tagged sRGB"
        assert image.getchannel("A").getextrema() == (255, 255)
        assert image.getpixel((0, 0))[3] == 255
        assert image.getpixel((511, 511))[3] == 255


def test_play_store_icon_matches_sources(tmp_path):
    # An alternate output leaves the committed asset intact.
    sys.path.insert(0, str(ROOT / "tools"))
    from generate_play_store_icon import generate

    generated = tmp_path / "airlens_store_icon_512.png"
    generate(output=generated)
    assert generated.read_bytes() == ICON.read_bytes(), "Committed icon is stale; regenerate"
