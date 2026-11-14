# Room TN20 Android

**Room TN20 1.3.0**, thương hiệu **TN20** của **Công ty TNHH TN20**, quản lý nhiều phòng trực tiếp trong LAN hoặc VPN có định tuyến tới máy con, không dùng API hay máy chủ trung tâm. Website: [20.com.vn](https://20.com.vn). Link trong app chỉ mở khi người dùng chạm. Yêu cầu Android 8.0 (API 26) trở lên.

Tên hiển thị và giao diện nhận thương hiệu mới; applicationId `com.lanpower.standalone`, vùng lưu dữ liệu và khóa mã hóa vẫn giữ nguyên để nâng cấp mà không mất phòng/máy đã lưu. Bản APK nâng cấp sử dụng cùng chứng thư ký với các bản trước.

- Tạo phòng bằng tên và key tự chọn đúng 8 ký tự ASCII hiển thị, gồm chữ, số hoặc ký tự đặc biệt, không có khoảng trắng; phân biệt hoa/thường. Khoảng trắng thừa ở hai đầu được bỏ qua. Dùng đúng cùng key khi cài agent Windows trong phòng.
- Mỗi phòng phải dùng key riêng để máy không xuất hiện ở nhiều phòng. Nhấn giữ thẻ phòng để xóa phòng, khóa và danh sách máy khỏi app sau khi xác nhận; thao tác này không thay đổi cấu hình máy Windows.
- PBKDF2-HMACSHA256 (210000 vòng, salt `LanPower/RoomKey/v1`, đầu ra 32 byte) chỉ chạy khi tạo phòng. Mỗi khóa đã dẫn xuất được mã hóa riêng bằng Android Keystore/AES-GCM, gắn với ID phòng; không lưu key 8 ký tự trong cấu hình.
- Trang phòng hiển thị số máy online/offline. Chạm phòng để chọn từng máy/chọn tất cả và thao tác hàng loạt; chạm máy để xem hostname, IP, MAC, lần phản hồi gần nhất, trạng thái UWF C: và các nút điều khiển.
- Bản 1.3 thêm **Khóa màn hình / Mở màn hình** theo máy hoặc chọn tất cả trong phòng. Đây là khóa phiên sử dụng kiểu phòng máy, không phải khóa đăng nhập Windows. Máy chỉ hiển thị thành công khi nhận trạng thái thực đã ký `locked`/`unlocked`; nếu chưa có người đăng nhập hoặc màn hình chưa xác nhận thì báo đang chờ, không khẳng định đã khóa.
- Mỗi phòng có nút **Quảng cáo riêng của phòng** và danh sách ảnh/video riêng. Chọn JPG/PNG/MP4 bằng trình chọn tệp Android, app sao chép vào vùng dữ liệu riêng nên không phụ thuộc tệp nguồn sau khi thêm. Có thể đổi thứ tự, xóa và đặt thời lượng ảnh (mặc định 10 giây, 1–600 giây); video phát hết. Giới hạn 20 tệp/phòng, 128 MiB/tệp, tổng 256 MiB/phòng; ảnh được kiểm tra giải mã/kích thước trước khi nhận.
- **Gửi lại cho cả phòng** gửi đúng snapshot danh sách và khóa của phòng đó tới tất cả máy online đã lưu trong phòng, không gửi sang phòng khác. Máy offline được báo **Chờ gửi**; khi máy online, chọn gửi lại (không có thông báo thành công giả hoặc tự dùng nội dung phòng khác). Gửi dở/hỏng không thay bộ quảng cáo cũ trên máy con; sau ACK có chữ ký, quét lại để kiểm tra phiên bản đang dùng.
- Nội dung quảng cáo truyền trực tiếp qua TLS 1.2/TCP `17842`, ghim SHA-256 chứng thư từ discovery HMAC đã xác thực. Header và ACK ký HMAC theo room key, có timestamp/nonce chống replay; từng tệp có SHA-256 và whitelist MIME. Không truyền video qua UDP, không tạo máy chủ API trung tâm. Máy con lưu dữ liệu ở vùng được cấu hình không bị UWF lọc, nên đổi quảng cáo không tắt đóng băng C:.
- UDP `17840`: phát hiện agent và gửi lệnh `shutdown`/`restart`/`freeze`/`unfreeze` có HMAC-SHA256. Phòng chỉ nhận agent dùng cùng khóa dẫn xuất.
- UDP `9`: gửi Wake-on-LAN Magic Packet trực tiếp.
- Trong phòng, chọn **Mạng LAN/VPN** để lưu IPv4 hoặc CIDR riêng (`10/8`, `172.16/12`, `192.168/16`) hoặc CGNAT `100.64/10`; mỗi CIDR phải từ `/22` đến `/32`, tổng tối đa 1024 IP gồm các IP máy đã lưu. App quét broadcast LAN, IP máy đã lưu và các IP được cấu hình, không quét toàn bộ VPN hay địa chỉ Internet công cộng. Cần VPN định tuyến hoạt động và firewall agent cho phép đúng dải VPN.
- Wake qua VPN sử dụng một agent **đang online trong cùng phòng** có trạng thái xác thực và capability `wolrelay`: app gửi lệnh `wake` ký HMAC, ràng buộc MAC máy đích; relay phát Magic Packet trong LAN đích. Khi không có relay, app báo rõ chỉ gửi broadcast cục bộ, không khẳng định máy từ xa đã bật. WOL vẫn cần BIOS/card mạng/nguồn điện phù hợp, và phải quét lại để xác nhận.
- Quét tự động khoảng 20 giây khi app đang hiển thị, không quét chồng thao tác. Trong một phòng, máy đã lưu nhưng không phản hồi sau lần quét thành công được giữ lại và đánh dấu offline/chưa phản hồi; lỗi toàn bộ lần quét được hiển thị là chưa xác định, không tự kết luận tất cả máy đã tắt. Máy đang tắt chưa từng được lưu cần bật một lần để nhận MAC.
- Mỗi thao tác đều xác nhận và báo kết quả riêng từng máy. Bật máy chỉ xác nhận đã gửi Magic Packet; các lệnh còn lại bỏ qua máy offline/chưa rõ trạng thái. UWF có timeout 30 giây mỗi máy để chờ lệnh và kiểm tra trạng thái.
- `freeze`/`unfreeze` thay đổi bảo vệ C: cho lần khởi động tiếp theo, không tự restart. Trạng thái Current/Next và khả năng UWF chỉ được tin khi `stateSignature` hợp lệ. Agent cũ vẫn điều khiển nguồn được; bộ cài client mới tự chuẩn bị UWF, sau đó khởi động lại nếu được yêu cầu rồi quét lại. Chi tiết ACK chỉ được hiển thị là kết quả xác thực khi có `detailSignature` đúng.
- Khi nâng cấp, Management Key Base64 và máy đã lưu từ bản cũ được chuyển sang **Phòng cũ**; dữ liệu nguồn cũ vẫn được giữ. Khóa cũ tiếp tục được mã hóa, không bắt buộc cài lại agent chỉ để nâng cấp app.
- App tự kiểm tra manifest `updates/stable.json` khi mở và tối đa mỗi 6 giờ khi đang hiển thị, chạy riêng với quét LAN. Manifest RS256 được kiểm tra bằng khóa RSA3072 nhúng sẵn; APK tự tải chỉ từ đường HTTPS đúng repo, chặn redirect ra ngoài và giới hạn dung lượng. Trước khi cài, app kiểm tra SHA-256, package, versionCode tăng và chứng thư ký trùng ứng dụng hiện tại. Manifest cũ hơn phiên bản đã tin cậy bị từ chối.
- Các thao tác cập nhật nằm trong menu **⋮** ở góc trên bên phải: **Kiểm tra cập nhật**, **Cài bản mới** và **Trạng thái cập nhật**.
- Sau khi tải/xác minh, Android 12+ có thể cho tự cập nhật bằng PackageInstaller nếu nguồn cài đã được người dùng cho phép và các điều kiện hệ thống đáp ứng; app yêu cầu không cần tương tác nhưng tuân theo kết quả của Android. Nếu Android yêu cầu xác nhận, trạng thái hiển thị tại mục cập nhật và nút **Cài bản mới** mở xác nhận khi người dùng chọn; app không mở hộp thoại đè thao tác phòng. Android 8–11 dùng xác nhận cài hệ thống. Quyền cài từ nguồn này được cấp trong Settings sau khi người dùng bấm nút, không tự bật hay bỏ qua quyền.
- Chi tiết máy hiển thị version/code và thông tin cập nhật Windows chỉ khi `updateSignature` hợp lệ; chọn Quét LAN để đọc lại. Thông tin ký HMAC không thay thế kiểm tra chữ ký RSA gói cập nhật của agent.

Build bằng Android Studio hoặc Gradle 8.9 + JDK 17 + Android SDK Platform/Build Tools 35:

```powershell
gradle testDebugUnitTest lintDebug assembleDebug
```

APK debug phục vụ kiểm thử được tạo tại `app/build/outputs/apk/debug/app-debug.apk`. Bản người dùng tải là `release/RoomTN20-1.2.3.apk`, build release với `debuggable=false`, ký bằng cùng chứng thư cũ để nâng cấp giữ dữ liệu. Chứng thư hiện tại vẫn mang tên Android Debug; không được hiểu là đã có xác minh Google Play/Play Protect. Xem phần cảnh báo bảo mật trong README chính.

CI chạy toàn bộ bộ thử debug và nâng cấp APK phát hành thật bằng giao diện công khai. Bài thử release không dùng `run-as`, không bật tuyến fixture hay cờ tắt cập nhật; IP fixture được nhập qua mục Mạng LAN/VPN như người dùng bình thường. CI kiểm tra chữ ký và bảo đảm APK phát hành không debuggable.

JVM tests kiểm tra các vector KDF/HMAC độc lập, ràng buộc chữ ký/relay MAC/update metadata/lockstate/media, playlist snapshot độc lập từng phòng và giới hạn dung lượng, thao tác UWF, Magic Packet, VPN IPv4/CIDR, RS256 manifest/tamper/wrong-key/rollback/allowlist. CI còn có fixture UDP để chạy UI trên emulator. Chỉ bản debug trên emulator có thể bật fixture bằng intent `--ez lanpower_ci true`; khi bật, app thêm đích `10.0.2.2` để liên lạc fixture trên máy host và tắt cập nhật mạng. `--ez disable_updates true` tắt riêng cập nhật trên debug/emulator để kiểm thử unicast VPN thật mà không thêm đường fixture. Điện thoại thật và bản release bỏ qua các cờ kiểm thử này.
## Quét và xác nhận trạng thái máy

- App quét nhanh IP đã lưu mỗi 20 giây khi đang mở và rảnh. Khoảng 5 phút một lần, app tìm máy mới trong LAN và các dải VPN đã cấu hình của phòng. **Quét LAN → Tìm máy mới** chạy ngay một lượt đầy đủ.
- Máy được đánh dấu offline sau hai lượt quét thành công liên tiếp mà máy không trả lời. App giữ lần cuối thấy và báo lỗi quét riêng.
- Sau khi gửi lệnh khởi động lại, app theo dõi tối đa 5 phút khi còn mở. Client Windows 1.4.5 trở lên gửi thời điểm khởi động đã xác thực để app xác nhận máy đã khởi động lại và online.
- Nếu hai máy trả lời với cùng ID nhưng khác MAC, app hiện cảnh báo trùng ID. Client Windows tính ID từ phần cứng khi khởi chạy, nên có thể giữ cấu hình phòng và nội dung quảng cáo trong ảnh ghost.
