#!/usr/bin/env python3
"""Phase 6 functional SRT reconnect acceptance over a routed test network.

The harness starts a real FFmpeg SRT listener, waits until Android is sending
4K30 + microphone media, deliberately terminates the receiver, keeps it down
for a short interval, then starts a fresh listener on the same port. Passing
proves caller loss detection/backoff/reconnect and post-recovery media flow.
It does not prove Wi-Fi roaming or production LAN behavior.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import threading
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import phase3_device_e2e as p3
import phase6_soak_e2e as p6

APP_ID = "dev.openstream.app"
TEST_PACKAGE = "dev.openstream.app.test"
RUNNER = "androidx.test.runner.AndroidJUnitRunner"
TEST_CLASS = "dev.openstream.app.Phase6ReconnectE2eTest#reconnect4k30WithMicrophone"


@dataclass(frozen=True)
class Config:
    adb_serial: str
    receiver_host: str
    app_apk: Path
    test_apk: Path
    receiver_port: int
    duration_seconds: int
    stream_bitrate_mbps: int
    capability_bitrate_mbps: int
    latency_ms: int
    outage_seconds: int
    fault_after_bytes: int
    evidence_dir: Path


def parse_args(argv: list[str]) -> Config:
    parser = argparse.ArgumentParser(description="Phase 6: functional receiver interruption + reconnect test.")
    parser.add_argument("--adb-serial", default=os.environ.get("OPENSTREAM_ADB_SERIAL"))
    parser.add_argument("--receiver-host", default=os.environ.get("OPENSTREAM_RECEIVER_HOST"))
    parser.add_argument("--app-apk", type=Path, default=Path("dist/openstream-android.apk"))
    parser.add_argument("--test-apk", type=Path, default=Path("dist/openstream-android-test.apk"))
    parser.add_argument("--receiver-port", type=int, default=19061)
    parser.add_argument("--duration-seconds", type=int, default=90)
    parser.add_argument("--stream-bitrate-mbps", type=int, default=8)
    parser.add_argument("--capability-bitrate-mbps", type=int, default=30)
    parser.add_argument("--latency-ms", type=int, default=120)
    parser.add_argument("--outage-seconds", type=int, default=10)
    parser.add_argument("--fault-after-bytes", type=int, default=4_000_000)
    parser.add_argument("--evidence-dir", type=Path, default=Path("build/phase6-reconnect-e2e"))
    args = parser.parse_args(argv)

    if not args.adb_serial:
        parser.error("cần --adb-serial hoặc OPENSTREAM_ADB_SERIAL")
    if not args.receiver_host:
        parser.error("cần --receiver-host hoặc OPENSTREAM_RECEIVER_HOST")
    if not 1 <= args.receiver_port <= 65535:
        parser.error("--receiver-port phải nằm trong 1..65535")
    if not 45 <= args.duration_seconds <= 180:
        parser.error("--duration-seconds phải nằm trong 45..180")
    if not 8 <= args.stream_bitrate_mbps <= 19:
        parser.error("reconnect smoke chỉ dùng functional bitrate 8..19 Mbps")
    if not 8 <= args.capability_bitrate_mbps <= 50:
        parser.error("--capability-bitrate-mbps phải nằm trong 8..50")
    if not 20 <= args.latency_ms <= 10_000:
        parser.error("--latency-ms phải nằm trong 20..10000")
    if not 3 <= args.outage_seconds <= 20:
        parser.error("--outage-seconds phải nằm trong 3..20")
    if args.fault_after_bytes < 500_000:
        parser.error("--fault-after-bytes phải >= 500000")

    return Config(
        adb_serial=args.adb_serial,
        receiver_host=args.receiver_host,
        app_apk=args.app_apk,
        test_apk=args.test_apk,
        receiver_port=args.receiver_port,
        duration_seconds=args.duration_seconds,
        stream_bitrate_mbps=args.stream_bitrate_mbps,
        capability_bitrate_mbps=args.capability_bitrate_mbps,
        latency_ms=args.latency_ms,
        outage_seconds=args.outage_seconds,
        fault_after_bytes=args.fault_after_bytes,
        evidence_dir=args.evidence_dir,
    )


def adb(config: Config, *args: str, check: bool = True, timeout: float | None = None):
    return p3.run(["adb", "-s", config.adb_serial, *args], check=check, timeout=timeout)


def install_test_build(config: Config) -> None:
    # Reuse the Phase 6 signature-recovery install path so this test is safe
    # after a developer-signed build was previously installed on the phone.
    p6.install_with_signature_recovery(config)  # type: ignore[arg-type]


def run_instrumentation(config: Config, output_path: Path) -> subprocess.CompletedProcess[str]:
    command = [
        "shell", "am", "instrument", "-w", "-r",
        "-e", "receiverHost", config.receiver_host,
        "-e", "receiverPort", str(config.receiver_port),
        "-e", "durationSeconds", str(config.duration_seconds),
        "-e", "streamBitrateMbps", str(config.stream_bitrate_mbps),
        "-e", "capabilityBitrateMbps", str(config.capability_bitrate_mbps),
        "-e", "latencyMs", str(config.latency_ms),
        "-e", "class", TEST_CLASS,
        f"{TEST_PACKAGE}/{RUNNER}",
    ]
    result = adb(config, *command, check=False, timeout=config.duration_seconds + 180)
    output_path.write_text((result.stdout or "") + (result.stderr or ""), encoding="utf-8")
    return result


def wait_for_media(path: Path, receiver: subprocess.Popen[str], minimum_bytes: int, timeout_seconds: int = 45) -> None:
    deadline = time.monotonic() + timeout_seconds
    while time.monotonic() < deadline:
        if receiver.poll() is not None:
            raise RuntimeError("FFmpeg receiver exited before fault injection")
        if path.is_file() and path.stat().st_size >= minimum_bytes:
            return
        time.sleep(0.25)
    size = path.stat().st_size if path.is_file() else 0
    raise RuntimeError(f"media did not reach {minimum_bytes} bytes before timeout; observed={size}")


def terminate_receiver_now(process: subprocess.Popen[str], log_file: Any) -> None:
    try:
        if process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)
    finally:
        log_file.close()


def parse_reconnect_counters(logcat: str) -> tuple[int, int]:
    losses = 0
    reconnects = 0
    pattern = re.compile(r"OpenStreamTelemetry:.*?losses=(\d+)\s+reconnects=(\d+)")
    for match in pattern.finditer(logcat):
        losses = max(losses, int(match.group(1)))
        reconnects = max(reconnects, int(match.group(2)))
    return losses, reconnects


def media_observation(probe: dict[str, Any]) -> dict[str, Any]:
    streams = probe.get("streams", [])
    video = next((s for s in streams if s.get("codec_type") == "video"), {})
    audio = next((s for s in streams if s.get("codec_type") == "audio"), {})
    fps = p3.rate_to_float(video.get("avg_frame_rate")) or p3.rate_to_float(video.get("r_frame_rate"))
    return {
        "videoCodec": video.get("codec_name"),
        "videoWidth": video.get("width"),
        "videoHeight": video.get("height"),
        "videoFps": fps,
        "audioCodec": audio.get("codec_name"),
        "audioSampleRate": audio.get("sample_rate"),
        "durationSeconds": p3.duration_seconds(probe),
    }


def validate(
    instrumentation: subprocess.CompletedProcess[str],
    before_probe: dict[str, Any],
    after_probe: dict[str, Any],
    logcat: str,
) -> dict[str, Any]:
    before = media_observation(before_probe)
    after = media_observation(after_probe)
    losses, reconnects = parse_reconnect_counters(logcat)
    instrumentation_text = (instrumentation.stdout or "") + (instrumentation.stderr or "")
    fatal_markers = [
        "FATAL EXCEPTION",
        "ANR in dev.openstream.app",
        "MediaCodec encoder error",
        "Audio capture/encoder failed",
    ]

    def media_ok(observed: dict[str, Any]) -> bool:
        fps = observed["videoFps"]
        return (
            observed["videoCodec"] == "h264"
            and int(observed["videoWidth"] or 0) == 3840
            and int(observed["videoHeight"] or 0) == 2160
            and fps is not None and 28.0 <= fps <= 32.0
            and observed["audioCodec"] == "aac"
            and str(observed["audioSampleRate"] or "") == "48000"
        )

    checks = {
        "instrumentation_passed": (
            instrumentation.returncode == 0
            and "FAILURES!!!" not in instrumentation_text
            and "Process crashed" not in instrumentation_text
        ),
        "media_flowed_before_interruption": media_ok(before),
        "media_resumed_after_reconnect": media_ok(after) and float(after["durationSeconds"] or 0.0) >= 5.0,
        "caller_reconnect_scheduled": "Caller reconnect scheduled attempt=" in logcat,
        "connection_loss_counted": losses >= 1,
        "successful_reconnect_counted": reconnects >= 1,
        "no_known_fatal_runtime_error": not any(marker in logcat for marker in fatal_markers),
    }
    return {
        "passed": all(checks.values()),
        "checks": checks,
        "observed": {
            "beforeInterruption": before,
            "afterReconnect": after,
            "maxConnectionLosses": losses,
            "maxReconnects": reconnects,
        },
        "performanceGate": {
            "included": False,
            "reason": "Receiver interruption over Tailscale validates caller reconnect behavior only; it is not LAN/Wi-Fi roaming or production-performance acceptance.",
        },
    }


def main(argv: list[str]) -> int:
    config = parse_args(argv)
    p3.require_binary("adb")
    ffmpeg = p3.require_binary("ffmpeg")
    ffprobe = p3.require_binary("ffprobe")
    p3.ensure_srt(ffmpeg)

    config.evidence_dir.mkdir(parents=True, exist_ok=True)
    before_capture = config.evidence_dir / "before-interruption.ts"
    after_capture = config.evidence_dir / "after-reconnect.ts"
    before_receiver_log = config.evidence_dir / "receiver-before.log"
    after_receiver_log = config.evidence_dir / "receiver-after.log"
    instrumentation_log = config.evidence_dir / "instrumentation.txt"

    p3.connect_device(config)  # type: ignore[arg-type]
    install_test_build(config)

    receiver = None
    receiver_log_file = None
    post_receiver = None
    post_receiver_log_file = None
    result_holder: dict[str, subprocess.CompletedProcess[str]] = {}
    error_holder: list[BaseException] = []

    def instrumentation_worker() -> None:
        try:
            result_holder["result"] = run_instrumentation(config, instrumentation_log)
        except BaseException as error:  # propagate after cleanup
            error_holder.append(error)

    receiver, receiver_log_file = p3.start_receiver(
        config, ffmpeg, before_capture, before_receiver_log  # type: ignore[arg-type]
    )
    worker = threading.Thread(target=instrumentation_worker, name="Phase6ReconnectInstrumentation", daemon=True)
    worker.start()

    try:
        wait_for_media(before_capture, receiver, config.fault_after_bytes)
        terminate_receiver_now(receiver, receiver_log_file)
        receiver = None
        receiver_log_file = None

        time.sleep(config.outage_seconds)
        post_receiver, post_receiver_log_file = p3.start_receiver(
            config, ffmpeg, after_capture, after_receiver_log  # type: ignore[arg-type]
        )

        worker.join(timeout=config.duration_seconds + 150)
        if worker.is_alive():
            raise RuntimeError("Reconnect instrumentation did not finish before timeout")
        if error_holder:
            raise error_holder[0]
    finally:
        if receiver is not None and receiver_log_file is not None:
            terminate_receiver_now(receiver, receiver_log_file)
        if post_receiver is not None and post_receiver_log_file is not None:
            p3.stop_receiver(post_receiver, post_receiver_log_file)
        if worker.is_alive():
            adb(config, "shell", "am", "force-stop", APP_ID, check=False)
            worker.join(timeout=10)

    instrumentation = result_holder.get("result")
    if instrumentation is None:
        raise SystemExit("Instrumentation không trả kết quả")

    logcat = adb(config, "logcat", "-d", "-v", "threadtime", check=False).stdout
    (config.evidence_dir / "device-logcat.txt").write_text(logcat, encoding="utf-8")

    before_probe: dict[str, Any] = {}
    after_probe: dict[str, Any] = {}
    if before_capture.is_file() and before_capture.stat().st_size > 0:
        before_probe = p3.ffprobe_json(ffprobe, before_capture, config.evidence_dir / "ffprobe-before.json")
    else:
        (config.evidence_dir / "ffprobe-before.json").write_text("{}\n", encoding="utf-8")
    if after_capture.is_file() and after_capture.stat().st_size > 0:
        after_probe = p3.ffprobe_json(ffprobe, after_capture, config.evidence_dir / "ffprobe-after.json")
    else:
        (config.evidence_dir / "ffprobe-after.json").write_text("{}\n", encoding="utf-8")

    acceptance = validate(instrumentation, before_probe, after_probe, logcat)
    (config.evidence_dir / "acceptance.json").write_text(
        json.dumps(acceptance, indent=2, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )
    (config.evidence_dir / "transport-context.txt").write_text(
        "performance_acceptance=false\n"
        "mode=Tailscale functional reconnect smoke\n"
        "note=Validates receiver interruption and caller reconnect only; not production Wi-Fi roaming.\n",
        encoding="utf-8",
    )

    print(json.dumps(acceptance, indent=2, ensure_ascii=False))
    if not acceptance["passed"]:
        failed = [name for name, passed in acceptance["checks"].items() if not passed]
        print("FAILED checks:", ", ".join(failed), file=sys.stderr)
        return 1
    print("Phase 6 reconnect functional gate: PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
