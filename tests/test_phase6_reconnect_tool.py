from importlib.util import module_from_spec, spec_from_file_location
from pathlib import Path
import subprocess
import sys


ROOT = Path(__file__).resolve().parents[1]
TOOLS = ROOT / "tools"
sys.path.insert(0, str(TOOLS))
MODULE_PATH = TOOLS / "phase6_reconnect_e2e.py"
SPEC = spec_from_file_location("phase6_reconnect_e2e", MODULE_PATH)
assert SPEC and SPEC.loader
MODULE = module_from_spec(SPEC)
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)


def test_parse_reconnect_counters_uses_max_lifetime_values() -> None:
    logcat = """
I OpenStreamTelemetry: targetMbps=8 losses=0 reconnects=0 codec=x
I OpenStreamTelemetry: targetMbps=8 losses=1 reconnects=1 codec=x
I OpenStreamTelemetry: targetMbps=8 losses=2 reconnects=1 codec=x
"""
    assert MODULE.parse_reconnect_counters(logcat) == (2, 1)


def test_validate_requires_media_before_and_after_and_reconnect_counters() -> None:
    probe = {
        "streams": [
            {
                "codec_type": "video",
                "codec_name": "h264",
                "width": 3840,
                "height": 2160,
                "avg_frame_rate": "30/1",
            },
            {
                "codec_type": "audio",
                "codec_name": "aac",
                "sample_rate": "48000",
                "channels": 1,
            },
        ],
        "format": {"duration": "10.0"},
    }
    instrumentation = subprocess.CompletedProcess(args=[], returncode=0, stdout="OK", stderr="")
    logcat = (
        "Caller reconnect scheduled attempt=1 delayMs=750 reason=send failed\n"
        "I OpenStreamTelemetry: targetMbps=8 losses=1 reconnects=1 codec=x\n"
    )

    result = MODULE.validate(instrumentation, probe, probe, logcat)

    assert result["passed"] is True
    assert result["observed"]["maxConnectionLosses"] == 1
    assert result["observed"]["maxReconnects"] == 1
    assert result["performanceGate"]["included"] is False


def test_parse_args_keeps_reconnect_smoke_outside_performance_range() -> None:
    config = MODULE.parse_args(
        [
            "--adb-serial", "device:5555",
            "--receiver-host", "100.1.2.3",
            "--stream-bitrate-mbps", "8",
            "--duration-seconds", "90",
            "--outage-seconds", "10",
        ]
    )
    assert config.stream_bitrate_mbps == 8
    assert config.duration_seconds == 90
    assert config.outage_seconds == 10
