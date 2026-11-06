# Phát hành cập nhật Room TN20

`stable.json` là phong bì JSON `{algorithm, payload, signature}`. `payload` chứa JSON UTF-8 được mã hóa Base64; `signature` là chữ ký RSA-SHA256/PKCS#1 v1.5 trên đúng các byte payload. Khóa công khai RSA 3072 bit được nhúng vào APK/updater Windows. Không lấy khóa tin cậy từ mạng.

Manifest chỉ trỏ tới các tệp đã có trong một commit GitHub bất biến và kèm SHA-256 của từng tệp. Client kiểm tra chữ ký trước khi tin URL hoặc phiên bản; APK còn kiểm tra package, versionCode và chứng thư ký của APK. Các bản cũ hơn bị từ chối.

## Quy trình phát hành

1. Tăng version và versionCode của client/Android, cập nhật bài kiểm thử.
2. Build APK bằng cùng chứng thư Android đã triển khai; build EXE nhúng đúng agent/core/updater/public key hiện hành.
3. Chạy kiểm thử, cập nhật checksum tài liệu, commit và push source cùng các tệp `release`.
4. Lấy SHA 40 ký tự của commit chứa các artifact rồi ký manifest bằng khóa riêng lưu ngoài repository:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\updates\Publish-UpdateManifest.ps1 `
    -PrivateKeyPath 'DUONG-DAN-NGOAI-REPOSITORY\roomtn20-update-private.xml' `
    -SourceCommit 'SHA-COMMIT-40-KY-TU' -Version '1.2.1' -VersionCode 6
```

5. Kiểm tra `stable.json` bằng khóa công khai, commit riêng manifest và push. Bản cập nhật tự động chỉ được quảng bá ở bước này.

Không tạo khóa ký mới cho mỗi phiên bản: các client đã cài chỉ tin khóa công khai cũ. Chỉ dùng `New-UpdateSigningKey.ps1` khi khởi tạo dòng phần mềm mới hoặc thực hiện quy trình thay khóa có kế hoạch. Mất khóa riêng sẽ không thể ký các bản cập nhật được client cũ tin cậy; cần sao lưu khóa ở nơi riêng, được bảo vệ.

Chữ ký manifest này không phải chữ ký Authenticode của EXE. Windows vẫn có thể hiển thị nhà xuất bản chưa xác định cho EXE chưa có chứng thư Authenticode thương mại.

## Giới hạn hệ thống

Windows không tự mở đóng băng C hoặc reboot để nâng cấp. Updater báo `blocked-uwf` và chờ bảo trì; việc kiểm tra/cài tiếp diễn khi C đã được mở bảo vệ. Android tự kiểm tra/tải nhưng có thể cần người dùng chấp thuận nguồn cài và xác nhận trong trình cài của hệ thống. Không có thành phần vượt quyền hoặc yêu cầu root điện thoại.
