# Giai đoạn 6: ổn định dài hạn, telemetry và soak

Giai đoạn 6 thuộc #8. Mục tiêu là tách rõ **telemetry/runtime recovery trong mã nguồn** khỏi **bằng chứng chạy dài vật lý**.

## Telemetry runtime

`SrtStreamClient` duy trì hai nhóm số liệu:

- số liệu phiên hiện tại: video access unit, keyframe, video byte, audio access unit, audio byte, lỗi gửi và PTS cuối;
- số liệu vòng đời tiến trình: tổng access unit/byte, số kết nối thành công, số reconnect và số lần mất kết nối.

Một generation transport chỉ được tính mất kết nối một lần dù video và audio cùng phát hiện lỗi. Khi reconnect, counter của phiên mới được reset nhưng counter vòng đời không bị xóa.

`SendRateMeter` tính bitrate gửi thực tế từ chênh lệch counter byte vòng đời theo đồng hồ monotonic và chỉ giữ một mẫu trước đó, không tạo queue telemetry không giới hạn.

`TelemetrySampler` thu pin, RSSI Wi‑Fi, nhiệt độ pin và Android thermal status khi hệ điều hành cung cấp.

## Chế độ kiểm thử hiện tại

Trong giai đoạn chưa có môi trường Android + receiver cùng LAN/Wi-Fi production, workflow ưu tiên **functional validation qua Tailscale**. PASS qua Tailscale chỉ chứng minh luồng chức năng; không được dùng để kết luận throughput, độ ổn định mạng, roaming hay thermal/resource dưới tải production.

| TC | Hạng mục | Qua Tailscale | Ý nghĩa của PASS | Limitation / nghiệm thu cuối |
|---|---|---:|---|---|
| P6-TC01 | Python/unit/source-contract telemetry + reconnect | ✅ | Gate implementation | Không có limitation mạng đáng kể |
| P6-TC02 | Build app APK + androidTest APK + lint | ✅ | APK và instrumentation build được | Không liên quan mạng |
| P6-TC03 | ADB tới Android vật lý qua Tailscale | ✅ | Device reachable/install/run được | Chỉ là hạ tầng test remote |
| P6-TC04 | Capability 4K30, hardware encoder, mic/config | ✅ | Device hỗ trợ path cần test | Không chứng minh media truyền ổn định |
| P6-TC05 | Android mở SRT unicast tới receiver qua Tailscale | ✅ | Xác nhận connect path | Không đại diện topology LAN production |
| P6-TC06 | Receiver nhận H.264 4K30 + AAC 48 kHz | ✅ | Xác nhận media path đầu-cuối | FPS/bitrate qua overlay không dùng đánh giá performance |
| P6-TC07 | Thu telemetry bitrate/frame/keyframe/audio/error/loss/reconnect | ✅ | Xác nhận observability/counters | Actual bitrate chịu ảnh hưởng overlay |
| P6-TC08 | Battery/memory/thermal sampling | ✅ | Xác nhận harness thu được số liệu | Chỉ diagnostic, không phải production acceptance |
| P6-TC09 | Ngắt receiver rồi auto reconnect và nhận lại media | ✅ | Xác nhận reconnect state machine | Không chứng minh Wi-Fi roaming/LAN recovery |
| P6-TC10 | Functional smoke 60–120 giây | ✅ | Xác nhận media duy trì ngắn hạn, không crash/fatal | Không phải soak/performance acceptance |
| P6-TC11 | 30 phút 4K30 + mic @20–40 Mbps | ❌ | DEFERRED | Chỉ nghiệm thu trên LAN/Wi-Fi phù hợp |
| P6-TC12 | Throughput/drop/loss thực ở 20–40 Mbps | ❌ | DEFERRED | Tailscale có thể là bottleneck/overlay |
| P6-TC13 | Resource/thermal dưới production load | ⚠️ | Chỉ diagnostic hiện tại | Acceptance để manual dưới tải production |
| P6-TC14 | Chuyển Wi-Fi/mạng và recovery topology production | ❌ | DEFERRED | Tailscale che/biến đổi topology và roaming path |

Quy ước: các TC `DEFERRED` **không làm block vòng phát triển functional hiện tại** và không bị đánh FAIL chỉ vì chưa có môi trường test thật. Chúng vẫn là gate bắt buộc trước khi đóng hoàn toàn Giai đoạn 6.

## Soak E2E

Instrumentation `Phase6SoakE2eTest` giữ **một phiên liên tục** 3840×2160@30 + micro. Thời lượng mặc định là 1800 giây và có thể đặt từ 60 đến 3600 giây. Với push hiện tại, workflow dùng functional smoke ngắn qua Tailscale ở bitrate thấp hơn dải nghiệm thu production; workflow thủ công giữ đường soak 20–40 Mbps cho môi trường LAN/Wi-Fi phù hợp về sau.

Host harness:

```bash
python3 tools/phase6_soak_e2e.py \
  --adb-serial 'DEVICE:PORT' \
  --receiver-host 'RECEIVER_IP' \
  --app-apk dist/openstream-android.apk \
  --test-apk dist/openstream-android-test.apk \
  --duration-seconds 1800 \
  --stream-bitrate-mbps 30
```

Harness mở FFmpeg SRT listener, chạy instrumentation, lấy `ffprobe`, `logcat`, preflight và định kỳ lấy `dumpsys battery`, `dumpsys meminfo` và `dumpsys thermalservice`.

Evidence mặc định ở `build/phase6-soak-e2e/` gồm `acceptance.json`, MPEG-TS capture, ffprobe, instrumentation output, device logcat và các mẫu sức khỏe thiết bị.

## GitHub Actions

Workflow `Phase 6 Soak E2E` chạy job build trên GitHub-hosted runner, sau đó chuyển APK sang runner `[self-hosted, linux, openstream-device-lab]`.

`adb_serial` và `receiver_host` là override tùy chọn. Nếu bỏ trống, workflow yêu cầu đúng một ADB device ở trạng thái `device` và tự lấy `tailscale ip -4` của runner. Không hard-code IP/ADB serial vào repository.

Hai mode được hiểu như sau:

- push: **Tailscale Functional Smoke**, `performance_acceptance=false`;
- workflow thủ công: **Production LAN/Wi-Fi Soak**, dùng sau khi có môi trường phù hợp.

## Quy tắc nghiệm thu

`acceptance.json` PASS của push run chỉ chứng minh functional smoke: video H.264 4K30, AAC 48 kHz, profile High khi capability báo hỗ trợ, hardware encoder, micro thật, telemetry và không có lỗi runtime nghiêm trọng đã biết trong cửa sổ test.

PASS tự động qua Tailscale **không tự động hoàn tất gate hiệu năng 20–40 Mbps**. #8 vẫn mở cho tới khi các TC manual/deferred bắt buộc được xác nhận trên môi trường production phù hợp.
