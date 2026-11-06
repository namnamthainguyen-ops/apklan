package com.lanpower.standalone;

import android.content.Context;
import android.content.Intent;
import android.app.PendingIntent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.PackageInstaller;
import android.os.Build;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Checks/downloads independently of LAN work; installation always requires an explicit UI action. */
final class AppUpdater {
    interface Listener { void changed(String status, boolean installReady); }
    private final Context context;
    private final Listener listener;
    private final boolean disabled;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final SharedPreferences preferences;
    private volatile boolean closed;
    private volatile boolean checking;
    private volatile HttpURLConnection connection;
    private volatile SignedManifest ready;
    private long lastCheck;

    AppUpdater(Context context, Listener listener, boolean disabled) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        this.disabled = disabled;
        preferences = context.getSharedPreferences("room_tn20_updates", Context.MODE_PRIVATE);
        UpdateInstallReceiver.listener = listener;
    }

    void check(boolean manual) {
        if (closed || disabled || checking || (!manual && System.currentTimeMillis() - lastCheck < 6 * 60 * 60 * 1000L)) return;
        checking = true;
        lastCheck = System.currentTimeMillis();
        report("Đang kiểm tra cập nhật có chữ ký…", false);
        worker.execute(() -> {
            try {
                byte[] manifest = downloadBytes(SignedManifest.MANIFEST_URL, 1024 * 1024);
                SignedManifest release = SignedManifest.verify(new String(manifest, StandardCharsets.UTF_8), UpdateTrust.key(),
                        BuildConfig.VERSION_CODE, preferences.getInt("highestVersionCode", BuildConfig.VERSION_CODE));
                if (!preferences.edit().putInt("highestVersionCode", release.versionCode).commit()) throw new IOException("Không lưu được trạng thái chống hạ phiên bản");
                if (release.versionCode == BuildConfig.VERSION_CODE) {
                    ready = null;
                    report("Hiện tại " + BuildConfig.VERSION_NAME + " • mới nhất " + release.version + " • Đã cập nhật", false);
                    return;
                }
                report("Hiện tại " + BuildConfig.VERSION_NAME + " • mới nhất " + release.version + " • Đang tải và xác minh APK…", false);
                File temporary = File.createTempFile("room-tn20-update-", ".download", context.getCacheDir());
                File apk = new File(context.getCacheDir(), UpdateApkProvider.FILE_NAME);
                try {
                    downloadFile(release.url, temporary, 100 * 1024 * 1024);
                    validateApk(temporary, release);
                    if (closed) throw new IOException("Đã dừng cập nhật");
                    if (apk.exists() && !apk.delete()) throw new IOException("Không thay được APK trong cache");
                    if (!temporary.renameTo(apk)) throw new IOException("Không lưu được APK đã xác minh");
                    ready = release;
                    report("Hiện tại " + BuildConfig.VERSION_NAME + " • mới nhất " + release.version + " • APK đã xác minh. Chọn Cài bản mới và xác nhận trong Android.", true);
                    if (Build.VERSION.SDK_INT >= 31 && context.getPackageManager().canRequestPackageInstalls()) {
                        try { installSession(apk); }
                        catch (Exception error) { report("APK đã xác minh. Android chưa cho cài tự động; chọn Cài bản mới để xác nhận.", true); }
                    }
                } finally { if (temporary.exists()) temporary.delete(); }
            } catch (Exception error) {
                ready = null;
                report("Hiện tại " + BuildConfig.VERSION_NAME + " • Chưa cập nhật: " + (error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()), false);
            } finally { checking = false; }
        });
    }

    void validateReady() throws Exception {
        if (ready == null) throw new IOException("Chưa có APK mới đã xác minh");
        validateApk(new File(context.getCacheDir(), UpdateApkProvider.FILE_NAME), ready);
    }

    void close() {
        closed = true;
        HttpURLConnection active = connection;
        if (active != null) active.disconnect();
        worker.shutdownNow();
        if (UpdateInstallReceiver.listener == listener) UpdateInstallReceiver.listener = null;
    }

    private void installSession(File apk) throws Exception {
        if (Build.VERSION.SDK_INT < 31) return;
        validateReady();
        PackageInstaller installer = context.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(SignedManifest.PACKAGE);
        params.setSize(apk.length());
        params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        int id = installer.createSession(params);
        boolean committed = false;
        try (PackageInstaller.Session session = installer.openSession(id)) {
            try (InputStream input = new FileInputStream(apk); java.io.OutputStream output = session.openWrite("RoomTN20.apk", 0, apk.length())) {
                byte[] buffer = new byte[32768];
                for (int count; (count = input.read(buffer)) != -1;) {
                    if (closed) throw new IOException("Đã dừng cập nhật");
                    output.write(buffer, 0, count);
                }
                session.fsync(output);
            }
            Intent callback = new Intent(context, UpdateInstallReceiver.class).setAction("com.lanpower.standalone.UPDATE_RESULT." + id);
            PendingIntent pending = PendingIntent.getBroadcast(context, id, callback, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
            report("Đã gửi bản mới cho Android. Hệ thống sẽ cập nhật hoặc yêu cầu xác nhận qua nút Cài bản mới.", false);
            session.commit(pending.getIntentSender());
            committed = true;
        } finally { if (!committed) installer.abandonSession(id); }
    }

    private void validateApk(File file, SignedManifest release) throws Exception {
        if (!file.isFile() || file.length() == 0 || file.length() > 100 * 1024 * 1024L) throw new SecurityException("APK không hợp lệ");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[32768];
            for (int count; (count = input.read(buffer)) != -1;) digest.update(buffer, 0, count);
        }
        if (!hex(digest.digest()).equals(release.sha256)) throw new SecurityException("SHA-256 APK không khớp manifest");
        PackageManager manager = context.getPackageManager();
        int flags = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
        PackageInfo archive = manager.getPackageArchiveInfo(file.getAbsolutePath(), flags);
        if (archive == null || !SignedManifest.PACKAGE.equals(archive.packageName)) throw new SecurityException("APK sai ứng dụng");
        long code = Build.VERSION.SDK_INT >= 28 ? archive.getLongVersionCode() : archive.versionCode;
        if (code != release.versionCode || code <= BuildConfig.VERSION_CODE || !release.version.equals(archive.versionName)) throw new SecurityException("APK không đúng phiên bản mới đã ký");
        String archiveSigner = signer(archive);
        String installedSigner = signer(manager.getPackageInfo(context.getPackageName(), flags));
        if (!release.signerSha256.equals(archiveSigner) || !archiveSigner.equals(installedSigner)) throw new SecurityException("Chứng thư ký APK khác ứng dụng hiện tại");
    }

    private static String signer(PackageInfo info) throws Exception {
        Signature[] signatures = Build.VERSION.SDK_INT >= 28 && info.signingInfo != null ? info.signingInfo.getApkContentsSigners() : info.signatures;
        if (signatures == null || signatures.length != 1) throw new SecurityException("APK phải có đúng một chứng thư ký");
        return hex(MessageDigest.getInstance("SHA-256").digest(signatures[0].toByteArray()));
    }

    private byte[] downloadBytes(String address, int limit) throws Exception {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            copyDownload(address, limit, bytes);
            return bytes.toByteArray();
        }
    }

    private void downloadFile(String address, File target, int limit) throws Exception {
        try (FileOutputStream output = new FileOutputStream(target)) { copyDownload(address, limit, output); }
    }

    private void copyDownload(String address, int limit, java.io.OutputStream output) throws Exception {
        String current = address;
        for (int redirect = 0; redirect < 4; redirect++) {
            SignedManifest.validateUrl(current);
            HttpURLConnection request = (HttpURLConnection) new URL(current).openConnection();
            connection = request;
            request.setInstanceFollowRedirects(false);
            request.setConnectTimeout(12000);
            request.setReadTimeout(15000);
            request.setRequestProperty("Accept-Encoding", "identity");
            try {
                int status = request.getResponseCode();
                if (status >= 300 && status <= 399) {
                    String location = request.getHeaderField("Location");
                    if (location == null) throw new IOException("Redirect thiếu URL");
                    current = new URL(new URL(current), location).toString();
                    SignedManifest.validateUrl(current);
                    continue;
                }
                if (status != 200) throw new IOException("HTTP " + status);
                if (request.getContentLengthLong() > limit) throw new IOException("Tệp cập nhật vượt giới hạn dung lượng");
                try (InputStream input = request.getInputStream()) {
                    byte[] buffer = new byte[32768];
                    long total = 0;
                    for (int count; (count = input.read(buffer)) != -1;) {
                        if (closed || Thread.currentThread().isInterrupted()) throw new IOException("Đã dừng tải cập nhật");
                        total += count;
                        if (total > limit) throw new IOException("Tệp cập nhật vượt giới hạn dung lượng");
                        output.write(buffer, 0, count);
                    }
                }
                return;
            } finally { request.disconnect(); connection = null; }
        }
        throw new IOException("Quá nhiều chuyển hướng tải cập nhật");
    }

    private void report(String message, boolean available) { if (!closed) listener.changed(message, available); }
    private static String hex(byte[] value) {
        StringBuilder result = new StringBuilder();
        for (byte part : value) result.append(String.format(Locale.ROOT, "%02x", part & 255));
        return result.toString();
    }
}
