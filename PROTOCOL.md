# Giao thức LAN

Tất cả datagram dùng JSON UTF-8 qua UDP 17840. `Management Key` là 32 byte ngẫu nhiên, biểu diễn bằng Base64. Chữ ký là Base64 của HMAC-SHA256 trên chuỗi canonical.

## Discovery

App phát broadcast:

```text
HMAC(key, "discover\n" + nonce)
```

Agent chỉ trả lời request có chữ ký đúng. Phản hồi được ký:

```text
HMAC(key, "discovery\n" + requestNonce + "\n" + deviceId + "\n" + hostname + "\n" + mac)
```

## Power command

App gửi trực tiếp tới IP của agent:

```text
HMAC(key, "command\n" + requestId + "\n" + deviceId + "\n" + action + "\n" + unixTimestamp + "\n" + nonce)
```

Agent chỉ chấp nhận `shutdown` hoặc `restart`, timestamp lệch không quá 120 giây và nonce chưa sử dụng. ACK được ký:

```text
HMAC(key, "ack\n" + requestId + "\n" + status)
```

## Wake-on-LAN

App gửi Magic Packet 102 byte tới các địa chỉ broadcast IPv4 của điện thoại trên UDP 9. Máy đích phải bật `S5 Wake on LAN` và dùng Ethernet.
