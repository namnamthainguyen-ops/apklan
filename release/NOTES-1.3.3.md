# Room TN20 — Windows client 1.3.3 / Android 1.3.1

Sửa đầy đủ hai điểm chặn tài khoản Windows thường khiến màn hình quảng cáo báo nhầm “chưa có phiên người dùng” dù đã mở desktop:

- Trình hiển thị yêu cầu đúng quyền đọc/ghi kênh đã được cấp, không yêu cầu quyền ghi tổng quát.
- Xác thực kênh thuộc SYSTEM và phiên dịch vụ mà không đòi tài khoản thường đọc đường dẫn tiến trình SYSTEM. Máy Windows vẫn giữ nguyên cơ chế bảo vệ tiến trình hệ thống.
- Giữ xác thực tiến trình player và phiên người dùng ở phía client SYSTEM; không cấp quyền quản trị cho player.
- Lệnh khóa thử khởi chạy player ngay, tránh chờ chu kỳ khởi chạy lại. Nếu chưa xác nhận được màn hình, thông báo phân biệt thiếu phiên console và lỗi khởi chạy/kết nối, kèm mã lỗi thay vì mặc định báo chưa có người dùng.

Đã kiểm thử trên GitHub: phương thức cũ tái hiện Access Denied dưới token không quản trị; phương thức mới trao đổi hai chiều thành công; tài khoản thường xác thực được phiên broker SYSTEM thật; gửi ảnh TLS, gửi lại sau lỗi và bộ nhận SYSTEM đều đạt.

## Cài đặt

1. Tải **RoomTN20-ClientSetup-1.3.3.exe**, chạy Administrator và cài đè với đúng key phòng. Nếu C: đang đóng băng, mở đóng băng và khởi động lại trước lần nâng cấp chương trình này.
2. Đăng nhập Windows tại máy con, quét lại phòng trong APK rồi thử **Mở màn hình / Khóa màn hình**. Ảnh/video và trạng thái trên D: được giữ lại.
3. APK Android giữ nguyên **1.3.1**. Không cần cài lại APK nếu đã dùng phiên bản này. Không cần gỡ UWF; thay quảng cáo trên D: vẫn không cần mở đóng băng C:.

## Code signing policy

Dự án dùng [MIT](https://github.com/namnamthainguyen-ops/apklan/blob/main/LICENSE). Hồ sơ xin ký miễn phí SignPath đã được nộp ngày 2026-11-08 và đang chờ xét duyệt. Bộ cài hiện chưa có Authenticode công khai; chữ ký RSA của cập nhật là cơ chế xác minh riêng. Không tự cài chứng chỉ gốc hoặc bỏ qua bảo vệ Windows/Android để thay thế chứng chỉ công khai.

Xem [Code signing policy](https://github.com/namnamthainguyen-ops/apklan/blob/main/docs/code-signing-policy.md).
