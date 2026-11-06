# Room TN20 1.2.2 — Quản lý phòng máy qua LAN/VPN

[![Verify Room TN20](https://github.com/namnamthainguyen-ops/apklan/actions/workflows/verify.yml/badge.svg)](https://github.com/namnamthainguyen-ops/apklan/actions/workflows/verify.yml)

**Thương hiệu TN20 · Công ty TNHH TN20 · [20.com.vn](https://20.com.vn)**

Quản lý HP t540 Windows IoT từ Android trong cùng LAN: chia nhiều phòng, mỗi phòng dùng chung **key 8 ký tự do bạn chọn**, theo dõi online/offline, bật/tắt/restart và đóng/mở đóng băng ổ C. App làm việc trực tiếp với agent, không cần API hoặc máy chủ trung tâm.

Bản 1.2.0 bổ sung dò trực tiếp qua **WireGuard/OpenVPN**, WOL qua máy trung gian cùng phòng và cơ chế cập nhật có chữ ký. Máy Windows tự kiểm tra/cài bản mới khi đủ điều kiện; APK tự kiểm tra và tải bản mới rồi đưa tới bước cài đặt của Android.

Bản 1.2.1 sửa cài lại/cập nhật tệp trên Windows PowerShell 5.1. Bộ cài tự chuẩn bị UWF và giao diện Android chừa vùng thanh trạng thái/tai thỏ.

Bản 1.2.2 đưa các thao tác cập nhật vào menu **⋮** ở góc trên bên phải, để phần quản lý phòng có thêm không gian.

Mỗi phòng có danh sách máy riêng. Chạm một máy để xem tên, IP, MAC, lần phản hồi gần nhất, trạng thái đóng băng và các nút điều khiển. Chọn nhiều máy hoặc **Chọn tất cả** để thao tác cả phòng; kết quả được báo theo từng máy.

## Tải về

- **[Tải bộ cài client Windows — một file EXE](https://raw.githubusercontent.com/namnamthainguyen-ops/apklan/main/release/RoomTN20-ClientSetup-1.2.2.exe)** — mở file, nhập key phòng 8 ký tự rồi bấm Cài đặt; tự chuẩn bị UWF.
- **[Tải APK Room TN20 1.2.2](https://raw.githubusercontent.com/namnamthainguyen-ops/apklan/main/release/RoomTN20-1.2.2.apk)** — Android 8.0 trở lên, bản debug để thử nghiệm.
- [Tải toàn bộ mã nguồn và bộ cài Windows ZIP](https://github.com/namnamthainguyen-ops/apklan/archive/refs/heads/main.zip).
- [Xem script cài Windows online](https://github.com/namnamthainguyen-ops/apklan/blob/main/install-online.ps1).
- [Xem kết quả kiểm thử trên GitHub](https://github.com/namnamthainguyen-ops/apklan/actions/workflows/verify.yml).

<a id="huong-dan-cai-dat"></a>

## Cài nhanh

Khi sử dụng, điện thoại và máy Windows cần cùng LAN hoặc có đường định tuyến qua VPN; Wi-Fi cần cho phép các thiết bị liên lạc với nhau. Mỗi máy Windows cần cài agent một lần bằng quyền Administrator. Bộ cài EXE chứa sẵn agent và phần cài đặt; Windows cần .NET Framework 4.7.2 trở lên. Chuẩn bị Windows Feature UWF có thể cần nguồn thành phần Windows nếu hệ điều hành chưa có sẵn.

### Bước 1 — Chọn key cho từng phòng

Tự chọn key gồm đúng **8 ký tự ASCII in được**, phân biệt hoa/thường; có thể dùng chữ, số và ký hiệu, không dùng khoảng trắng hoặc chữ có dấu. Dùng cùng key cho tất cả máy thuộc một phòng và dùng key khác cho phòng khác. Tên phòng chỉ cần đặt trên APK; trên máy Windows chỉ cần nhập key của phòng đó khi cài agent. Không gửi key lên GitHub hoặc chia sẻ công khai.

### Bước 2 — Cài một file EXE trên từng máy Windows

1. [Tải `RoomTN20-ClientSetup-1.2.2.exe`](https://raw.githubusercontent.com/namnamthainguyen-ops/apklan/main/release/RoomTN20-ClientSetup-1.2.2.exe), có thể chép cùng file này sang tất cả máy client.
2. Mở file với quyền Administrator, nhập key của phòng rồi bấm **Cài đặt**.
3. Khi nhận thông báo thành công, đóng bộ cài. Agent tự chạy cùng Windows; trên APK tạo phòng bằng đúng key đó để nhận máy.

EXE tự cài/chuẩn bị UWF khi hệ điều hành hỗ trợ, không cần chạy script thủ công. Bộ cài báo riêng nếu cần restart hoặc không thể chuẩn bị feature. Nếu UWF chưa sẵn sàng, phần điều khiển bật/tắt vẫn được cài. Bộ cài không tự khởi động lại máy.

```text
EXE SHA-256: 61CB0C66C5EB21659E3A6382F2008FDC54B8F4A1A72CD3CE5B986834C7235103
```

Bản EXE hiện chưa ký Authenticode; Windows có thể hiển thị nhà xuất bản chưa xác định. Thương hiệu hiển thị trong ứng dụng là **Room TN20 — Công ty TNHH TN20**, website **20.com.vn**.

<details>
<summary>Cách cài thay thế bằng PowerShell online</summary>

Mở **Windows PowerShell bằng quyền Administrator** trên máy client rồi chạy nguyên đoạn sau. Khi được hỏi, nhập key 8 ký tự của phòng:

```powershell
$ErrorActionPreference = 'Stop'
$key = Read-Host 'Nhap key phong gom 8 ky tu, khong khoang trang'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$url = 'https://raw.githubusercontent.com/namnamthainguyen-ops/apklan/main/install-online.ps1'
$expected = '6FDAE5244BC3805081F6871E9D37DA18CE683F7D49F21AB37FDDC9D60E7B8456'
$file = Join-Path $env:TEMP ('lanpower-install-' + [Guid]::NewGuid().ToString('N') + '.ps1')
Invoke-WebRequest -UseBasicParsing -Uri $url -OutFile $file
if ((Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash -cne $expected) { throw 'Installer checksum mismatch.' }
Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass -Force
& $file -RoomKey $key
```

Khi cài thành công, PowerShell hiện `Standalone agent installed`. Agent tự chạy cùng Windows bằng Scheduled Task `LanPowerStandaloneAgent`; không cần mở app quản lý trên máy Windows.

Execution policy chỉ được đặt cho phiên PowerShell đang mở. Gọi trực tiếp script đã xác minh giúp giữ nguyên key chứa dấu nháy hoặc ký hiệu trên Windows PowerShell 5.1.

Bộ cài tự chuẩn bị Windows Feature UWF trên Windows IoT Enterprise, không tự đóng băng C và không tự khởi động lại. Nếu bộ cài yêu cầu restart để hoàn tất feature, khởi động lại máy một lần rồi mới dùng nút đóng băng trong APK. Windows không hỗ trợ UWF sẽ được báo rõ; chức năng quản lý nguồn vẫn hoạt động.

</details>

**Nếu ổ C đang đóng băng:** mở đóng băng và restart trước khi cài/cập nhật agent hoặc đổi key, để cấu hình mới được giữ sau khi boot. Bộ cài sẽ từ chối ghi khi phát hiện C đang được bảo vệ.

### Bước 3 — Cài APK và quản lý máy

1. [Tải APK Room TN20 1.2.2 trên điện thoại](https://raw.githubusercontent.com/namnamthainguyen-ops/apklan/main/release/RoomTN20-1.2.2.apk), cho phép trình duyệt hoặc trình quản lý tệp cài ứng dụng khi Android yêu cầu.
2. Mở **Room TN20**, thêm phòng, đặt tên phòng và nhập đúng key 8 ký tự đã cài trên các máy.
3. Mở phòng, chọn **Quét mạng LAN**. Máy dùng đúng key sẽ được đưa vào danh sách phòng; app lưu lại tên, IP và MAC để tiếp tục hiển thị khi máy offline.
4. Chạm một máy để xem chi tiết và dùng **Bật**, **Tắt**, **Restart**, **Đóng băng C:** hoặc **Mở đóng băng C:**.
5. Chọn các máy hoặc **Chọn tất cả**, rồi chọn thao tác hàng loạt. Lệnh tắt/restart/đóng băng bỏ qua máy offline; Wake-on-LAN gửi tới MAC đã lưu của các máy được chọn.

App cập nhật trạng thái khi đang mở. **Offline** nghĩa là agent không phản hồi ở lần kiểm tra gần nhất, có thể do máy tắt hoặc mạng/firewall; đây không phải xác nhận nguồn điện của máy. Máy chưa từng được phát hiện khi online chưa thể xuất hiện trong danh sách offline hoặc được bật từ APK vì app chưa biết MAC.

Nút Bật cần cấu hình Wake-on-LAN trong BIOS và card mạng theo hướng dẫn bên dưới; máy vẫn cần cắm điện và cáp Ethernet. Đóng/mở đóng băng thay đổi trạng thái **sau lần khởi động lại kế tiếp**; app không tự reboot cả phòng.

Nếu chưa quét thấy máy, kiểm tra khóa hai bên giống nhau, agent đã cài thành công, Wi-Fi không bật AP/client isolation và hai thiết bị cùng VLAN. Đồng hồ điện thoại và Windows cần lệch dưới 2 phút để lệnh tắt/restart được chấp nhận.

Các phần tiếp theo hướng dẫn cài offline, cấu hình Wake-on-LAN và đóng băng ổ C bằng UWF.

## Kiến trúc

```text
Android APK ── UDP broadcast 17840 ──► phát hiện HP t540
Android APK ── UDP 17840 + HMAC ─────► tắt/restart
Android APK ── UDP broadcast 9 ──────► Wake-on-LAN
```

## Kết nối qua WireGuard/OpenVPN

LAN vẫn dùng broadcast để nhận máy tự động. VPN dạng routed/TUN thường không chuyển broadcast; vì vậy trong phần **Mạng LAN/VPN** của từng phòng, nhập các IP hoặc dải mạng mà điện thoại có thể định tuyến tới, ví dụ `10.8.0.12` hoặc `192.168.20.0/24`. App gửi discovery có chữ ký trực tiếp tới các địa chỉ đó và các IP máy đã lưu. Không cần máy chủ API.

- Mỗi phòng tối đa 1.024 địa chỉ; không quét cả dải VPN khổng lồ. Hỗ trợ IPv4 riêng/CGNAT và các CIDR từ `/22` trở lên trong phạm vi giới hạn.
- Trong bộ cài Windows, mục VPN cho phép điền IP/dải nguồn của máy quản trị, ví dụ `10.8.0.0/24`. Firewall chỉ mở UDP 17840 cho `LocalSubnet` và các nguồn được cấu hình, không mở toàn bộ Internet.
- WireGuard phải có `AllowedIPs`/route phù hợp; OpenVPN phải có route và chính sách client-to-client hoặc site-to-site cho phép UDP 17840. Ứng dụng không tự thay cấu hình router/VPN.
- Wi-Fi client isolation, firewall hoặc đường về không đúng vẫn có thể chặn liên lạc dù thiết bị đã kết nối VPN.

Bật máy qua VPN cần một agent đang online trong cùng LAN của phòng để phát Magic Packet hộ. App chọn một máy cùng phòng có khả năng WOL relay đã được xác thực. Nếu cả phòng đều tắt, cần router/thiết bị WOL riêng hoặc một máy relay luôn bật. Gửi WOL thành công không xác nhận máy đã boot; hãy quét lại.

Tham khảo [routing OpenVPN](https://openvpn.net/community-docs/setting-up-routing/) và [khác biệt TUN/TAP](https://community.openvpn.net/Pages/Getting%20started%20with%20OpenVPN).

## Kiểm tra và tự nâng cấp

Client và APK hiển thị phiên bản đang dùng, phiên bản mới và tình trạng kiểm tra/cập nhật. Bản phát hành được mô tả trong `updates/stable.json`; chữ ký RSA-SHA256 được kiểm tra bằng khóa công khai nhúng trong phần mềm, sau đó từng tệp tải về được đối chiếu SHA-256.

- **Windows:** task cập nhật kiểm tra định kỳ và lúc khởi động, giữ key, deviceId và cấu hình VPN. Khi C đang đóng băng, cập nhật chuyển sang trạng thái chờ; phần mềm không tự mở đóng băng hoặc reboot. Mở bảo vệ C và restart khi bạn sẵn sàng bảo trì, sau đó cập nhật mới được ghi bền vững.
- **Android:** tự kiểm tra và tải APK đã xác minh. Android thông thường vẫn có thể yêu cầu cấp quyền cài từ nguồn này và xác nhận cài đặt; app dùng bước cài đặt chính thức, không vượt quyền hệ thống. [Quy tắc xác nhận của Android](https://developer.android.com/reference/android/content/pm/PackageInstaller.SessionParams#setRequireUserAction(int)).
- Trên APK, mở menu **⋮** ở góc trên bên phải rồi chọn **Kiểm tra cập nhật**, **Cài bản mới** hoặc **Trạng thái cập nhật**. Mục cài đặt tiếp tục dùng xác nhận của Android khi hệ thống yêu cầu.
- Kiểm tra đúng package/chứng thư APK, từ chối chữ ký sai, checksum sai, URL ngoài nguồn cho phép và phiên bản thấp hơn. Có Internet mới kiểm tra được bản phát hành trên GitHub; chức năng quản lý LAN/VPN vẫn dùng dữ liệu cục bộ khi không có Internet.
- Bản 1.1.x chưa có bộ tự cập nhật: cần cài 1.2.2 một lần trên điện thoại và máy client để bắt đầu nhận các bản mới tiếp theo.
- Client Windows 1.2.0 thử nghiệm cần chạy lại EXE 1.2.2 để sửa lỗi thay thế tệp; từ client 1.2.1 trở lên có thể nhận cập nhật tự động khi C không đang được bảo vệ.

Khóa ký riêng của nhà phát hành không nằm trong repository hoặc bộ cài. Nếu phát hành phiên bản mới, sử dụng `updates/Publish-UpdateManifest.ps1` với khóa riêng được bảo vệ; không đặt khóa riêng lên GitHub hoặc trong tệp ZIP mã nguồn.

Mỗi t540 cần cài standalone agent. Agent chạy bằng Scheduled Task dưới tài khoản SYSTEM, mở UDP 17840 cho `LocalSubnet` và các nguồn VPN được cấu hình, chỉ chấp nhận lệnh có Management Key đúng.

## 1. Key phòng và tương thích bản cũ

Key 8 ký tự được chuyển thành khóa giao thức 32 byte bằng PBKDF2-HMAC-SHA256. APK và bộ cài dùng cùng quy tắc; không gửi key thô trong gói UDP. Key phòng được bảo vệ bằng Android Keystore; agent lưu khóa đã dẫn xuất trong thư mục chỉ Administrators/SYSTEM truy cập được.

Bản cũ dùng `-ManagementKey` Base64 32 byte vẫn được hỗ trợ để nâng cấp. APK chuyển khóa và danh sách đã lưu sang một phòng mặc định; cần cập nhật agent mới để dùng các nút UWF và metadata trạng thái được ký. Để chuyển sang key 8 ký tự, cài lại các máy bằng EXE hoặc `-RoomKey` và tạo phòng tương ứng trên APK. Định danh package Android, nơi lưu dữ liệu và mã task Windows được giữ nguyên để nâng cấp không mất dữ liệu.

## 2. Cấu hình HP t540

Trong BIOS (`F10`):

1. `Advanced` → `Device Options`.
2. Bật `S5 Wake on LAN`.
3. Tắt `S5 Maximum Power Savings`.
4. Cắm cáp Ethernet và giữ nguồn điện kết nối.

Trong Windows bật `Wake on Magic Packet` của card Ethernet nếu driver có tùy chọn này.

## 3. Cài agent trên mỗi máy

Ưu tiên dùng [một file EXE ở Bước 2](#huong-dan-cai-dat). Nếu cần dùng mã nguồn để cài offline, tải [ZIP mã nguồn](https://github.com/namnamthainguyen-ops/apklan/archive/refs/heads/main.zip), giải nén toàn bộ rồi mở PowerShell Administrator trong thư mục `client`. Giữ đủ năm tệp `Install-Agent.ps1`, `Install-Core.ps1`, `StandaloneAgent.ps1`, `Update-Agent.ps1` và `Update-PublicKey.xml` cạnh nhau:

```powershell
$key = Read-Host 'Nhap key phong gom 8 ky tu, khong khoang trang'
Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass -Force
& .\Install-Agent.ps1 -RoomKey $key
```

Để cài online, mở phần PowerShell trong [Bước 2](#huong-dan-cai-dat) phía đầu trang. Lệnh tải installer về file tạm, kiểm tra SHA-256 rồi mới chạy. Installer tiếp tục kiểm tra SHA-256 của agent, phần cài đặt, updater và khóa công khai trước khi cài.

Script sẽ:

- Cài agent tại `C:\ProgramData\LanPowerStandalone`.
- Giới hạn thư mục cho SYSTEM và Administrators.
- Tạo Scheduled Task `LanPowerStandaloneAgent` và task kiểm tra cập nhật mỗi 6 giờ.
- Mở UDP 17840 cho `LocalSubnet` và các nguồn VPN được chỉ định.
- Tự chuẩn bị UWF nếu Windows hỗ trợ; báo riêng khi cần restart.

## 4. Cài APK

Cài APK lên điện thoại Android 8.0 trở lên. Mở Room TN20, thêm phòng bằng tên và key 8 ký tự rồi chọn `Quét LAN`.

APK debug trong repository: `release/RoomTN20-1.2.2.apk`. Cùng chứng thư với bản 1.0.x/1.1.x để cài nâng cấp; bản cũ được giữ lại phục vụ kiểm thử nâng cấp. Giao diện dùng vùng an toàn status bar/tai thỏ và thanh điều hướng, không đè lên giờ/thông báo của điện thoại.

```text
APK SHA-256: A6D6A4314D80D5ABD29B262BE36FD96DD37502849FBAA75CDD92DFE457A08709
```

Để tự tìm bằng broadcast, điện thoại và t540 cần cùng broadcast domain, Wi-Fi không bật AP/client isolation. Nếu khác VLAN hoặc kết nối VPN, cấu hình IP/dải mạng trực tiếp trong mục **Mạng LAN/VPN** của phòng và cho phép định tuyến/firewall tương ứng.

Đồng hồ điện thoại và Windows cần lệch dưới 2 phút để lệnh có timestamp được chấp nhận.

## 5. Đóng/mở đóng băng ổ C từ APK

Chỉ đóng băng sau khi agent đã được cài bền vững, APK phát hiện được máy và đã thử đủ Bật/Tắt/Restart.

- **Đóng băng C:** đặt bảo vệ C bằng UWF cho phiên boot kế tiếp.
- **Mở đóng băng C:** bỏ bảo vệ C cho phiên boot kế tiếp; giữ cấu hình các ổ khác.
- App hiển thị cả trạng thái hiện tại và trạng thái sau reboot. Sau thao tác, chọn **Restart** khi bạn sẵn sàng; kết quả ACK không có nghĩa trạng thái mới đã có hiệu lực ngay.
- Với thao tác cả phòng, đọc kết quả từng máy. Máy offline hoặc không hỗ trợ UWF được báo riêng; app không coi các máy đó đã hoàn thành.

Nếu máy chưa có UWF, bộ cài client tự chuẩn bị feature. Cài lại client mới rồi restart nếu được yêu cầu; không cần thao tác PowerShell chuẩn bị riêng. Windows Home/Pro không hỗ trợ UWF; Windows IoT Enterprise, Enterprise và Education có hỗ trợ. [Tài liệu Microsoft](https://learn.microsoft.com/en-us/windows/configuration/unified-write-filter/).

Nếu trước đó cài online, tải [ZIP mã nguồn](https://github.com/namnamthainguyen-ops/apklan/archive/refs/heads/main.zip), giải nén và mở PowerShell Administrator trong thư mục `client` để có các script UWF bên dưới.

Các script bên dưới chỉ dành cho bảo trì kỹ thuật tùy chọn; người dùng bình thường dùng bộ cài EXE và các nút trong APK. Chuẩn bị feature riêng, không tự đóng băng hoặc restart:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\Prepare-Uwf.ps1
```

Để bảo trì tại máy bằng script cũ (bật/tắt filter UWF toàn hệ thống, có thể ảnh hưởng các ổ khác):

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

- Ai có key phòng đều có thể gửi lệnh quản lý tới các máy dùng key đó, gồm tắt/restart và thay đổi bảo vệ C.
- Không gửi khóa qua nhóm chat hoặc lưu công khai.
- Dùng key khác nhau cho từng phòng; không đặt cùng key cho hai phòng nếu muốn tách chúng.
- Nếu một máy hoặc điện thoại bị lộ khóa, tạo khóa mới và cài lại cấu hình các máy trong nhóm.
- Không mở UDP 17840 ra Internet hoặc các VLAN không tin cậy.
- Không có server đồng nghĩa không có audit log tập trung; app chỉ xác nhận agent đã nhận lệnh.

## Kiểm thử trước triển khai

GitHub Actions tự chạy khi push vào `main`, khi mở pull request hoặc bấm `Run workflow` trong tab [Actions](https://github.com/namnamthainguyen-ops/apklan/actions/workflows/verify.yml). Mỗi lượt tạo APK mới, báo cáo unit test/lint và ảnh màn hình máy ảo trong mục Artifacts.

- Windows PowerShell 5.1: phân tích cú pháp mọi script, kiểm tra HMAC và gửi UDP thật tới bản agent thử nghiệm với hàm tắt máy được thay bằng bộ ghi nhận.
- Giao thức: khóa sai, chữ ký sai, gói lỗi, timestamp quá hạn, replay, sai thiết bị và action không hỗ trợ.
- Installer: cài trên Windows runner tạm, xác nhận Scheduled Task SYSTEM, firewall `LocalSubnet`, ACL, cài lại và gỡ bỏ.
- EXE: build từ source, kiểm tra payload nhúng/KDF/giao diện nội bộ và thử cài bằng đúng file EXE phát hành trên Windows runner tạm.
- Android: unit test cho code giao thức thực tế, lint và build APK từ source.
- Key phòng: đối chiếu PBKDF2 giữa Node.js, Windows PowerShell và Java; từ chối sai độ dài/định dạng.
- Máy ảo Android API 26 và 35: nhiều phòng, khóa mã hóa, cách ly danh sách, thiết bị giả lập online/offline, thao tác từng máy/hàng loạt và nâng cấp giữ dữ liệu. Các agent giả lập chỉ ghi nhận lệnh, không điều khiển máy thật.

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
