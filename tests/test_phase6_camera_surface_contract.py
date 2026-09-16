from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
CAMERA = ROOT / "android/app/src/main/java/dev/openstream/app/camera/Camera2Controller.kt"


def test_streaming_can_continue_without_preview_surface() -> None:
    source = CAMERA.read_text(encoding="utf-8")

    assert "?.takeIf { it.isValid }" in source
    assert "if (preview == null && encoded == null)" in source
    assert "Preview surface unavailable; using encoder-only camera session" in source
    assert "val surfaces = listOfNotNull(preview, encoded)" in source
    assert source.count("preview?.let { addTarget(it) }") >= 2
    assert source.count("encoded?.let { addTarget(it) }") >= 2


def test_camera_recovery_accepts_encoder_surface_when_preview_is_missing() -> None:
    source = CAMERA.read_text(encoding="utf-8")

    assert "val encoderReady = streamingSurface?.isValid == true" in source
    assert "if (!previewReady && !encoderReady) return" in source
