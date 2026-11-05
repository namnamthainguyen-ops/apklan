# LanPower Standalone 1.0.1

[![Verify LAN Power](https://github.com/namnamthainguyen-ops/apklan/actions/workflows/verify.yml/badge.svg)](https://github.com/namnamthainguyen-ops/apklan/actions/workflows/verify.yml)

Giải pháp bật, tắt và khởi động lại HP t540 Windows IoT từ Android trong cùng LAN, không cần API hoặc máy chủ trung tâm.

## Tải về

- **[Tải APK Android 1.0.1](https://raw.githubusercontent.com/namnamthainguyen-ops/apklan/main/release/LanPowerStandalone-1.0.1-debug.apk)** — Android 8.0 trở lên, bản debug để thử nghiệm.
- [Tải toàn bộ mã nguồn và bộ cài Windows ZIP](https://github.com/namnamthainguyen-ops/apklan/archive/refs/heads/main.zip).
- [Xem script cài Windows online](https://github.com/namnamthainguyen-ops/apklan/blob/main/install-online.ps1).
- [Xem kết quả kiểm thử trên GitHub](https://github.com/namnamthainguyen-ops/apklan/actions/workflows/verify.yml).

<a id="huong-dan-cai-dat"></a>

## Cài nhanh

Máy Windows cần Internet khi tải bộ cài online. Khi sử dụng, điện thoại và máy Windows phải cùng LAN; Wi-Fi cần cho phép các thiết bị liên lạc với nhau. Mỗi máy Windows cần cài agent một lần bằng quyền Administrator.

### Bước 1 — Tạo khóa quản lý một lần

Mở **Windows PowerShell → Run as administrator**, dán đoạn sau để tạo Management Key:

```powershell
$keyBytes = New-Object byte[] 32
$rng = [Security.Cryptography.RandomNumberGenerator]::Create()
try { $rng.GetBytes($keyBytes) } finally { $rng.Dispose() }
$key = [Convert]::ToBase64String($keyBytes)
Write-Host $key
```

Lưu khóa vừa hiện ra ở nơi riêng tư. Dùng **cùng khóa này** cho APK và tất cả máy Windows thuộc cùng nhóm quản lý.

### Bước 2 — Cài online trên từng máy Windows

Mở **Windows PowerShell bằng quyền Administrator** trên máy client. Thay `DAN-KHOA-BASE64-VAO-DAY` bằng khóa ở bước 1, rồi chạy nguyên đoạn sau:

```powershell
$ErrorActionPreference = 'Stop'
$key = 'DAN-KHOA-BASE64-VAO-DAY'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$url = 'https://raw.githubusercontent.com/namnamthainguyen-ops/apklan/main/install-online.ps1'
$expected = '312E174FCEBBF8675D4944DA46A96D52AB61E41A62579D9C95F602CD040760BF'
$file = Join-Path $env:TEMP ('lanpower-install-' + [Guid]::NewGuid().ToString('N') + '.ps1')
Invoke-WebRequest -UseBasicParsing -Uri $url -OutFile $file
if ((Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash -cne $expected) { throw 'Installer checksum mismatch.' }
powershell.exe -NoProfile -ExecutionPolicy Bypass -File $file -ManagementKey $key
if ($LASTEXITCODE -ne 0) { throw 'Agent installation failed. Check the error above.' }
```

Khi cài thành công, PowerShell hiện `Standalone agent installed`. Agent tự chạy cùng Windows bằng Scheduled Task `LanPowerStandaloneAgent`; không cần mở app quản lý trên máy Windows.

### Bước 3 — Cài APK và quản lý máy

1. [Tải APK 1.0.1 trên điện thoại](https://raw.githubusercontent.com/namnamthainguyen-ops/apklan/main/release/LanPowerStandalone-1.0.1-debug.apk), cho phép trình duyệt hoặc trình quản lý tệp cài ứng dụng khi Android yêu cầu.
2. Mở **LanPower Standalone**, nhập khóa bước 1 rồi chọn **Lưu khóa**.
3. Kết nối điện thoại vào cùng LAN với máy client, chọn **Quét mạng LAN**.
4. Khi máy xuất hiện, dùng **Bật**, **Tắt** hoặc **Restart**. Nút Bật cần cấu hình Wake-on-LAN trong BIOS và card mạng theo hướng dẫn bên dưới; máy vẫn cần cắm điện và cáp Ethernet.

Nếu chưa quét thấy máy, kiểm tra khóa hai bên giống nhau, agent đã cài thành công, Wi-Fi không bật AP/client isolation và hai thiết bị cùng VLAN. Đồng hồ điện thoại và Windows cần lệch dưới 2 phút để lệnh tắt/restart được chấp nhận.

Các phần tiếp theo hướng dẫn cài offline, cấu hình Wake-on-LAN và đóng băng ổ C bằng UWF.

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
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\client\New-ManagementKey.ps1
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

Để cài offline, tải [ZIP mã nguồn](https://github.com/namnamthainguyen-ops/apklan/archive/refs/heads/main.zip), giải nén toàn bộ rồi mở PowerShell Administrator trong thư mục `client`. Giữ đủ ba tệp `Install-Agent.ps1`, `Install-Core.ps1` và `StandaloneAgent.ps1` cạnh nhau:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\Install-Agent.ps1 -ManagementKey "KHOA-BASE64-DA-TAO"
```

Để cài online, dùng nguyên lệnh ở [Bước 2 — Cài online](#huong-dan-cai-dat) phía đầu trang. Lệnh tải installer về file tạm, kiểm tra SHA-256 rồi mới chạy. Installer tiếp tục kiểm tra SHA-256 của cả `StandaloneAgent.ps1` và `Install-Core.ps1` trước khi cài.

Script sẽ:

- Cài agent tại `C:\ProgramData\LanPowerStandalone`.
- Giới hạn thư mục cho SYSTEM và Administrators.
- Tạo Scheduled Task `LanPowerStandaloneAgent`.
- Mở UDP 17840 chỉ cho `LocalSubnet`.

## 4. Cài APK

Cài APK lên điện thoại Android 8.0 trở lên. Mở app, nhập Management Key và chọn `Quét mạng LAN`.

APK debug trong repository: `release/LanPowerStandalone-1.0.1-debug.apk`. Cùng chứng thư với bản 1.0.0 để cài nâng cấp; bản 1.0.0 được giữ lại phục vụ kiểm thử nâng cấp.

```text
SHA-256: 758B0E24F467455F14CE7A0F03B7B132E762D78698BD0CAA6A2E3CFF2C37B10F
```

Điện thoại và t540 phải ở cùng broadcast domain. Wi-Fi không được bật AP/client isolation. Nếu Wi-Fi và Ethernet nằm ở hai VLAN khác nhau, router phải chuyển tiếp broadcast hoặc cần đặt chúng vào cùng VLAN.

Đồng hồ điện thoại và Windows cần lệch dưới 2 phút để lệnh có timestamp được chấp nhận.

## 5. Đóng băng ổ C

Chỉ đóng băng sau khi app phát hiện được máy và đã thử đủ Bật/Tắt/Restart.

Nếu trước đó cài online, tải [ZIP mã nguồn](https://github.com/namnamthainguyen-ops/apklan/archive/refs/heads/main.zip), giải nén và mở PowerShell Administrator trong thư mục `client` để có các script UWF bên dưới.

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\Uwf-Enable.ps1 -Restart
```

Nếu script vừa cài Windows Feature UWF, sau khi máy khởi động lại chạy lần hai:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\Uwf-Enable.ps1 -OverlaySizeMB 1024 -Restart
```

Kiểm tra bằng `uwfmgr.exe get-config`.

Để bảo trì:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\Uwf-Disable.ps1 -Restart
```

## Lưu ý bảo mật

- Ai có Management Key đều có thể tắt/restart toàn bộ máy dùng khóa đó.
- Không gửi khóa qua nhóm chat hoặc lưu công khai.
- Có thể dùng khóa khác nhau cho từng phòng/nhóm máy.
- Nếu một máy hoặc điện thoại bị lộ khóa, tạo khóa mới và cài lại cấu hình các máy trong nhóm.
- Không mở UDP 17840 ra Internet hoặc các VLAN không tin cậy.
- Không có server đồng nghĩa không có audit log tập trung; app chỉ xác nhận agent đã nhận lệnh.

## Kiểm thử trước triển khai

GitHub Actions tự chạy khi push vào `main`, khi mở pull request hoặc bấm `Run workflow` trong tab [Actions](https://github.com/namnamthainguyen-ops/apklan/actions/workflows/verify.yml). Mỗi lượt tạo APK mới, báo cáo unit test/lint và ảnh màn hình máy ảo trong mục Artifacts.

- Windows PowerShell 5.1: phân tích cú pháp mọi script, kiểm tra HMAC và gửi UDP thật tới bản agent thử nghiệm với hàm tắt máy được thay bằng bộ ghi nhận.
- Giao thức: khóa sai, chữ ký sai, gói lỗi, timestamp quá hạn, replay, sai thiết bị và action không hỗ trợ.
- Installer: cài trên Windows runner tạm, xác nhận Scheduled Task SYSTEM, firewall `LocalSubnet`, ACL, cài lại và gỡ bỏ.
- Android: unit test cho code giao thức thực tế, lint và build APK từ source.
- Máy ảo Android API 26 và 35: cài APK, mở Activity, lưu khóa mã hóa trong Keystore, tải lại khóa sau khi khởi động lại app, quét LAN, lưu logcat/UI XML/ảnh màn hình.

Chạy kiểm thử giao thức cục bộ trên Windows:

```powershell
npm test
```

`tests/Install-Smoke.ps1` thay đổi Scheduled Task/firewall trên máy chạy, chỉ dành cho Windows runner thử nghiệm có quyền Administrator. Các bài kiểm thử tự động không thực hiện tắt/restart máy thật và không bật UWF.

Giới hạn: GitHub-hosted runner không nằm trong LAN của bạn và không có BIOS/NIC HP t540 hoặc Windows IoT UWF. Kết quả xanh trên CI chưa xác nhận Wake-on-LAN từ S5, Wi-Fi/VLAN thực tế hoặc việc hoàn nguyên ổ C.

Pilot một t540:

1. App quét thấy đúng hostname, IP và MAC.
2. Tắt máy nhận ACK rồi máy tắt.
3. Bật máy bằng WOL và agent hoạt động lại sau boot.
4. Restart hoạt động khi UWF đang ON.
5. Tạo file thử trên C, restart và xác nhận file biến mất.
