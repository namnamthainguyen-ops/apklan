package com.lanpower.standalone;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

final class MediaTransfer {
    interface Progress { void sent(long done, long total); }
    private volatile SSLSocket active;
    private volatile boolean closed;

    void close() { closed = true; SSLSocket socket = active; if (socket != null) try { socket.close(); } catch (IOException ignored) { } }

    String send(String roomId, String key, ManagedDevice device, RoomPlaylist playlist, RoomMediaStorage storage, Progress progress) throws Exception {
        playlist.validate();
        if (!device.online || !VpnTargets.isPrivateAddress(device.ip)) throw new IOException("Máy đang ngoại tuyến hoặc chưa có địa chỉ LAN/VPN hợp lệ");
        if (!device.supportsMedia()) throw new IOException(device.mediaUnavailableReason());
        if (!device.supports("mediasync")) throw new IOException("Hãy cập nhật client Windows lên 1.4.0 để đồng bộ tệp quảng cáo còn thiếu");
        if (!playlist.version.matches("[a-f0-9]{32}")) throw new IOException("Phòng chưa có nội dung để gửi");
        JSONArray files = new JSONArray();
        for (MediaItem item : playlist.files) {
            File file = storage.file(roomId, item);
            if (!file.isFile() || file.length() != item.length) throw new IOException("Thiếu bản sao nội dung: " + item.name + ". Hãy chọn lại tệp");
            files.put(new JSONObject().put("id", item.id).put("name", item.name).put("type", item.type).put("length", item.length).put("sha256", item.id).put("durationSeconds", item.durationSeconds));
        }
        byte[] payload = new JSONObject().put("schema", 1).put("version", playlist.version).put("files", files).toString().getBytes(StandardCharsets.UTF_8);
        String nonce = UUID.randomUUID().toString().replace("-", "");
        long timestamp = Instant.now().getEpochSecond();
        long total = playlist.totalBytes();
        JSONObject header = new JSONObject().put("deviceId", device.id).put("timestamp", timestamp).put("nonce", nonce).put("mediaVersion", playlist.version)
                .put("totalBytes", total).put("payload", Base64.getEncoder().encodeToString(payload))
                .put("transferMode", "delta-v1")
                .put("signature", LanProtocol.hmac(key, LanProtocol.mediaCanonical(device.id, timestamp, nonce, playlist.version, total, RoomPlaylist.sha256(payload)) + "\ndelta-v1"));
        byte[] headerLine = (header + "\n").getBytes(StandardCharsets.UTF_8);
        if (headerLine.length > 32768) throw new IOException("Danh sách nội dung quá dài");
        X509TrustManager pinned = new X509TrustManager() {
            @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            @Override public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException { throw new CertificateException("Client trust is not used"); }
            @Override public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                try {
                    if (chain == null || chain.length == 0) throw new CertificateException("Thiếu chứng thư máy đích");
                    chain[0].checkValidity();
                    String actual = RoomPlaylist.sha256(chain[0].getEncoded());
                    if (!MessageDigest.isEqual(actual.getBytes(StandardCharsets.US_ASCII), device.mediaCertSha256.getBytes(StandardCharsets.US_ASCII))) throw new CertificateException("Chứng thư không khớp máy đã xác thực");
                } catch (CertificateException error) { throw error; }
                catch (Exception error) { throw new CertificateException(error); }
            }
        };
        SSLContext tls = SSLContext.getInstance("TLSv1.2");
        tls.init(null, new TrustManager[]{pinned}, new java.security.SecureRandom());
        ScheduledExecutorService deadline = Executors.newSingleThreadScheduledExecutor();
        try (SSLSocket socket = (SSLSocket) tls.getSocketFactory().createSocket()) {
            active = socket;
            if (closed) throw new IOException("Đã dừng gửi nội dung");
            socket.setEnabledProtocols(new String[]{"TLSv1.2"});
            socket.connect(new InetSocketAddress(device.ip, device.mediaPort), 10000);
            socket.setSoTimeout(120000);
            deadline.schedule(() -> { try { socket.close(); } catch (IOException ignored) { } }, 10, TimeUnit.MINUTES);
            socket.startHandshake();
            OutputStream output = socket.getOutputStream();
            output.write(headerLine);
            output.flush();
            JSONObject envelope = new JSONObject(readLine(socket.getInputStream()));
            MediaSyncPlan plan = MediaSyncPlan.read(envelope, key, device.id, playlist, nonce);
            java.util.Set<String> missing = plan.missing;
            total = plan.bytes;
            progress.sent(0, total);
            long sent = 0;
            long lastProgress = -1;
            for (MediaItem item : playlist.files) {
                if (!missing.contains(item.id)) continue;
                MessageDigest hash = MessageDigest.getInstance("SHA-256");
                long copied = 0;
                try (InputStream input = new FileInputStream(storage.file(roomId, item))) {
                    byte[] buffer = new byte[32768];
                    for (int count; (count = input.read(buffer)) != -1;) {
                        if (closed || Thread.currentThread().isInterrupted()) throw new IOException("Đã dừng gửi nội dung");
                        copied += count;
                        if (copied > item.length) throw new IOException("Nội dung đã thay đổi; hãy chọn lại tệp");
                        output.write(buffer, 0, count);
                        hash.update(buffer, 0, count);
                        sent += count;
                        long percent = total == 0 ? 100 : sent * 100 / total;
                        if (percent != lastProgress) { progress.sent(sent, total); lastProgress = percent; }
                    }
                }
                if (copied != item.length || !item.id.equals(RoomPlaylist.hex(hash.digest()))) throw new IOException("Checksum nội dung không khớp: " + item.name);
            }
            output.flush();
            JSONObject response = new JSONObject(readLine(socket.getInputStream()));
            String status = response.optString("status");
            String message = response.optString("message");
            if (!nonce.equals(response.optString("nonce")) || !device.id.equals(response.optString("deviceId")) || !playlist.version.equals(response.optString("version"))
                    || !LanProtocol.verify(key, LanProtocol.mediaResultCanonical(nonce, device.id, playlist.version, status, message), response.optString("signature"))) throw new IOException("Phản hồi nhận nội dung không được xác thực");
            if (!"accepted".equals(status)) throw new IOException(message);
            return "Đã đồng bộ: dùng lại " + (playlist.files.size() - missing.size()) + ", tải " + missing.size() + ", thêm " + plan.addedIds.size() + ", bỏ " + plan.removedIds.size() + " khỏi danh sách phát."
                    + (plan.uploadedNames.length() == 0 ? "" : "\nTải: " + plan.uploadedNames + (missing.size() > 3 ? "…" : ""))
                    + (plan.addedNames.length() == 0 ? "" : "\nThêm: " + plan.addedNames + (plan.addedIds.size() > 3 ? "…" : ""))
                    + (plan.removedNames.length() == 0 ? "" : "\nBỏ: " + plan.removedNames + (plan.removedIds.size() > 3 ? "…" : ""));
        } finally { active = null; deadline.shutdownNow(); }
    }

    private static String readLine(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (int value; (value = input.read()) != -1;) {
            if (value == '\n') return bytes.toString(StandardCharsets.UTF_8.name());
            bytes.write(value);
            // A signed plan can describe 20 added plus 20 removed Unicode names.
            if (bytes.size() > 65536) throw new IOException("Phản hồi nội dung vượt giới hạn");
        }
        throw new IOException("Mất kết nối trước khi máy xác nhận nội dung");
    }
}
