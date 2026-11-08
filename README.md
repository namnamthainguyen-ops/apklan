# Room TN20 — Windows 1.4.0 / Android 1.4.0

[![Verify Room TN20](https://github.com/namnamthainguyen-ops/apklan/actions/workflows/verify.yml/badge.svg)](https://github.com/namnamthainguyen-ops/apklan/actions/workflows/verify.yml)

**Thương hiệu TN20 · Công ty TNHH TN20 · [20.com.vn](https://20.com.vn)**

Quản lý HP t540 Windows IoT từ Android trong LAN/VPN: chia nhiều phòng, mỗi phòng dùng chung **key 8 ký tự do bạn chọn**, theo dõi online/offline, bật/tắt/restart, đóng/mở đóng băng C: và khóa/mở sử dụng máy với quảng cáo ảnh/video MP4. App làm việc trực tiếp với từng máy, không cần API hoặc máy chủ trung tâm.

Bản 1.2.0 bổ sung dò trực tiếp qua **WireGuard/OpenVPN**, WOL qua máy trung gian cùng phòng và cơ chế cập nhật có chữ ký. Máy Windows tự kiểm tra/cài bản mới khi đủ điều kiện; APK tự kiểm tra và tải bản mới rồi đưa tới bước cài đặt của Android.

Bản 1.2.1 sửa cài lại/cập nhật tệp trên Windows PowerShell 5.1. Bộ cài tự chuẩn bị UWF và giao diện Android chừa vùng thanh trạng thái/tai thỏ.

Bản 1.2.2 đưa các thao tác cập nhật vào menu **⋮** ở góc trên bên phải, để phần quản lý phòng có thêm không gian.

Bản 1.2.3 chuẩn bị UWF qua công cụ Windows gốc, kiểm tra lại feature và phản hồi xác thực của client. Bộ cài hỗ trợ cài đè và tự đóng sau 3 giây khi hoàn tất. APK phát hành đã tắt debug, giữ cùng chứng thư để nâng cấp mà không mất phòng đã lưu.

Client nhận biết Windows Server 2025, Home và Pro: cài phần quản lý nguồn thành công, bỏ qua UWF không được hệ điều hành hỗ trợ rồi tự đóng. Máy Windows IoT Enterprise vẫn được chuẩn bị UWF.

**Bản 1.3.0:** khóa/mở sử dụng từng máy hoặc nhiều máy; **mỗi phòng có một bộ quảng cáo riêng**. Chọn ảnh/MP4 trên điện thoại và gửi tới các máy online trong phòng đang mở. Nội dung và trạng thái khóa lưu trên `D:\RoomTN20Data`, nên có thể thay quảng cáo khi C: vẫn đóng băng. Tải lỗi giữ bộ quảng cáo trước; máy offline được báo chưa cập nhật để gửi lại sau.

**Bản 1.3.1:** Mạng LAN/VPN và Quảng cáo phòng nằm ngang hàng. Trước khi gửi ảnh/video, app quét và xác thực lại máy con; hiển thị phiên bản và nguyên nhân bộ nhận quảng cáo chưa hoạt động. Bộ cài xác minh riêng bộ nhận trước khi báo sẵn sàng; client thử lại nếu ổ dữ liệu chưa sẵn sàng lúc Windows khởi động. Sửa gửi lại ngay sau khi tải lỗi.

**Client 1.3.3:** sửa cả hai lỗi quyền khiến quảng cáo báo nhầm chưa có phiên người dùng: quyền kết nối của player và yêu cầu tài khoản thường đọc đường dẫn tiến trình SYSTEM. Đã kiểm thử tái hiện Access Denied và xác thực broker SYSTEM thật sau sửa.

Mỗi phòng có danh sách máy riêng. Chạm một máy để xem tên, IP, MAC, lần phản hồi gần nhất, trạng thái đóng băng và các nút điều khiển. Chọn nhiều máy hoặc **Chọn tất cả** để thao tác cả phòng; kết quả được báo theo từng máy.

**Bản 1.4.0:** đồng bộ quảng cáo theo nội dung: chỉ tải tệp còn thiếu hoặc hỏng, giữ tệp đã có, báo tệp tải thêm và tệp bỏ khỏi danh sách phát. Đổi thứ tự hoặc thời lượng không tải lại ảnh/video. Client tự nhận định danh phần cứng khi ghost sang máy khác, giữ mã phòng và cấu hình đã cài.

## Ghost nhiều máy

Cài client **1.4.0** trên máy mẫu, nhập key phòng, cấu hình VPN nếu cần và để màn hình quảng cáo ở trạng thái mở sử dụng trước khi tạo ảnh ghost. Khi chạy trên máy đích, client tự dùng UUID phần cứng (hoặc MAC card mạng vật lý khi UUID không hợp lệ), đọc tên/MAC hiện tại; app quét phòng sẽ nhận IP và phiên bản client/UWF đang chạy. Không dùng MachineGuid/SID hay GUID sao chép trong file cấu hình để phân biệt máy. Định danh được tính lúc khởi động nên vẫn ổn định khi C: đóng băng, không cần ghi lại C: hoặc nhập lại key trên từng máy cùng phòng.

- Giữ key, phạm vi VPN và phần mềm đã có trong ảnh ghost; client không tự cài lại toàn bộ ứng dụng Windows. Việc tổng quát hóa Windows/driver vẫn thuộc quy trình tạo ghost của bạn.
- Quảng cáo giữ được nếu ổ D: đã có dữ liệu `D:\RoomTN20Data`; nếu chỉ ghost C: sang D: trống, gửi quảng cáo một lần từ app. Những lần sau chỉ tải phần thiếu.
- Ghost sang phòng khác: cài đè với key phòng mới. Với máy ảo, cấp UUID phần cứng riêng cho từng bản clone. Khi nâng cấp từ bản định danh cũ, app có thể giữ một dòng máy cũ offline; có thể xóa dòng cũ sau khi kiểm tra máy mới.
- Dùng lại tệp chỉ sau khi kiểm tra SHA-256 của bản sao trên máy. Tệp bỏ ngừng phát sau khi đồng bộ thành công; giữ tối đa một bộ trước để dự phòng, rồi dọn các bộ cũ hơn. Tải lỗi không thay bộ đang phát. Cập nhật **cả APK và client 1.4.0** để dùng đồng bộ này; APK báo cần nâng cấp khi gặp client cũ.

## Tải về

- **[Tải bộ cài client Windows 1.4.0 — một file EXE](https://raw.githubusercontent.com/namnamthainguyen-ops/apklan/main/release/RoomTN20-ClientSetup-1.4.0.exe)** — nhập key phòng rồi chọn **Cài / Cài đè**; chứa sẵn phần khóa/quảng cáo và tự chuẩn bị UWF khi được hỗ trợ.
- **[Tải APK Room TN20 1.4.0](https://raw.githubusercontent.com/namnamthainguyen-ops/apklan/main/release/RoomTN20-1.4.0.apk)** — Android 8.0 trở lên, cùng chữ ký để nâng cấp và giữ dữ liệu phòng.
- [Tải toàn bộ mã nguồn và bộ cài Windows ZIP](https://github.com/namnamthainguyen-ops/apklan/archive/refs/heads/main.zip).
- [Xem script cài Windows online](https://github.com/namnamthainguyen-ops/apklan/blob/main/install-online.ps1).
- [Xem kết quả kiểm thử trên GitHub](https://github.com/namnamthainguyen-ops/apklan/actions/workflows/verify.yml).

<a id="huong-dan-cai-dat"></a>

## Cài nhanh

Khi sử dụng, điện thoại và máy Windows cần cùng LAN hoặc có đường định tuyến qua VPN; Wi-Fi cần cho phép các thiết bị liên lạc với nhau. Mỗi máy Windows cần cài agent một lần bằng quyền Administrator. Bộ cài EXE chứa sẵn agent và phần cài đặt; Windows cần .NET Framework 4.7.2 trở lên. Chuẩn bị Windows Feature UWF có thể cần nguồn thành phần Windows nếu hệ điều hành chưa có sẵn.

### Bước 1 — Chọn key cho từng phòng

Tự chọn key gồm đúng **8 ký tự ASCII in được**, phân biệt hoa/thường; có thể dùng chữ, số và ký hiệu, không dùng khoảng trắng hoặc chữ có dấu. Dùng cùng key cho tất cả máy thuộc một phòng và dùng key khác cho phòng khác. Tên phòng chỉ cần đặt trên APK; trên máy Windows chỉ cần nhập key của phòng đó khi cài agent. Không gửi key lên GitHub hoặc chia sẻ công khai.

### Bước 2 — Cài một file EXE trên từng máy Windows

1. [Tải `RoomTN20-ClientSetup-1.4.0.exe`](https://raw.githubusercontent.com/namnamthainguyen-ops/apklan/main/release/RoomTN20-ClientSetup-1.4.0.exe), có thể chép cùng file này sang tất cả máy client. Để dùng quảng cáo, chuẩn bị ổ D: chưa đóng băng.
2. Mở file với quyền Administrator, nhập key của phòng rồi bấm **Cài / Cài đè**. Không cần gỡ bản cũ; định danh theo phần cứng và mạng VPN đã lưu được giữ lại nếu không nhập cấu hình mới.
3. Bộ cài kiểm tra client đã phản hồi đúng khóa/phiên bản, báo **Cài đặt thành công** rồi tự đóng sau 3 giây. Nếu UWF cần restart, thông báo sẽ ghi rõ; bạn tự khởi động lại khi sẵn sàng.

EXE tự cài/chuẩn bị UWF khi hệ điều hành hỗ trợ, không cần chạy script thủ công. Bộ cài báo riêng nếu cần restart hoặc không thể chuẩn bị feature. Nếu UWF chưa sẵn sàng, phần điều khiển bật/tắt vẫn được cài. Bộ cài không tự khởi động lại máy.

**Windows Server 2025/Home/Pro:** UWF không được hỗ trợ; bộ cài cài client quản lý nguồn, báo rõ giới hạn này rồi tự đóng sau 3 giây. Các máy đó vẫn dùng được bật/tắt/restart và WOL relay; đóng băng C không dùng được. [Các bản Windows hỗ trợ UWF](https://learn.microsoft.com/en-us/windows/configuration/unified-write-filter/).

**Windows IoT Enterprise/Enterprise/Education:** nếu chuẩn bị UWF bị lỗi thật, bộ cài giữ thông báo và không tự đóng như một lần cài hoàn tất. Mã lỗi DISM, phiên bản Windows và đường dẫn nhật ký được hiển thị để xử lý đúng nguyên nhân. Nhật ký chuẩn bị UWF nằm tại `C:\ProgramData\LanPowerStandalone\uwf-preparation.log`. Thiếu nguồn thành phần Windows hoặc chính sách Windows Update/WSUS có thể cần sửa nguồn cài tương ứng; bộ cài không báo UWF thành công khi kiểm tra thất bại.

```text
EXE SHA-256: 1EBC5497F8FAC27069171B41DE3C151C4268C14CAB8576A380CD83CDA0D0417B
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
$expected = '67D7050E39BEEEBFB5F60BA7C88844765AF282B155C0E0BA9FC280503D11DF88'
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

1. [Tải APK Room TN20 1.4.0 trên điện thoại](https://raw.githubusercontent.com/namnamthainguyen-ops/apklan/main/release/RoomTN20-1.4.0.apk), cho phép trình duyệt hoặc trình quản lý tệp cài ứng dụng từ nguồn này khi Android yêu cầu. Nếu Play Protect đề nghị quét, chọn quét và đợi kết quả.
2. Mở **Room TN20**, thêm phòng, đặt tên phòng và nhập đúng key 8 ký tự đã cài trên các máy.
3. Mở phòng, chọn **Quét mạng LAN**. Máy dùng đúng key sẽ được đưa vào danh sách phòng; app lưu lại tên, IP và MAC để tiếp tục hiển thị khi máy offline.
4. Chạm một máy để xem chi tiết và dùng **Bật**, **Tắt**, **Restart**, **Khóa**, **Mở khóa**, **Đóng băng C:** hoặc **Mở đóng băng C:**.
5. Chọn các máy hoặc **Chọn tất cả**, rồi chọn thao tác hàng loạt. Lệnh tắt/restart/đóng băng bỏ qua máy offline; Wake-on-LAN gửi tới MAC đã lưu của các máy được chọn.

App cập nhật trạng thái khi đang mở. **Offline** nghĩa là agent không phản hồi ở lần kiểm tra gần nhất, có thể do máy tắt hoặc mạng/firewall; đây không phải xác nhận nguồn điện của máy. Máy chưa từng được phát hiện khi online chưa thể xuất hiện trong danh sách offline hoặc được bật từ APK vì app chưa biết MAC.

Nút Bật cần cấu hình Wake-on-LAN trong BIOS và card mạng theo hướng dẫn bên dưới; máy vẫn cần cắm điện và cáp Ethernet. Đóng/mở đóng băng thay đổi trạng thái **sau lần khởi động lại kế tiếp**; app không tự reboot cả phòng.

Nếu chưa quét thấy máy, kiểm tra khóa hai bên giống nhau, agent đã cài thành công, Wi-Fi không bật AP/client isolation và hai thiết bị cùng VLAN. Đồng hồ điện thoại và Windows cần lệch dưới 2 phút để lệnh tắt/restart được chấp nhận.

### Quảng cáo riêng cho từng phòng và khóa/mở máy

1. Cài client 1.4.0 trên các máy; ổ `D:` phải tồn tại và không bị UWF bảo vệ. Lần nâng cấp chương trình trên C: cần mở đóng băng và khởi động lại trước khi cài. Sau đó có thể đóng băng C: trở lại.
2. Trên Android, mở đúng phòng và chọn phần quảng cáo. Chọn ảnh JPG/PNG và video MP4 theo thứ tự muốn phát. Mỗi phòng lưu một bộ nội dung riêng trong điện thoại.
3. Gửi quảng cáo cho toàn phòng đang mở. Chỉ các máy online có hỗ trợ quảng cáo và đúng key phòng nhận nội dung; máy offline hoặc bản cũ được báo riêng. Khi máy online, gửi lại để hoàn tất. Đổi sang phòng khác không làm thay nội dung phòng trước.
4. Chọn **Khóa** để hiện quảng cáo toàn màn hình; **Mở khóa** để trả desktop. Có thể điều khiển từng máy hoặc chọn nhiều máy trong phòng. Trạng thái “đã khóa” cần player trong phiên người dùng xác nhận; chưa có người đăng nhập sẽ được báo riêng, không coi là đã khóa thành công.

Nên dùng MP4 mã hóa H.264/AAC và ảnh có kích thước phù hợp màn hình. Trình phát dùng thành phần media của Windows; nếu máy thiếu codec hoặc tệp không phát được, màn hình hiển thị thương hiệu TN20 và tiếp tục danh sách.

Ảnh/video được truyền qua TLS với chứng thư máy đã được xác thực bằng key phòng; mỗi yêu cầu còn được ký HMAC và kiểm tra thời gian, nonce, dung lượng, SHA-256. Tối đa 20 mục, 128 MiB mỗi tệp và 256 MiB cho một bộ. Nội dung chỉ được dùng làm ảnh/video, không cài thêm chương trình. Nếu mất mạng hoặc tải lỗi, máy tiếp tục giữ bộ đã xác minh trước đó.

Windows lưu ảnh/video tại `D:\RoomTN20Data\Media` và trạng thái khóa tại `D:\RoomTN20Data\State`. Dữ liệu được giữ qua lần khởi động lại; trạng thái khóa cuối cùng được áp dụng lại khi người dùng đăng nhập. Có thể thay nội dung khi C: vẫn đóng băng, không cần gỡ UWF hoặc loại trừ thư mục chương trình/khóa quản trị trên C:. Thư mục D: chỉ SYSTEM/Administrators được ghi; tài khoản sử dụng máy chỉ có quyền đọc.

Đây là **khóa sử dụng Room TN20**: player hiển thị trong phiên người dùng, hỗ trợ nhiều màn hình và phát nội dung đã tải kể cả khi mất mạng. Cơ chế không thay màn hình đăng nhập Windows và không chặn Ctrl+Alt+Del. Nếu cần hạn chế người dùng thoát ứng dụng kiểu kiosk, dùng tài khoản thường và cấu hình kiosk/Windows Shell Launcher phù hợp. [Microsoft: phiên dịch vụ](https://learn.microsoft.com/en-us/windows/win32/services/interactive-services), [Shell Launcher](https://learn.microsoft.com/en-us/windows/iot/iot-enterprise/customize/shell-launcher), [UWF](https://learn.microsoft.com/en-us/windows/configuration/unified-write-filter/).

**Mở khóa khẩn cấp tại máy:** đăng nhập tài khoản quản trị, mở PowerShell bằng quyền Administrator rồi chạy:

```powershell
& 'C:\ProgramData\LanPowerStandalone\Display\RoomTN20.Player.exe' --emergency-unlock --data-root 'D:\RoomTN20Data'
```

Lệnh phục hồi cần quyền quản trị và ghi trạng thái trên D:, nên dùng được khi C: đang đóng băng. Không chia sẻ tài khoản quản trị hoặc key phòng cho người sử dụng máy. Gỡ client giữ lại ảnh/video trên D: để quản trị viên quyết định sử dụng tiếp.

Các phần tiếp theo hướng dẫn cài offline, cấu hình Wake-on-LAN và đóng băng ổ C bằng UWF.

## Kiến trúc

```text
Android APK ── UDP broadcast 17840 ──► phát hiện HP t540
Android APK ── UDP 17840 + HMAC ─────► tắt/restart
Android APK ── UDP 17840 + HMAC ─────► khóa/mở sử dụng + UWF
Android APK ── TCP/TLS 17842 ────────► ảnh/MP4 riêng cho phòng
Windows agent ── named pipe ────────► player trong phiên người dùng
Android APK ── UDP broadcast 9 ──────► Wake-on-LAN
```

## Kết nối qua WireGuard/OpenVPN

LAN vẫn dùng broadcast để nhận máy tự động. VPN dạng routed/TUN thường không chuyển broadcast; vì vậy trong phần **Mạng LAN/VPN** của từng phòng, nhập các IP hoặc dải mạng mà điện thoại có thể định tuyến tới, ví dụ `10.8.0.12` hoặc `192.168.20.0/24`. App gửi discovery có chữ ký trực tiếp tới các địa chỉ đó và các IP máy đã lưu. Không cần máy chủ API.

- Mỗi phòng tối đa 1.024 địa chỉ; không quét cả dải VPN khổng lồ. Hỗ trợ IPv4 riêng/CGNAT và các CIDR từ `/22` trở lên trong phạm vi giới hạn.
- Trong bộ cài Windows, mục VPN cho phép điền IP/dải nguồn của máy quản trị, ví dụ `10.8.0.0/24`. Firewall chỉ mở UDP 17840 và TCP 17842 cho `LocalSubnet` và các nguồn được cấu hình.
- WireGuard phải có `AllowedIPs`/route phù hợp; OpenVPN phải có route và chính sách client-to-client hoặc site-to-site cho phép UDP 17840 và TCP 17842. Ứng dụng không tự thay cấu hình router/VPN.
- Wi-Fi client isolation, firewall hoặc đường về không đúng vẫn có thể chặn liên lạc dù thiết bị đã kết nối VPN.

Bật máy qua VPN cần một agent đang online trong cùng LAN của phòng để phát Magic Packet hộ. App chọn một máy cùng phòng có khả năng WOL relay đã được xác thực. Nếu cả phòng đều tắt, cần router/thiết bị WOL riêng hoặc một máy relay luôn bật. Gửi WOL thành công không xác nhận máy đã boot; hãy quét lại.

Tham khảo [routing OpenVPN](https://openvpn.net/community-docs/setting-up-routing/) và [khác biệt TUN/TAP](https://community.openvpn.net/Pages/Getting%20started%20with%20OpenVPN).

## Kiểm tra và tự nâng cấp

Client và APK hiển thị phiên bản đang dùng, phiên bản mới và tình trạng kiểm tra/cập nhật. Bản phát hành được mô tả trong `updates/stable.json`; chữ ký RSA-SHA256 được kiểm tra bằng khóa công khai nhúng trong phần mềm, sau đó từng tệp tải về được đối chiếu SHA-256.

- **Windows:** task cập nhật kiểm tra định kỳ và lúc khởi động, giữ key, deviceId và cấu hình VPN. Khi C đang đóng băng, cập nhật chuyển sang trạng thái chờ; phần mềm không tự mở đóng băng hoặc reboot. Mở bảo vệ C và restart khi bạn sẵn sàng bảo trì, sau đó cập nhật mới được ghi bền vững.
- **Android:** tự kiểm tra và tải APK đã xác minh. Android thông thường vẫn có thể yêu cầu cấp quyền cài từ nguồn này và xác nhận cài đặt; app dùng bước cài đặt chính thức, không vượt quyền hệ thống. [Quy tắc xác nhận của Android](https://developer.android.com/reference/android/content/pm/PackageInstaller.SessionParams#setRequireUserAction(int)).
- Trên APK, mở menu **⋮** ở góc trên bên phải rồi chọn **Kiểm tra cập nhật**, **Cài bản mới** hoặc **Trạng thái cập nhật**. Mục cài đặt tiếp tục dùng xác nhận của Android khi hệ thống yêu cầu.
- Kiểm tra đúng package/chứng thư APK, từ chối chữ ký sai, checksum sai, URL ngoài nguồn cho phép và phiên bản thấp hơn. Có Internet mới kiểm tra được bản phát hành trên GitHub; chức năng quản lý LAN/VPN vẫn dùng dữ liệu cục bộ khi không có Internet.
- Bản 1.1.x chưa có bộ tự cập nhật: cần cài APK 1.2.3 và client Windows 1.2.4 một lần để bắt đầu nhận các bản mới tiếp theo.
- Client Windows 1.2.0 thử nghiệm cần chạy lại EXE 1.2.4 để sửa lỗi thay thế tệp; từ client 1.2.1 trở lên có thể nhận cập nhật tự động khi C không đang được bảo vệ.

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

Ưu tiên dùng [một file EXE ở Bước 2](#huong-dan-cai-dat). Nếu cần cài từ mã nguồn, tải [ZIP mã nguồn](https://github.com/namnamthainguyen-ops/apklan/archive/refs/heads/main.zip), giải nén toàn bộ rồi mở PowerShell Administrator trong thư mục `client`. Giữ nguyên thư mục `client` và `release` của ZIP để có đủ script và hai thành phần `RoomTN20.Runtime-1.4.0.dll`, `RoomTN20.Player-1.4.0.exe`. Nếu thiếu hai tệp này, core chỉ tải bản đúng SHA-256 đã ghim từ GitHub, cần Internet:

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

APK phát hành trong repository: `release/RoomTN20-1.4.0.apk`, `debuggable=false`. Giữ cùng chứng thư cũ để cài nâng cấp và giữ dữ liệu; tên chứng thư vẫn là Android Debug do tương thích với các bản đã cài. Đây chưa phải bản phân phối qua Google Play. Giao diện dùng vùng an toàn status bar/tai thỏ và thanh điều hướng, không đè lên giờ/thông báo của điện thoại.

```text
APK SHA-256: 8E4BA710E17A051DFC6F314C35C4AFF2C0524AC87AE74CB2FF89BB94FC66D09B
```

Để tự tìm bằng broadcast, điện thoại và t540 cần cùng broadcast domain, Wi-Fi không bật AP/client isolation. Nếu khác VLAN hoặc kết nối VPN, cấu hình IP/dải mạng trực tiếp trong mục **Mạng LAN/VPN** của phòng và cho phép định tuyến/firewall tương ứng.

Đồng hồ điện thoại và Windows cần lệch dưới 2 phút để lệnh có timestamp được chấp nhận.

### Cảnh báo bảo mật Android

APK cài từ GitHub nằm ngoài Google Play. Android có thể yêu cầu quyền cài từ nguồn này hoặc đề nghị quét ứng dụng mới. Cảnh báo **ứng dụng có hại/bị chặn** cần kiểm tra nguyên văn lý do; không thể kết luận là cảnh báo nhầm chỉ vì phần mềm đã qua CI. Bản 1.2.3 đã bỏ debug nhưng không bảo đảm mọi điện thoại sẽ hết cảnh báo. [Phân loại cảnh báo của Google](https://developers.google.com/android/play-protect/warning-dev-guidance), [Google Play Protect](https://support.google.com/googleplay/answer/2812853?hl=vi).

Nếu Play Protect chỉ **đề nghị quét ứng dụng**, chọn **Quét ứng dụng** và chờ kết quả của Google trước khi tiếp tục cài.

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

## Code signing policy và giấy phép

Mã nguồn phát hành theo [MIT](LICENSE). Hồ sơ xin ký miễn phí đã gửi SignPath và đang chờ xét duyệt; chưa có chứng chỉ Authenticode công khai. Xem [Code signing policy](docs/code-signing-policy.md).
