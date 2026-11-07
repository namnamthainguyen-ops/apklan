package com.lanpower.standalone;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class RoomMediaStorage {
    private final Context context;
    RoomMediaStorage(Context context) { this.context = context.getApplicationContext(); }

    RoomPlaylist importFiles(String roomId, RoomPlaylist current, List<Uri> sources) throws Exception {
        if (sources.isEmpty() || sources.size() + current.files.size() > RoomPlaylist.MAX_FILES) throw new IOException("Mỗi phòng tối đa 20 ảnh/video");
        RoomPlaylist result = current.copy();
        Set<String> known = new HashSet<>();
        for (MediaItem item : result.files) known.add(item.id);
        long total = result.totalBytes();
        boolean changed = false;
        for (Uri source : sources) {
            File temporary = File.createTempFile("incoming-", ".part", directory(roomId));
            try {
                MessageDigest hash = MessageDigest.getInstance("SHA-256");
                long length = 0;
                try (InputStream input = context.getContentResolver().openInputStream(source); FileOutputStream output = new FileOutputStream(temporary)) {
                    if (input == null) throw new IOException("Không đọc được tệp đã chọn");
                    byte[] buffer = new byte[32768];
                    for (int count; (count = input.read(buffer)) != -1;) {
                        if (Thread.currentThread().isInterrupted()) throw new IOException("Đã dừng sao chép nội dung");
                        length += count;
                        if (length > RoomPlaylist.MAX_FILE_BYTES || total + length > RoomPlaylist.MAX_TOTAL_BYTES) throw new IOException("Giới hạn 128 MiB mỗi tệp và 256 MiB mỗi phòng");
                        output.write(buffer, 0, count);
                        hash.update(buffer, 0, count);
                    }
                }
                String type = detectType(temporary);
                if (!"video/mp4".equals(type)) validateImage(temporary);
                String id = RoomPlaylist.hex(hash.digest());
                if (known.contains(id)) continue;
                MediaItem item = new MediaItem(id, displayName(source), type, length, "video/mp4".equals(type) ? 0 : 10);
                item.validate();
                File destination = file(roomId, item);
                if (destination.exists() && !destination.delete()) throw new IOException("Không cập nhật được bản sao nội dung");
                if (!temporary.renameTo(destination)) throw new IOException("Không lưu được nội dung của phòng");
                result.files.add(item);
                known.add(id);
                total += length;
                changed = true;
            } finally { if (temporary.exists()) temporary.delete(); }
        }
        if (changed) result.changed();
        result.validate();
        return result;
    }

    File file(String roomId, MediaItem item) throws IOException {
        item.validate();
        return new File(directory(roomId), item.id + item.extension());
    }

    void cleanUnused(String roomId, RoomPlaylist playlist) {
        try {
            Set<String> keep = new HashSet<>();
            for (MediaItem item : playlist.files) keep.add(item.id + item.extension());
            File[] contents = directory(roomId).listFiles();
            if (contents != null) for (File file : contents) if (file.isFile() && !keep.contains(file.getName())) file.delete();
        } catch (IOException ignored) { }
    }

    private File directory(String roomId) throws IOException {
        if (roomId == null || !roomId.matches("[A-Za-z0-9-]{1,80}")) throw new IOException("Phòng không hợp lệ");
        File result = new File(new File(context.getFilesDir(), "room_media"), roomId);
        if (!result.isDirectory() && !result.mkdirs()) throw new IOException("Không tạo được nơi lưu nội dung phòng");
        return result;
    }

    private String displayName(Uri uri) {
        String name = "Nội dung";
        try (Cursor cursor = context.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) name = cursor.getString(0);
        } catch (Exception ignored) { }
        if (name == null || name.isEmpty()) name = "Nội dung";
        name = name.replace('\r', ' ').replace('\n', ' ').replace('\0', ' ');
        return name.length() > 160 ? name.substring(0, 160) : name;
    }

    private static String detectType(File file) throws IOException {
        byte[] header = new byte[16];
        int count;
        try (InputStream input = new FileInputStream(file)) { count = input.read(header); }
        if (count >= 3 && (header[0] & 255) == 255 && (header[1] & 255) == 216 && (header[2] & 255) == 255) return "image/jpeg";
        byte[] png = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};
        if (count >= 8 && java.util.Arrays.equals(java.util.Arrays.copyOf(header, 8), png)) return "image/png";
        if (count >= 12 && header[4] == 'f' && header[5] == 't' && header[6] == 'y' && header[7] == 'p') return "video/mp4";
        throw new IOException("Tệp không phải ảnh JPG/PNG hoặc video MP4 được hỗ trợ");
    }

    private static void validateImage(File file) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        if (bounds.outWidth < 1 || bounds.outHeight < 1 || bounds.outWidth > 16384 || bounds.outHeight > 16384
                || (long) bounds.outWidth * bounds.outHeight > 64_000_000) throw new IOException("Ảnh lỗi hoặc quá lớn; chọn ảnh tối đa 64 triệu điểm ảnh");
        BitmapFactory.Options decode = new BitmapFactory.Options();
        decode.inSampleSize = 1;
        while (bounds.outWidth / decode.inSampleSize > 1024 || bounds.outHeight / decode.inSampleSize > 1024) decode.inSampleSize *= 2;
        Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), decode);
        if (bitmap == null) throw new IOException("Không giải mã được ảnh đã chọn");
        bitmap.recycle();
    }
}
