# Room TN20 1.4.0 — Windows và Android

- Đồng bộ quảng cáo theo SHA-256: dùng lại tệp có sẵn đã kiểm tra, chỉ tải tệp thiếu/hỏng. Đổi thứ tự, thời lượng hoặc bỏ tệp không tải lại toàn bộ ảnh/video.
- Hiển thị số tệp giữ, tải, bỏ và tên các tệp thay đổi trong kết quả từng máy. Tệp bỏ ngừng phát; giữ một bộ trước để dự phòng, dọn các bộ cũ hơn.
- Ghost sang nhiều máy: mỗi máy tự có định danh từ phần cứng, giữ key phòng/VPN trong ảnh ghost; tên, MAC, IP và trạng thái client/UWF lấy từ máy đang chạy. Không cần ghi lại C: khi khởi động. Máy ảo cần UUID riêng.
- Giữ các bản sửa quyền player và xác thực SYSTEM ở client 1.3.3. Không thêm chức năng remote ẩn.

Cài đè **cả EXE và APK 1.4.0**; APK giữ chữ ký cũ để bảo toàn dữ liệu phòng. Máy offline cần gửi lại khi online. D: phải không đóng băng để lưu quảng cáo. Nếu C: đang đóng băng, mở đóng băng và khởi động lại trước khi nâng cấp chương trình, sau đó có thể đóng băng lại. Những lần chỉ đổi quảng cáo không cần mở đóng băng C:.

EXE phát hành chưa có chữ ký Authenticode, theo yêu cầu không chờ chứng chỉ. Vẫn giữ ký manifest cập nhật, checksum, xác thực key phòng và TLS ghim chứng thư giữa APK/client.
