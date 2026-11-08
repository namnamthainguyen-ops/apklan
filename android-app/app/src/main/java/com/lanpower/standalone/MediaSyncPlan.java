package com.lanpower.standalone;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;

final class MediaSyncPlan {
    final Set<String> missing = new HashSet<>();
    final Set<String> removedIds = new HashSet<>();
    final Set<String> addedIds = new HashSet<>();
    final StringBuilder removedNames = new StringBuilder();
    final StringBuilder addedNames = new StringBuilder();
    final StringBuilder uploadedNames = new StringBuilder();
    long bytes;

    static MediaSyncPlan read(JSONObject envelope, String key, String deviceId, RoomPlaylist playlist, String nonce) throws Exception {
        byte[] payload = Base64.getDecoder().decode(envelope.optString("payload"));
        String canonical = "media-plan\n" + nonce + "\n" + deviceId + "\n" + playlist.version + "\n" + RoomPlaylist.sha256(payload);
        if (!"media-plan".equals(envelope.optString("type")) || !nonce.equals(envelope.optString("nonce")) || !deviceId.equals(envelope.optString("deviceId"))
                || !playlist.version.equals(envelope.optString("version")) || !LanProtocol.verify(key, canonical, envelope.optString("signature"))) throw new IOException("Kế hoạch đồng bộ không được xác thực; kiểm tra client đã cập nhật");
        JSONObject plan = new JSONObject(new String(payload, StandardCharsets.UTF_8));
        JSONArray missing = plan.getJSONArray("missing"), removed = plan.getJSONArray("removed"), added = plan.getJSONArray("added");
        Set<String> wanted = new HashSet<>();
        for (MediaItem item : playlist.files) wanted.add(item.id);
        if (missing.length() > 20 || removed.length() > 20 || added.length() > 20) throw new IOException("Kế hoạch đồng bộ vượt giới hạn");
        MediaSyncPlan result = new MediaSyncPlan();
        for (int i = 0; i < missing.length(); i++) {
            String id = missing.getString(i);
            if (!wanted.contains(id) || !result.missing.add(id)) throw new IOException("Danh sách tệp cần tải không hợp lệ");
        }
        for (int i = 0; i < removed.length(); i++) {
            JSONObject item = removed.getJSONObject(i);
            String id = item.getString("id"), name = item.getString("name");
            if (!id.matches("[a-f0-9]{64}") || wanted.contains(id) || !result.removedIds.add(id) || name.length() > 160 || name.matches("(?s).*[\\r\\n\\x00].*")) throw new IOException("Danh sách tệp bỏ không hợp lệ");
            if (i < 3) { if (i > 0) result.removedNames.append(", "); result.removedNames.append(name); }
        }
        for (int i = 0; i < added.length(); i++) {
            JSONObject item = added.getJSONObject(i);
            String id = item.getString("id"), name = item.getString("name");
            if (!wanted.contains(id) || !result.addedIds.add(id) || name.length() > 160 || name.matches("(?s).*[\\r\\n\\x00].*")) throw new IOException("Danh sách tệp thêm không hợp lệ");
            if (i < 3) { if (i > 0) result.addedNames.append(", "); result.addedNames.append(name); }
        }
        int uploadedCount = 0;
        for (MediaItem item : playlist.files) if (result.missing.contains(item.id)) {
            result.bytes += item.length;
            if (uploadedCount++ < 3) { if (result.uploadedNames.length() > 0) result.uploadedNames.append(", "); result.uploadedNames.append(item.name); }
        }
        return result;
    }
}
