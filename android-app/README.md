# LanPower Standalone Android

App Android 1.1.0 quản lý nhiều phòng trực tiếp trong LAN, không dùng API hay máy chủ trung tâm. Yêu cầu Android 8.0 (API 26) trở lên.

- Tạo phòng bằng tên và key tự chọn đúng 8 ký tự ASCII hiển thị, gồm chữ, số hoặc ký tự đặc biệt, không có khoảng trắng; phân biệt hoa/thường. Khoảng trắng thừa ở hai đầu được bỏ qua. Dùng đúng cùng key khi cài agent Windows trong phòng.
- Mỗi phòng phải dùng key riêng để máy không xuất hiện ở nhiều phòng. Nhấn giữ thẻ phòng để xóa phòng, khóa và danh sách máy khỏi app sau khi xác nhận; thao tác này không thay đổi cấu hình máy Windows.
- PBKDF2-HMACSHA256 (210000 vòng, salt `LanPower/RoomKey/v1`, đầu ra 32 byte) chỉ chạy khi tạo phòng. Mỗi khóa đã dẫn xuất được mã hóa riêng bằng Android Keystore/AES-GCM, gắn với ID phòng; không lưu key 8 ký tự trong cấu hình.
- Trang phòng hiển thị số máy online/offline. Chạm phòng để chọn từng máy/chọn tất cả và thao tác hàng loạt; chạm máy để xem hostname, IP, MAC, lần phản hồi gần nhất, trạng thái UWF C: và các nút điều khiển.
- UDP `17840`: phát hiện agent và gửi lệnh `shutdown`/`restart`/`freeze`/`unfreeze` có HMAC-SHA256. Phòng chỉ nhận agent dùng cùng khóa dẫn xuất.
- UDP `9`: gửi Wake-on-LAN Magic Packet trực tiếp.
- Quét tự động khoảng 20 giây khi app đang hiển thị, không quét chồng thao tác. Trong một phòng, máy đã lưu nhưng không phản hồi sau lần quét thành công được giữ lại và đánh dấu offline/chưa phản hồi; lỗi toàn bộ lần quét được hiển thị là chưa xác định, không tự kết luận tất cả máy đã tắt. Máy đang tắt chưa từng được lưu cần bật một lần để nhận MAC.
- Mỗi thao tác đều xác nhận và báo kết quả riêng từng máy. Bật máy chỉ xác nhận đã gửi Magic Packet; các lệnh còn lại bỏ qua máy offline/chưa rõ trạng thái. UWF có timeout 30 giây mỗi máy để chờ lệnh và kiểm tra trạng thái.
- `freeze`/`unfreeze` thay đổi bảo vệ C: cho lần khởi động tiếp theo, không tự restart. Trạng thái Current/Next và khả năng UWF chỉ được tin khi `stateSignature` hợp lệ. Agent cũ vẫn điều khiển nguồn được; máy chưa có UWF cần agent mới và chạy `client/Prepare-Uwf.ps1` một lần bằng Administrator rồi khởi động lại/quét. Chi tiết ACK chỉ được hiển thị là kết quả xác thực khi có `detailSignature` đúng.
- Khi nâng cấp, Management Key Base64 và máy đã lưu từ bản cũ được chuyển sang **Phòng cũ**; dữ liệu nguồn cũ vẫn được giữ. Khóa cũ tiếp tục được mã hóa, không bắt buộc cài lại agent chỉ để nâng cấp app.

Build bằng Android Studio hoặc Gradle 8.9 + JDK 17 + Android SDK Platform/Build Tools 35:

```powershell
gradle testDebugUnitTest lintDebug assembleDebug
```

APK debug được tạo tại `app/build/outputs/apk/debug/app-debug.apk`.

JVM tests kiểm tra các vector KDF/HMAC độc lập, ràng buộc chữ ký, thao tác UWF, cấu trúc Magic Packet, phân tách phòng, giữ máy offline, chọn máy và khả năng UWF đã xác thực. CI còn có fixture UDP để chạy UI trên emulator. Chỉ bản debug trên emulator có thể bật fixture bằng intent `--ez lanpower_ci true`; khi bật, app thêm đích `10.0.2.2` để liên lạc fixture trên máy host. Điện thoại thật và bản release không sử dụng đường kiểm thử này.
