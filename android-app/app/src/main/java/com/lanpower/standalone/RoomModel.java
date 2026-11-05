package com.lanpower.standalone;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class RoomModel {
    final String id;
    final String name;
    final boolean legacy;
    final Map<String, ManagedDevice> devices = new LinkedHashMap<>();
    String key;
    long lastScan;
    String scanError = "";

    RoomModel(String id, String name, boolean legacy, String key) {
        if (id == null || id.isEmpty() || name == null || name.trim().isEmpty()) throw new IllegalArgumentException("Phòng không hợp lệ");
        this.id = id;
        this.name = name.trim();
        this.legacy = legacy;
        this.key = key;
    }

    void applyScan(Collection<ManagedDevice> found, long scanTime) {
        for (ManagedDevice device : devices.values()) device.online = false;
        for (ManagedDevice device : found) {
            device.online = true;
            device.lastSeen = scanTime;
            devices.put(device.id, device);
        }
        lastScan = scanTime;
        scanError = "";
    }

    int onlineCount() {
        int count = 0;
        for (ManagedDevice device : devices.values()) if (device.online) count++;
        return count;
    }

    List<ManagedDevice> selected(Set<String> ids) {
        List<ManagedDevice> result = new ArrayList<>();
        for (ManagedDevice device : devices.values()) if (ids.contains(device.id)) result.add(device.copy());
        return result;
    }
}

final class ManagedDevice {
    String id;
    String hostname;
    String ip;
    String mac;
    String uwf = "unknown";
    String capabilities = "power";
    String agentVersion = "unknown";
    boolean stateAuthenticated;
    boolean online;
    long lastSeen;

    ManagedDevice(String id, String hostname, String ip, String mac) {
        this.id = id;
        this.hostname = hostname;
        this.ip = ip;
        this.mac = mac;
    }

    boolean supportsUwf() {
        if (!stateAuthenticated) return false;
        for (String capability : capabilities.split(",")) if ("uwf".equals(capability)) return true;
        return false;
    }

    String trustedUwf() { return stateAuthenticated ? uwf : "Chưa xác thực / agent cũ"; }

    String uwfDescription() {
        if (!stateAuthenticated) return "Chưa xác thực / agent cũ";
        if (!supportsUwf()) return "Chưa cài / chưa chuẩn bị UWF";
        if (uwf.matches("(ON|OFF)( \\(next (ON|OFF)\\))?")) {
            boolean current = uwf.startsWith("ON");
            boolean next = uwf.contains("next ON") || (!uwf.contains("next OFF") && current);
            return "Hiện tại: " + (current ? "Đóng băng" : "Không đóng băng")
                    + " • Sau khởi động lại: " + (next ? "Đóng băng" : "Không đóng băng");
        }
        return uwf;
    }

    ManagedDevice copy() {
        ManagedDevice value = new ManagedDevice(id, hostname, ip, mac);
        value.uwf = uwf;
        value.capabilities = capabilities;
        value.agentVersion = agentVersion;
        value.stateAuthenticated = stateAuthenticated;
        value.online = online;
        value.lastSeen = lastSeen;
        return value;
    }
}
