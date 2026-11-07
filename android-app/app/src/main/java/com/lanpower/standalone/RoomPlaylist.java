package com.lanpower.standalone;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

final class RoomPlaylist {
    static final int MAX_FILES = 20;
    static final long MAX_FILE_BYTES = 128L * 1024 * 1024;
    static final long MAX_TOTAL_BYTES = 256L * 1024 * 1024;
    String version = "none";
    final List<MediaItem> files = new ArrayList<>();

    void changed() { version = UUID.randomUUID().toString().replace("-", ""); }

    long totalBytes() {
        long total = 0;
        for (MediaItem item : files) total = Math.addExact(total, item.length);
        return total;
    }

    void validate() {
        if (!"none".equals(version) && !version.matches("[a-f0-9]{32}")) throw new IllegalArgumentException("Phiên bản quảng cáo không hợp lệ");
        if (files.size() > MAX_FILES || totalBytes() > MAX_TOTAL_BYTES) throw new IllegalArgumentException("Tối đa 20 nội dung, tổng dung lượng 256 MiB mỗi phòng");
        Set<String> ids = new HashSet<>();
        for (MediaItem item : files) {
            item.validate();
            if (!ids.add(item.id)) throw new IllegalArgumentException("Nội dung trùng trong danh sách quảng cáo");
        }
        if (!files.isEmpty() && "none".equals(version)) throw new IllegalArgumentException("Danh sách chưa có phiên bản");
    }

    RoomPlaylist copy() {
        RoomPlaylist result = new RoomPlaylist();
        result.version = version;
        for (MediaItem item : files) result.files.add(item.copy());
        return result;
    }

    static String hex(byte[] bytes) {
        final char[] alphabet = "0123456789abcdef".toCharArray();
        char[] result = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            result[2 * i] = alphabet[(bytes[i] & 255) >>> 4];
            result[2 * i + 1] = alphabet[bytes[i] & 15];
        }
        return new String(result);
    }

    static String sha256(byte[] bytes) throws Exception { return hex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
}

final class MediaItem {
    final String id;
    final String name;
    final String type;
    final long length;
    int durationSeconds;

    MediaItem(String id, String name, String type, long length, int durationSeconds) {
        this.id = id;
        this.name = name;
        this.type = type;
        this.length = length;
        this.durationSeconds = durationSeconds;
    }

    void validate() {
        if (id == null || !id.matches("[a-f0-9]{64}") || name == null || name.isEmpty() || name.length() > 160
                || name.indexOf('\n') >= 0 || name.indexOf('\r') >= 0 || name.indexOf('\0') >= 0) throw new IllegalArgumentException("Thông tin tệp quảng cáo không hợp lệ");
        if (!"image/jpeg".equals(type) && !"image/png".equals(type) && !"video/mp4".equals(type)) throw new IllegalArgumentException("Chỉ nhận ảnh JPG/PNG và video MP4");
        if (length < 1 || length > RoomPlaylist.MAX_FILE_BYTES) throw new IllegalArgumentException("Mỗi tệp phải từ 1 byte đến 128 MiB");
        if ("video/mp4".equals(type) ? durationSeconds != 0 : durationSeconds < 1 || durationSeconds > 600) throw new IllegalArgumentException("Thời lượng ảnh phải từ 1 đến 600 giây");
    }

    String extension() { return "image/jpeg".equals(type) ? ".jpg" : "image/png".equals(type) ? ".png" : ".mp4"; }
    MediaItem copy() { return new MediaItem(id, name, type, length, durationSeconds); }
}
