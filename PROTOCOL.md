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

`capabilities` là chuỗi `power` hoặc `power,uwf`. Chỉ tin metadata UWF/capabilities khi `stateSignature` hợp lệ. Trạng thái UWF mô tả C cho phiên hiện tại và phiên sau reboot; filter toàn hệ thống bật chưa đủ để kết luận C được bảo vệ.

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
