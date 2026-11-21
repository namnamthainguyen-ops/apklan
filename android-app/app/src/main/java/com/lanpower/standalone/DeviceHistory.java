package com.lanpower.standalone;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.text.DateFormat;
import java.util.Date;

final class DeviceHistory {
    private final SharedPreferences preferences;
    private volatile boolean writeFailed;

    DeviceHistory(Context context) {
        this(context.getSharedPreferences("device-history", Context.MODE_PRIVATE));
    }

    DeviceHistory(SharedPreferences preferences) { this.preferences = preferences; }

    synchronized void record(String roomId, String deviceId, String action, String result) {
        try {
            String key = roomId + "|" + deviceId;
            JSONArray previous = new JSONArray(preferences.getString(key, "[]"));
            JSONArray next = new JSONArray();
            for (int i = Math.max(0, previous.length() - 49); i < previous.length(); i++)
                next.put(previous.getJSONObject(i));
            String message = result.length() > 1200 ? result.substring(0, 1200) + "…" : result;
            next.put(new JSONObject().put("time", System.currentTimeMillis()).put("action", action).put("result", message));
            writeFailed = !preferences.edit().putString(key, next.toString()).commit();
        } catch (Exception error) { writeFailed = true; }
    }

    synchronized String read(String roomId, String deviceId) {
        try {
            JSONArray entries = new JSONArray(preferences.getString(roomId + "|" + deviceId, "[]"));
            StringBuilder text = new StringBuilder(writeFailed ? "Không lưu được một phần nhật ký. Kiểm tra dung lượng điện thoại.\n\n" : "");
            for (int i = entries.length() - 1; i >= 0; i--) {
                JSONObject entry = entries.getJSONObject(i);
                text.append(DateFormat.getDateTimeInstance().format(new Date(entry.getLong("time"))))
                    .append(" • ").append(entry.getString("action")).append("\n")
                    .append(entry.getString("result")).append("\n\n");
            }
            if (entries.length() == 0) text.append("Chưa có nhật ký lệnh của máy trên APK này.");
            return text.toString();
        } catch (Exception error) { return "Không đọc được nhật ký đã lưu."; }
    }
}
