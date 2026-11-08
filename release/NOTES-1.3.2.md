# Room TN20 — Windows client 1.3.2 / Android 1.3.1

- Sửa lỗi trình quảng cáo bị từ chối kết nối khi chạy bằng tài khoản Windows thường, dẫn tới thông báo nhầm “chưa có phiên người dùng” dù đã mở desktop trực tiếp.
- Trình hiển thị chỉ yêu cầu đúng các quyền kết nối đã được cấp. Không cấp thêm quyền quản trị, không nới quyền ghi vào thư mục chương trình hay khóa quản trị.
- Thêm kiểm thử Windows bằng token không quản trị: cách kết nối cũ bị từ chối, cách mới kết nối và trao đổi hai chiều thành công.
- Giữ xác thực tiến trình/phiên người dùng, xác nhận màn hình thực, khôi phục quản trị, quảng cáo ảnh/MP4 riêng từng phòng trên D: và các chức năng LAN/VPN/UWF.

## Nâng cấp

Tải **RoomTN20-ClientSetup-1.3.2.exe**, cài đè trên máy con và nhập đúng key phòng. Nếu C: đang đóng băng, mở đóng băng và khởi động lại trước lần nâng cấp chương trình. Sau khi cài, đăng nhập Windows bình thường rồi quét lại phòng trong APK và thử Khóa/Mở màn hình.

APK Android giữ nguyên **1.3.1**; không cần cài lại APK nếu đã dùng bản này. Không cần gỡ UWF; thay nội dung quảng cáo trên D: không cần mở đóng băng C:.

## Code signing policy

Dự án được phát hành theo [MIT](https://github.com/namnamthainguyen-ops/apklan/blob/main/LICENSE) theo quyết định của chủ dự án. Hồ sơ xin ký miễn phí qua SignPath Foundation đang được chuẩn bị; chưa được cấp chứng chỉ và bộ cài này chưa có Authenticode công khai. Chữ ký RSA của bản cập nhật là cơ chế xác minh riêng.

Xem [Code signing policy](https://github.com/namnamthainguyen-ops/apklan/blob/main/docs/code-signing-policy.md). Không tự cài chứng chỉ gốc hoặc bỏ qua các cảnh báo bảo mật của Windows/Android.
