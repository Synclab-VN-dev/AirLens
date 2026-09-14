# Nghiệm thu Giai đoạn 5: Android → OpenStream OBS

Mục tiêu của bài nghiệm thu này là chứng minh luồng thật đi từ Android vào **nguồn OpenStream trong OBS**, không dùng FFmpeg làm receiver trung gian.

## Điều kiện trước khi chạy

Máy chạy harness phải có `adb` trên `PATH` và có thể `adb connect` tới điện thoại T1. OBS phải đang chạy trên máy nhận, đã nạp đúng plugin OpenStream của nhánh đang kiểm thử, và có một nguồn **OpenStream V8** đang bật listener ở cổng sẽ truyền vào. Log OBS phải chứa dòng `OBS plugin loaded` trước khi harness bắt đầu.

Nếu Android và máy OBS đi qua Tailscale, `--receiver-host` là IPv4 Tailscale của máy OBS. Không cần cắm USB; ADB/TCP qua Tailscale là đủ.

## Bài test tự động

`tools/phase5_obs_e2e.py` cài APK ứng dụng + APK instrumentation lên T1, cấp quyền camera/micro, rồi chạy đường 4K30 của Giai đoạn 3 **hai lần liên tiếp** tới listener của OBS. Không có tiến trình FFmpeg receiver nào được mở.

Sau hai phiên phát, harness chỉ PASS khi log OBS thật cho thấy đồng thời:

- plugin OpenStream đã được nạp;
- có hai phiên nhận `3840x2160`, codec `h264`, kèm audio;
- decoder audio mở AAC 48 kHz;
- ít nhất một khung 3840×2160 thật đã được đưa qua `obs_source_output_video*`;
- giữa hai phiên có trạng thái giữ slot để reconnect;
- cả hai instrumentation run trên Android đều thành công.

Hai phiên phát liên tiếp chủ động tạo một khoảng ngắt ngắn để kiểm tra OBS không chỉ nhận phiên đầu mà còn quay lại nhận được phiên sau.

## Chạy trực tiếp

Ví dụ:

```bash
python3 tools/phase5_obs_e2e.py \
  --adb-serial '100.x.y.z:5555' \
  --receiver-host '100.a.b.c' \
  --obs-log '/path/to/obs-studio/logs/current-log.txt' \
  --app-apk dist/openstream-android.apk \
  --test-apk dist/openstream-android-test.apk \
  --receiver-port 9000 \
  --duration-seconds 12 \
  --stream-bitrate-mbps 8 \
  --capability-bitrate-mbps 30 \
  --latency-ms 120
```

Bitrate 8 Mbps ở đây là gate chức năng để không biến tốc độ Tailscale thành điều kiện thất bại giả. Capability 4K30 của Camera2 + MediaCodec vẫn được kiểm ở 30 Mbps bởi instrumentation test. Nghiệm thu throughput 20–40 Mbps vẫn phải thực hiện riêng trên LAN/Wi‑Fi đủ tốt.

## Evidence

Mặc định evidence nằm ở `build/phase5-obs-e2e/`:

- `instrumentation-1.txt`
- `instrumentation-2.txt`
- `device-logcat.txt`
- `obs-session.log`
- `acceptance.json`

`acceptance.json` là nguồn quyết định PASS/FAIL. Không đóng #7 chỉ dựa vào CI source-contract hoặc việc plugin build thành công; phải có evidence từ OBS thật nhận Android 4K30 + micro. Nếu chưa có máy OBS phù hợp thì giữ #7 mở và chỉ ghi nhận phần harness/CI đã sẵn sàng.
