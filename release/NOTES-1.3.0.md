# Room TN20 1.3.0

Thương hiệu TN20 — Công ty TNHH TN20 — https://20.com.vn

- Khóa/mở sử dụng từng máy hoặc chọn nhiều máy trong phòng từ Android.
- Mỗi phòng có bộ ảnh JPG/PNG và video MP4 riêng. Sắp thứ tự, đặt thời lượng ảnh và gửi cho toàn phòng đang mở; các phòng khác giữ nguyên.
- Ảnh/video và trạng thái khóa lưu tại D:\RoomTN20Data. Thay quảng cáo khi C: vẫn đóng băng, không cần gỡ UWF.
- Máy offline được báo chưa cập nhật để gửi lại sau. Tải lỗi giữ bộ quảng cáo đã xác minh trước.
- Truyền nội dung bằng TLS và chữ ký HMAC; xác minh dung lượng, thời gian, nonce và SHA-256. Chỉ báo khóa thành công sau xác nhận từ player trong phiên người dùng.
- Giữ quản lý nguồn, UWF trên Windows được hỗ trợ, LAN/VPN WireGuard/OpenVPN và cập nhật có chữ ký.

## Cài đặt

1. Tải RoomTN20-ClientSetup-1.3.0.exe, chạy Administrator trên máy con, nhập key 8 ký tự của phòng. Các phòng dùng key khác nhau.
2. Để dùng quảng cáo, cần D: chưa đóng băng. Nếu C: đang đóng băng, mở đóng băng và khởi động lại trước lần nâng cấp chương trình này; sau đó có thể đóng băng C: lại.
3. Cài RoomTN20-1.3.0.apk đè lên bản cũ để giữ phòng. Nếu Play Protect đề nghị quét, chọn quét và đợi kết quả.
4. Mở phòng → Quảng cáo riêng của phòng → chọn ảnh/video → Gửi lại cho cả phòng. Khi máy đang offline, gửi lại sau khi máy online.

Mỗi bộ tối đa 20 tệp, 128 MiB/tệp và 256 MiB tổng. Nên dùng MP4 H.264/AAC. Cần đăng nhập người dùng Windows để có màn hình hiển thị. Đây là khóa sử dụng Room TN20; không thay màn hình đăng nhập Windows hoặc chính sách kiosk. Phát MP4 phụ thuộc codec/thành phần media của bản Windows IoT thực tế; nên thử một máy trước khi triển khai cả phòng.

Khôi phục tại máy bằng PowerShell Administrator:

```powershell
& 'C:\ProgramData\LanPowerStandalone\Display\RoomTN20.Player.exe' --emergency-unlock --data-root 'D:\RoomTN20Data'
```

Windows Server 2025/Home/Pro không hỗ trợ UWF. Client vẫn quản lý nguồn và có thể dùng màn hình quảng cáo khi đủ điều kiện ổ dữ liệu/phiên người dùng. Windows EXE chưa ký Authenticode. APK giữ chứng thư tương thích với các bản Room TN20 trước, không bật debug.
