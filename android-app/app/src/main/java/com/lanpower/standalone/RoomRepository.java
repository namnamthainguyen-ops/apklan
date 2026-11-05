package com.lanpower.standalone;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

final class RoomRepository {
    private final Context context;
    private final SecureKeyStore keys;
    private final SharedPreferences preferences;

    RoomRepository(Context context, SecureKeyStore keys) {
        this.context = context.getApplicationContext();
        this.keys = keys;
        preferences = context.getSharedPreferences("lanpower_rooms_v2", Context.MODE_PRIVATE);
    }

    List<RoomModel> load() throws Exception {
        List<RoomModel> rooms = new ArrayList<>();
        if (preferences.contains("rooms")) {
            JSONArray values = new JSONArray(preferences.getString("rooms", "[]"));
            for (int i = 0; i < values.length(); i++) {
                JSONObject value = values.getJSONObject(i);
                RoomModel room = new RoomModel(value.getString("id"), value.getString("name"), value.optBoolean("legacy"), keys.loadRoom(value.getString("id")));
                readDevices(room, value.optJSONArray("devices"));
                room.lastScan = value.optLong("lastScan");
                rooms.add(room);
            }
            return rooms;
        }
        String legacyKey = keys.load();
        JSONArray legacyDevices = new JSONArray(context.getSharedPreferences("lanpower_devices", Context.MODE_PRIVATE).getString("devices", "[]"));
        if (!legacyKey.isEmpty() || legacyDevices.length() > 0) {
            RoomModel room = new RoomModel("legacy-default", "Phòng cũ", true, legacyKey);
            readDevices(room, legacyDevices);
            if (!legacyKey.isEmpty()) keys.saveRoom(room.id, LanProtocol.normalizeKey(legacyKey));
            rooms.add(room);
        }
        save(rooms); // Legacy source remains intact in case a later recovery is necessary.
        return rooms;
    }

    void save(Collection<RoomModel> rooms) throws Exception {
        JSONArray values = new JSONArray();
        for (RoomModel room : rooms) {
            JSONArray devices = new JSONArray();
            for (ManagedDevice device : room.devices.values()) {
                devices.put(new JSONObject().put("id", device.id).put("hostname", device.hostname).put("ip", device.ip)
                        .put("mac", device.mac).put("uwf", device.uwf).put("capabilities", device.capabilities)
                        .put("stateAuthenticated", device.stateAuthenticated).put("agentVersion", device.agentVersion).put("lastSeen", device.lastSeen));
            }
            values.put(new JSONObject().put("id", room.id).put("name", room.name).put("legacy", room.legacy)
                    .put("lastScan", room.lastScan).put("devices", devices));
        }
        if (!preferences.edit().putString("rooms", values.toString()).commit()) throw new IllegalStateException("Không lưu được danh sách phòng");
    }

    private static void readDevices(RoomModel room, JSONArray values) throws Exception {
        if (values == null) return;
        for (int i = 0; i < values.length(); i++) {
            JSONObject value = values.getJSONObject(i);
            ManagedDevice device = new ManagedDevice(value.getString("id"), value.optString("hostname", "Không rõ tên"), value.optString("ip"), value.optString("mac"));
            device.uwf = value.optString("uwf", "unknown");
            device.capabilities = value.optString("capabilities", "power");
            device.agentVersion = value.optString("agentVersion", "unknown");
            device.stateAuthenticated = value.optBoolean("stateAuthenticated", false);
            device.lastSeen = value.optLong("lastSeen");
            device.online = false; // Saved presence never proves that a PC is still reachable.
            room.devices.put(device.id, device);
        }
    }
}
