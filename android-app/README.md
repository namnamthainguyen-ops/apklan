# LanPower Standalone Android

App Android quản lý nguồn trực tiếp trong LAN, không dùng API hay máy chủ trung tâm.

- UDP `17840`: phát hiện agent và gửi lệnh `shutdown`/`restart` có HMAC-SHA256.
- UDP `9`: gửi Wake-on-LAN Magic Packet trực tiếp.
- Management Key được mã hóa bằng Android Keystore.
- Yêu cầu Android 8.0 (API 26) trở lên.

Build bằng Android Studio hoặc Gradle 8.9 + JDK 17 + Android SDK Platform/Build Tools 35:

```powershell
gradle assembleDebug
```

APK debug được tạo tại `app/build/outputs/apk/debug/app-debug.apk`.
