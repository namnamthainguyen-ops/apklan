# Room TN20 Server 1.0.1

Tải và chạy `RoomTN20-Server-1.0.1.exe` bằng tài khoản Windows dùng để quản lý phòng. Bộ cài tạo lối tắt **Room TN20 Server** trong Start Menu và lưu dữ liệu tại `%LOCALAPPDATA%\RoomTN20\Server`. Khi cập nhật, đóng cửa sổ đang chạy rồi mở bộ cài mới.

1. Mở **Phòng máy → Thêm phòng**. Nhập tên phòng và cùng key 8 ký tự đã dùng trên Android và client.
2. Chọn **Quét LAN**. Với VPN, vào **Sửa phòng** và nhập IP hoặc CIDR của mạng đích. Windows cần định tuyến tới client trên UDP 17840 và, cho quảng cáo, TCP 17842.
3. Tích các máy đích rồi chọn thao tác. Kiểm tra kết quả từng máy và mở **Nhật ký** để xem lại thao tác gửi từ Server.
4. Trong **Quảng cáo**, nhập JPEG, PNG hoặc MP4 và gửi danh sách của phòng. Server dùng lại nội dung client đã có. Android và Windows dùng danh sách quản lý riêng, lần gửi sau cùng sẽ đặt danh sách phát trên client.
5. Trong **Cập nhật**, xem phiên bản, trạng thái client báo về và máy thuộc nhóm thử. Việc cài cập nhật do cơ chế đã ký trên client thực hiện.

Android và Server quản lý trực tiếp cùng các client. Máy chạy Server có thể tắt khi quản lý bằng Android. Cấu hình phòng và nhật ký Server lưu theo tài khoản Windows, với key được bảo vệ bằng Windows DPAPI. Không đồng bộ lịch sử thao tác giữa Android và Windows.

Build và kiểm thử bằng Windows PowerShell 5.1 trên máy có .NET Framework 4.8.

```powershell
.\server-win\Test.ps1
.\server-win\Build.ps1
```
