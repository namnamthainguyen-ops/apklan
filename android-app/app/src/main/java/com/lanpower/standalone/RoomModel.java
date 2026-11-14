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
    final Map<String, RestartCheck> restartChecks = new LinkedHashMap<>();
    String key;
    long lastScan;
    long lastFullScan;
    String scanError = "";
    String networkTargets = "";
    RoomPlaylist playlist = new RoomPlaylist();

    RoomModel(String id, String name, boolean legacy, String key) {
        if (id == null || id.isEmpty() || name == null || name.trim().isEmpty()) throw new IllegalArgumentException("Phòng không hợp lệ");
        this.id = id;
        this.name = name.trim();
        this.legacy = legacy;
        this.key = key;
    }

    void applyScan(Collection<ManagedDevice> found, long scanTime) {
        applyScan(found, scanTime, true);
    }

    void applyScan(Collection<ManagedDevice> found, long scanTime, boolean full) {
        Set<String> answered = new java.util.HashSet<>();
        for (ManagedDevice device : found) {
            answered.add(device.listKey());
            device.online = true;
            device.missedScans = 0;
            device.lastSeen = scanTime;
            devices.put(device.listKey(), device);
            RestartCheck restart = restartChecks.get(device.listKey());
            if (restart != null && !restart.bootToken.isEmpty() && !device.bootToken().isEmpty()
                    && !restart.bootToken.equals(device.bootToken())) {
                restart.message = "Đã xác nhận máy khởi động lại và online";
                restart.finished = true;
            }
        }
        for (ManagedDevice device : devices.values()) {
            if (answered.contains(device.listKey())) continue;
            device.missedScans = Math.min(2, device.missedScans + 1);
            if (device.missedScans == 2) device.online = false;
        }
        for (RestartCheck restart : restartChecks.values()) {
            if (!restart.finished && scanTime >= restart.deadline) {
                restart.message = "Chưa xác nhận khởi động lại trong 5 phút";
                restart.finished = true;
            }
        }
        if (full) lastFullScan = scanTime;
        lastScan = scanTime;
        scanError = "";
    }

    void trackRestart(ManagedDevice device, long now) {
        restartChecks.put(device.listKey(), new RestartCheck(device.bootToken(), now + 300_000));
    }

    boolean duplicateId(ManagedDevice device) {
        for (ManagedDevice other : devices.values()) {
            if (other.online && other.missedScans == 0 && device.id.equals(other.id)
                    && !device.listKey().equals(other.listKey())) return true;
        }
        return false;
    }

    int onlineCount() {
        int count = 0;
        for (ManagedDevice device : devices.values()) if (device.online) count++;
        return count;
    }

    List<ManagedDevice> selected(Set<String> ids) {
        List<ManagedDevice> result = new ArrayList<>();
        for (ManagedDevice device : devices.values()) if (ids.contains(device.listKey())) result.add(device.copy());
        return result;
    }
}

final class RestartCheck {
    final String bootToken;
    final long deadline;
    String message;
    boolean finished;

    RestartCheck(String bootToken, long deadline) {
        this.bootToken = bootToken;
        this.deadline = deadline;
        message = bootToken.isEmpty() ? "Đã gửi yêu cầu. Client hiện tại chưa hỗ trợ xác minh khởi động lại"
                : "Đang chờ máy khởi động lại và online";
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
    int missedScans;
    long lastSeen;
    long clientVersionCode;
    String latestVersion = "unknown";
    String updateStatus = "unknown";
    String updateMessage = "Chưa xác thực thông tin cập nhật";
    boolean updateAuthenticated;
    boolean runtimeAuthenticated;
    String runtimeStatus = "unknown";
    String runtimeMessage = "";
    String lockState = "unknown";
    String mediaVersion = "none";
    int mediaPort;
    String mediaCertSha256 = "";
    boolean lockAuthenticated;

    ManagedDevice(String id, String hostname, String ip, String mac) {
        this.id = id;
        this.hostname = hostname;
        this.ip = ip;
        this.mac = mac;
    }

    String listKey() { try { return id + "|" + LanProtocol.normalizeMac(mac); } catch (IllegalArgumentException invalidMac) { return id; } }

    String desktopBlockReason() {
        if (!online) return "Máy chưa phản hồi";
        if (updateAuthenticated && clientVersionCode < 16) return "Cần client Windows 1.4.2 trở lên";
        if (!supports("desktopactions") && runtimeAuthenticated && !"ready".equals(runtimeStatus))
            return "Bộ điều khiển Windows chưa sẵn sàng: " + runtimeMessage;
        if (!supports("desktopactions")) return "Máy chưa xác nhận hỗ trợ lệnh";
        if (!lockAuthenticated) return "Chưa xác nhận trạng thái phiên Windows";
        switch (lockState) {
            case "unlocked": return "";
            case "windows-locked":
            case "locked": return "Màn hình đang khóa";
            case "signed-out": return "Chưa đăng nhập Windows";
            case "pending": return "Đang chờ xác nhận trạng thái màn hình";
            default: return "Phiên Windows chưa sẵn sàng. Kiểm tra máy đã đăng nhập.";
        }
    }

    boolean supportsUwf() {
        return supports("uwf");
    }

    String bootToken() {
        if (stateAuthenticated) for (String value : capabilities.split(","))
            if (value.matches("boot-[0-9]{10,20}")) return value;
        return "";
    }

    boolean supports(String name) {
        if (!stateAuthenticated) return false;
        for (String capability : capabilities.split(",")) if (name.equals(capability)) return true;
        return false;
    }

    String trustedUwf() { return stateAuthenticated ? uwf : "Chưa xác thực / agent cũ"; }

    String lockDescription() {
        if (!lockAuthenticated) return "Chưa xác thực / cần cập nhật client";
        switch (lockState) {
            case "locked": return "Đang khóa và hiển thị quảng cáo";
            case "unlocked": return "Đang mở";
            case "pending": return "Đang chờ màn hình xác nhận";
            case "windows-locked": return "Windows đang khóa";
            case "signed-out": return "Chưa đăng nhập Windows";
            case "ui-offline": return "Chưa có màn hình người dùng hoạt động";
            default: return "Chưa xác định";
        }
    }

    boolean supportsMedia() { return lockAuthenticated && supports("media") && mediaPort == 17842 && mediaCertSha256.matches("[a-f0-9]{64}"); }

    String mediaUnavailableReason() {
        if (runtimeAuthenticated && "not-configured".equals(runtimeStatus)) return "Client chưa chuẩn bị bộ nhận quảng cáo. Kiểm tra D: chưa đóng băng rồi cài đè client mới. Nếu C: đang đóng băng, mở bảo vệ và khởi động lại trước khi cài. " + runtimeMessage;
        if (runtimeAuthenticated && "error".equals(runtimeStatus)) return "Bộ nhận quảng cáo trên Windows lỗi: " + runtimeMessage;
        return "Client " + agentVersion + " chưa bật bộ nhận quảng cáo. Cài đè client mới trên máy con; cần D: chưa đóng băng. Nếu C: đang đóng băng, mở bảo vệ và khởi động lại trước khi cài";
    }

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
        value.missedScans = missedScans;
        value.clientVersionCode = clientVersionCode;
        value.latestVersion = latestVersion;
        value.updateStatus = updateStatus;
        value.updateMessage = updateMessage;
        value.updateAuthenticated = updateAuthenticated;
        value.runtimeAuthenticated = runtimeAuthenticated;
        value.runtimeStatus = runtimeStatus;
        value.runtimeMessage = runtimeMessage;
        value.lockState = lockState;
        value.mediaVersion = mediaVersion;
        value.mediaPort = mediaPort;
        value.mediaCertSha256 = mediaCertSha256;
        value.lockAuthenticated = lockAuthenticated;
        return value;
    }
}
