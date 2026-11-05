# LanPower Standalone 1.0

Giải pháp bật, tắt và khởi động lại HP t540 Windows IoT từ Android trong cùng LAN, không cần API hoặc máy chủ trung tâm.

## Kiến trúc

```text
Android APK ── UDP broadcast 17840 ──► phát hiện HP t540
Android APK ── UDP 17840 + HMAC ─────► tắt/restart
Android APK ── UDP broadcast 9 ──────► Wake-on-LAN
```

Mỗi t540 cần cài standalone agent. Agent chạy bằng Scheduled Task dưới tài khoản SYSTEM, chỉ mở UDP 17840 cho `LocalSubnet` và chỉ chấp nhận lệnh có Management Key đúng.

## 1. Tạo Management Key

Trên máy quản trị, chạy:

```powershell
.\client\New-ManagementKey.ps1
```

Lưu khóa được in ra. Dùng đúng cùng một khóa cho app và các máy thuộc cùng nhóm quản lý.

## 2. Cấu hình HP t540

Trong BIOS (`F10`):

1. `Advanced` → `Device Options`.
2. Bật `S5 Wake on LAN`.
3. Tắt `S5 Maximum Power Savings`.
4. Cắm cáp Ethernet và giữ nguồn điện kết nối.

Trong Windows bật `Wake on Magic Packet` của card Ethernet nếu driver có tùy chọn này.

## 3. Cài agent trên mỗi máy

Mở PowerShell Administrator trong thư mục `client`:

```powershell
.\Install-Agent.ps1 -ManagementKey "KHOA-BASE64-DA-TAO"
```

Sau khi repository được xuất bản, có thể cài online bằng PowerShell Administrator. Tải script về file tạm, kiểm tra SHA-256 rồi mới chạy; không dùng `irm | iex`. Lệnh chính xác và checksum hiện hành được ghi trong phần Releases/README của repository.

```powershell
$key = 'KHOA-BASE64-DA-TAO'
$url = 'https://raw.githubusercontent.com/namnamthainguyen-ops/apklan/main/install-online.ps1'
$expected = '4A540525C07CE8BD7870083E1247909E9D16EFAA7345DDD68C3D7637131B4D13'
$file = Join-Path $env:TEMP 'lanpower-install.ps1'
Invoke-WebRequest -UseBasicParsing -Uri $url -OutFile $file
if ((Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash -cne $expected) { throw 'Installer checksum mismatch.' }
powershell.exe -ExecutionPolicy Bypass -File $file -ManagementKey $key
```

Script online tiếp tục kiểm tra SHA-256 của `StandaloneAgent.ps1` trước khi cài.

Script sẽ:

- Cài agent tại `C:\ProgramData\LanPowerStandalone`.
- Giới hạn thư mục cho SYSTEM và Administrators.
- Tạo Scheduled Task `LanPowerStandaloneAgent`.
- Mở UDP 17840 chỉ cho `LocalSubnet`.

## 4. Cài APK

Cài `LanPowerStandalone-debug.apk` lên điện thoại Android 8.0 trở lên. Mở app, nhập Management Key và chọn `Quét mạng LAN`.

APK debug trong repository: `release/LanPowerStandalone-1.0.0-debug.apk`

```text
SHA-256: 586EC9A1428F10143D82CDF31C91113EDC039438FFAA83FD14D4B18E61EEF6E7
```

Điện thoại và t540 phải ở cùng broadcast domain. Wi-Fi không được bật AP/client isolation. Nếu Wi-Fi và Ethernet nằm ở hai VLAN khác nhau, router phải chuyển tiếp broadcast hoặc cần đặt chúng vào cùng VLAN.

## 5. Đóng băng ổ C

Chỉ đóng băng sau khi app phát hiện được máy và đã thử đủ Bật/Tắt/Restart.

```powershell
.\Uwf-Enable.ps1 -Restart
```

Nếu script vừa cài Windows Feature UWF, sau khi máy khởi động lại chạy lần hai:

```powershell
.\Uwf-Enable.ps1 -OverlaySizeMB 1024 -Restart
```

Kiểm tra bằng `uwfmgr.exe get-config`.

Để bảo trì:

```powershell
.\Uwf-Disable.ps1 -Restart
```

## Lưu ý bảo mật

- Ai có Management Key đều có thể tắt/restart toàn bộ máy dùng khóa đó.
- Không gửi khóa qua nhóm chat hoặc lưu công khai.
- Có thể dùng khóa khác nhau cho từng phòng/nhóm máy.
- Nếu một máy hoặc điện thoại bị lộ khóa, tạo khóa mới và cài lại cấu hình các máy trong nhóm.
- Không mở UDP 17840 ra Internet hoặc các VLAN không tin cậy.
- Không có server đồng nghĩa không có audit log tập trung; app chỉ xác nhận agent đã nhận lệnh.

## Kiểm thử trước triển khai

Pilot một t540:

1. App quét thấy đúng hostname, IP và MAC.
2. Tắt máy nhận ACK rồi máy tắt.
3. Bật máy bằng WOL và agent hoạt động lại sau boot.
4. Restart hoạt động khi UWF đang ON.
5. Tạo file thử trên C, restart và xác nhận file biến mất.
