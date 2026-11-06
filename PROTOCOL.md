# Giao thức LAN

Tất cả datagram dùng JSON UTF-8 qua UDP 17840. Khóa giao thức luôn dài 32 byte, biểu diễn bằng Base64. Chữ ký là Base64 của HMAC-SHA256 trên chuỗi canonical.

## Key phòng (1.1.0)

Người dùng chọn key gồm đúng 8 ký tự ASCII in được không có khoảng trắng `[\x21-\x7E]`, phân biệt hoa/thường. Bộ cài và APK dẫn xuất cùng khóa giao thức:

```text
PBKDF2-HMAC-SHA256(
  password = UTF8(roomKey),
  salt = UTF8("LanPower/RoomKey/v1"),
  iterations = 210000,
  outputBytes = 32
)
```

Không gửi key phòng trong gói UDP. Mỗi phòng là một phạm vi khóa; tên phòng được lưu cục bộ trên APK và không cần cấu hình trên Windows. Khóa Base64 32 byte của bản cũ vẫn hợp lệ.

Vector dùng trong kiểm thử, không phải key triển khai:

```text
ROOM0001 -> VUnd9gqHWPnL3YsvZF69DuCPHFp4YrHzQbv+TVHKsuc=
ROOM0002 -> kLU0qzCKD7iBFMKXa8010x45Me8b/xIbLo/XH+co4yw=
```

Dẫn xuất không làm tăng độ khó đoán của key do người dùng tự chọn. Key 8 ký tự là đánh đổi tiện dụng; chỉ sử dụng trong LAN tin cậy và không công bố key.

## Discovery

App phát broadcast:

```text
HMAC(key, "discover\n" + nonce)
```

Agent chỉ trả lời request có chữ ký đúng. Phản hồi được ký:

```text
HMAC(key, "discovery\n" + requestNonce + "\n" + deviceId + "\n" + hostname + "\n" + mac)
```

Agent 1.1.0 bổ sung `uwf`, `agentVersion`, `capabilities` và `stateSignature`:

```text
HMAC(key, "state\n" + requestNonce + "\n" + deviceId + "\n" + agentVersion + "\n" + uwf + "\n" + capabilities)
```

`capabilities` là chuỗi `power` hoặc `power,uwf`; bản 1.2.0 thêm `wolrelay` ở cuối. Chỉ tin metadata UWF/capabilities khi `stateSignature` hợp lệ. Trạng thái UWF mô tả C cho phiên hiện tại và phiên sau reboot; filter toàn hệ thống bật chưa đủ để kết luận C được bảo vệ.

Bản 1.2.0 dùng cùng request/chữ ký khi gửi discovery unicast tới IP đã lưu hoặc đích IPv4/CIDR LAN/VPN do người dùng cấu hình. Không phụ thuộc broadcast đi qua VPN. Agent trả lời về IP/cổng nguồn; cần định tuyến hai chiều và firewall cho phép nguồn VPN.

Metadata cập nhật Windows được bảo vệ riêng:

```text
HMAC(key, "update\n" + requestNonce + "\n" + deviceId + "\n" + clientVersionCode + "\n" + latestVersion + "\n" + updateStatus + "\n" + updateMessage)
```

Chỉ tin các trường này khi `updateSignature` hợp lệ. Đây là xác thực trạng thái agent, còn tệp cập nhật phải qua chữ ký RSA của nhà phát hành.

## Power command

App gửi trực tiếp tới IP của agent:

```text
HMAC(key, "command\n" + requestId + "\n" + deviceId + "\n" + action + "\n" + unixTimestamp + "\n" + nonce)
```

Agent chấp nhận `shutdown`, `restart`, `freeze` hoặc `unfreeze`, timestamp lệch không quá 120 giây và nonce chưa sử dụng. `freeze` bảo vệ C cho phiên kế tiếp; `unfreeze` bỏ bảo vệ C cho phiên kế tiếp. Hai thao tác không tự reboot và không dừng vòng nhận lệnh của agent. ACK được ký:

```text
HMAC(key, "ack\n" + requestId + "\n" + status)
```

Agent 1.1.0 thêm `detailSignature` để bảo vệ thông báo kết quả:

```text
HMAC(key, "ack-detail\n" + requestId + "\n" + status + "\n" + message)
```

ACK `accepted` cho UWF chỉ được gửi sau khi lệnh cấu hình thành công; thay đổi vẫn chờ restart. Lỗi thiếu feature/không hỗ trợ hoặc exit code bất thường trả ACK `rejected` được ký. App giữ khả năng điều khiển power với agent cũ nhưng không coi metadata không ký là trạng thái bảo vệ đáng tin cậy.

## Wake-on-LAN

App gửi Magic Packet 102 byte tới các địa chỉ broadcast IPv4 của điện thoại trên UDP 9. Máy đích phải bật `S5 Wake on LAN` và dùng Ethernet.

Bản 1.2.0 hỗ trợ relay qua agent online cùng phòng có capability `wolrelay` đã xác thực. Request `action = wake` thêm `targetMac` chuẩn hóa dạng `AA:BB:CC:DD:EE:FF`. Canonical của lệnh là chuỗi power command ở trên, nối thêm `"\n" + targetMac`. MAC không hợp lệ hoặc bị thay đổi sẽ bị từ chối; timestamp/nonce chống replay áp dụng như lệnh nguồn. Relay phát Magic Packet trong LAN và trả ACK có `detailSignature`. ACK chỉ xác nhận phát gói, cần discovery lại để biết máy đã online. Nếu cả LAN đích không còn relay online, cần thiết bị WOL luôn bật riêng.

## Cập nhật có chữ ký

Manifest HTTPS `updates/stable.json` gồm `algorithm = RS256`, `payload` Base64 và `signature` Base64. Xác minh RSA-SHA256/PKCS#1 v1.5 trên đúng byte payload bằng khóa công khai nhúng sẵn trước khi tin JSON, phiên bản hoặc URL. Payload stable schema 1 chứa version/versionCode và URL/SHA-256 cho Windows agent/core/updater/setup và Android APK. URL chỉ thuộc repository cho phép; các artifact phát hành dùng SHA commit bất biến. APK phải cùng package và chứng thư hiện tại. Xem [quy trình phát hành](updates/README.md).
