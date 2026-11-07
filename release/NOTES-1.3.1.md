# Room TN20 1.3.1

TN20 — Công ty TNHH TN20 — https://20.com.vn

- Đặt **Mạng LAN/VPN** và **Quảng cáo phòng** ngang hàng, rộng bằng nhau.
- Trước khi gửi ảnh/video, tự quét và xác thực lại các máy của đúng phòng, tránh dùng thông tin cũ sau khi nâng cấp client.
- Nếu bộ nhận quảng cáo chưa bật, hiển thị phiên bản client và nguyên nhân đã xác thực, thay cho thông báo chung “máy chưa hỗ trợ”.
- Bộ cài kiểm tra riêng bộ nhận quảng cáo khi đã chuẩn bị D:, không báo đã sẵn sàng chỉ vì phần quản lý nguồn hoạt động.
- Client thử khởi động lại bộ nhận khi ổ dữ liệu chưa sẵn sàng lúc Windows khởi động.
- Sửa trường hợp gửi lại ngay sau lần tải lỗi bị báo bộ nhận đang bận.
- Giữ quảng cáo riêng từng phòng, ảnh JPG/PNG và video MP4 lưu trên D:, khóa/mở từng máy hoặc cả phòng, LAN/VPN và UWF.

## Nâng cấp và gửi quảng cáo

1. Cài đè **RoomTN20-ClientSetup-1.3.1.exe** trên các máy con, nhập key của phòng. Nếu C: đang đóng băng, mở đóng băng và khởi động lại trước lần nâng cấp chương trình này.
2. Đảm bảo D: tồn tại và không bị UWF bảo vệ. Bộ cài chuẩn bị D:\RoomTN20Data để lưu nội dung; không cần tự tạo thư mục quảng cáo hoặc gỡ UWF.
3. Cài đè **RoomTN20-1.3.1.apk**, mở đúng phòng → **Quảng cáo phòng** → chọn ảnh/video → **Gửi lại cho cả phòng**. Nội dung các phòng khác giữ nguyên; máy offline được báo để gửi lại sau.
4. Khi sử dụng bình thường có thể đóng băng C: lại; thay nội dung quảng cáo trên D: không cần mở đóng băng C:.

Tối đa 20 tệp, 128 MiB/tệp và 256 MiB/bộ. Video MP4 nên dùng H.264/AAC; phát video phụ thuộc thành phần media của Windows IoT. Cần người dùng đăng nhập để hiển thị quảng cáo. Khóa Room TN20 là khóa sử dụng trong phiên người dùng, không thay màn hình đăng nhập Windows hoặc chính sách kiosk.

Khôi phục tại máy bằng PowerShell Administrator:

```powershell
& 'C:\ProgramData\LanPowerStandalone\Display\RoomTN20.Player.exe' --emergency-unlock --data-root 'D:\RoomTN20Data'
```

APK giữ cùng chứng thư với các bản trước để nâng cấp giữ dữ liệu phòng. EXE chưa ký Authenticode. Windows Server/Home/Pro không hỗ trợ UWF; client vẫn quản lý nguồn và quảng cáo khi đủ điều kiện ổ dữ liệu/phiên người dùng.
