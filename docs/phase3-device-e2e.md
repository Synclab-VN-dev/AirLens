# Nghiệm thu E2E Giai đoạn 3 trên thiết bị thật

Mục tiêu của bài kiểm thử này là tự động xác nhận đường chức năng:

`Camera2 thật -> MediaCodec H.264 phần cứng -> mic/AudioRecord -> AAC -> MPEG-TS -> libsrt/SRT -> Linux receiver -> ffprobe`.

## Phân tách capability và hiệu năng mạng

Đường Tailscale có thể chậm hơn bitrate sản phẩm. Vì vậy bài kiểm thử tách hai gate:

- **Capability gate** luôn kiểm tra Camera2 + hardware MediaCodec ở `3840x2160 @ 30 fps` với bitrate mục tiêu mặc định `30 Mbps`.
- **Network E2E gate** có thể phát 4K30 ở bitrate thấp nhất hiện tại của Phase 3, mặc định `8 Mbps`, để kiểm chứng luồng thật qua SRT mà không biến tốc độ Tailscale thành điều kiện thất bại giả.

Hiệu năng sustained `20-40 Mbps` trên LAN/Wi-Fi tốt vẫn là hạng mục nghiệm thu riêng. Không được dùng kết quả Tailscale để kết luận throughput 4K30 production.

## Điều kiện Linux

Linux cần có:

- Python 3;
- `adb`;
- FFmpeg có hỗ trợ `srt`;
- `ffprobe`;
- kết nối Tailscale tới Android;
- cổng UDP `19000` không bị firewall chặn.

Kiểm tra FFmpeg:

```bash
ffmpeg -hide_banner -protocols | grep -w srt
```

## Điều kiện Android

Android phải cho phép ADB từ Linux. Nếu hai máy chỉ gặp nhau qua Tailscale thì `adb` cũng phải đi được qua Tailscale.

Ví dụ khi thiết bị đã bật ADB TCP ở cổng 5555:

```bash
adb connect <TAILSCALE_IP_ANDROID>:5555
adb -s <TAILSCALE_IP_ANDROID>:5555 get-state
```

Nếu dùng Wireless debugging với cổng ngẫu nhiên, dùng đúng cổng `adb connect` mà Android hiển thị. Nếu cần pairing lần đầu thì pairing phải hoàn thành trước khi workflow chạy.

## Chạy trực tiếp trên Linux

Sau khi có `openstream-android.apk` và `openstream-android-test.apk`:

```bash
python3 tools/phase3_device_e2e.py \
  --adb-serial <TAILSCALE_IP_ANDROID>:5555 \
  --receiver-host <TAILSCALE_IP_LINUX> \
  --app-apk dist/openstream-android.apk \
  --test-apk dist/openstream-android-test.apk \
  --stream-bitrate-mbps 8 \
  --capability-bitrate-mbps 30 \
  --duration-seconds 15 \
  --latency-ms 2000
```

Có thể dùng biến môi trường thay cho hai tham số địa chỉ:

```bash
export OPENSTREAM_ADB_SERIAL=<TAILSCALE_IP_ANDROID>:5555
export OPENSTREAM_RECEIVER_HOST=<TAILSCALE_IP_LINUX>
python3 tools/phase3_device_e2e.py
```

## Evidence tự động

Mặc định evidence được ghi tại `build/phase3-device-e2e/`:

- `acceptance.json`: kết quả pass/fail từng điều kiện;
- `phase3-device-e2e-preflight.json`: capability Camera2/MediaCodec từ chính Android;
- `ffprobe.json`: codec/resolution/fps/audio thực nhận;
- `ffprobe-frames.json`: evidence keyframe cadence;
- `device-logcat.txt`: encoder/audio/camera runtime log;
- `receiver-ffmpeg.log`: log phía Linux receiver;
- `instrumentation.txt`: kết quả Android instrumentation;
- `phase3-e2e.ts`: mẫu MPEG-TS nhận qua SRT.

Bài kiểm thử pass khi tối thiểu xác nhận được:

- main back camera có đường Camera2 + hardware AVC `3840x2160@30` tại capability bitrate 30 Mbps;
- stream thật nhận được là H.264 `3840x2160` gần 30 fps;
- High profile được yêu cầu và ffprobe thấy High khi capability công bố hỗ trợ;
- log xác nhận hardware video encoder, không có software video fallback;
- có AAC 48 kHz và `AudioRecord` thật đã bắt đầu thu microphone;
- keyframe cadence gần 2 giây;
- app giữ trạng thái LIVE trong suốt sample;
- receiver thực nhận được MPEG-TS qua SRT.

## GitHub Actions

Workflow `.github/workflows/phase3-device-e2e.yml` dùng runner:

```text
self-hosted, linux, openstream-device-lab
```

Linux lab cần đăng ký làm GitHub self-hosted runner và gắn label `openstream-device-lab`. Workflow build APK trên GitHub-hosted runner, sau đó tải APK xuống Linux lab và chạy hardware E2E. Vì vậy Linux lab không cần Android SDK/Gradle để chạy gate, chỉ cần `adb`, Python, FFmpeg và ffprobe.

Workflow hiện chỉ tự động tạo evidence và quyết định pass/fail. Phiếu #5 chỉ được đóng sau khi evidence này pass và hạng mục throughput LAN thủ công đã được xác nhận hoặc được chuyển rõ ràng sang một gate khác theo quyết định của dự án.
