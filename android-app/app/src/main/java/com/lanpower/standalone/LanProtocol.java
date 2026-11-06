package com.lanpower.standalone;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/** Wire-format operations shared by the app and the plain JVM tests. */
final class LanProtocol {
    private LanProtocol() { }

    static String deriveRoomKey(String value) throws Exception {
        if (value == null || !value.trim().matches("[\\x21-\\x7E]{8}")) {
            throw new IllegalArgumentException("Key phòng phải đúng 8 ký tự ASCII hiển thị, không có khoảng trắng; phân biệt hoa thường");
        }
        PBEKeySpec specification = new PBEKeySpec(value.trim().toCharArray(),
                "LanPower/RoomKey/v1".getBytes(StandardCharsets.UTF_8), 210000, 256);
        try {
            return Base64.getEncoder().encodeToString(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(specification).getEncoded());
        } finally { specification.clearPassword(); }
    }

    static String normalizeKey(String value) {
        byte[] key = Base64.getDecoder().decode(value.trim());
        if (key.length != 32) throw new IllegalArgumentException("Management Key phải có 32 byte");
        return Base64.getEncoder().encodeToString(key);
    }

    static String hmac(String keyBase64, String value) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(Base64.getDecoder().decode(normalizeKey(keyBase64)), "HmacSHA256"));
        return Base64.getEncoder().encodeToString(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
    }

    static boolean verify(String key, String canonical, String signature) throws Exception {
        try {
            return MessageDigest.isEqual(Base64.getDecoder().decode(signature),
                    Base64.getDecoder().decode(hmac(key, canonical)));
        } catch (IllegalArgumentException invalidBase64) {
            return false;
        }
    }

    static String discoveryCanonical(String nonce, String deviceId, String hostname, String mac) {
        requireField(nonce);
        requireField(deviceId);
        requireField(hostname);
        magicPacket(mac); // Reject corrupt identities before they enter the saved-device list.
        return "discovery\n" + nonce + "\n" + deviceId + "\n" + hostname + "\n" + mac;
    }

    static String commandCanonical(String requestId, String deviceId, String action, long timestamp, String nonce) {
        requireField(requestId);
        requireField(deviceId);
        requireField(nonce);
        if (!"shutdown".equals(action) && !"restart".equals(action) && !"freeze".equals(action) && !"unfreeze".equals(action)) {
            throw new IllegalArgumentException("Unsupported action");
        }
        return "command\n" + requestId + "\n" + deviceId + "\n" + action + "\n" + timestamp + "\n" + nonce;
    }

    static boolean verifyAcknowledgement(String key, String requestId, String status, String signature) throws Exception {
        requireField(requestId);
        if (!"accepted".equals(status) && !"rejected".equals(status)) return false;
        return verify(key, "ack\n" + requestId + "\n" + status, signature);
    }

    static String stateCanonical(String nonce, String deviceId, String agentVersion, String uwf, String capabilities) {
        requireField(nonce);
        requireField(deviceId);
        requireField(agentVersion);
        requireField(uwf);
        requireField(capabilities);
        return "state\n" + nonce + "\n" + deviceId + "\n" + agentVersion + "\n" + uwf + "\n" + capabilities;
    }

    static boolean verifyAckDetail(String key, String requestId, String status, String message, String signature) throws Exception {
        requireField(requestId);
        requireField(status);
        if (message == null || message.length() > 2048) return false;
        return verify(key, "ack-detail\n" + requestId + "\n" + status + "\n" + message, signature);
    }

    static String wakeCanonical(String requestId, String relayId, long timestamp, String nonce, String targetMac) {
        requireField(requestId);
        requireField(relayId);
        requireField(nonce);
        magicPacket(targetMac);
        if (!targetMac.matches("([0-9A-F]{2}:){5}[0-9A-F]{2}")) throw new IllegalArgumentException("Relay MAC must use uppercase colon format");
        return "command\n" + requestId + "\n" + relayId + "\nwake\n" + timestamp + "\n" + nonce + "\n" + targetMac;
    }

    static String updateCanonical(String nonce, String deviceId, long clientCode, String latest, String status, String message) {
        requireField(nonce);
        requireField(deviceId);
        requireField(latest);
        requireField(status);
        if (clientCode < 0 || message == null || message.length() > 2048) throw new IllegalArgumentException("Invalid update metadata");
        return "update\n" + nonce + "\n" + deviceId + "\n" + clientCode + "\n" + latest + "\n" + status + "\n" + message;
    }

    static String normalizeMac(String value) {
        magicPacket(value);
        String hex = value.replace(":", "").replace("-", "").toUpperCase(java.util.Locale.ROOT);
        return hex.substring(0, 2) + ":" + hex.substring(2, 4) + ":" + hex.substring(4, 6) + ":" + hex.substring(6, 8) + ":" + hex.substring(8, 10) + ":" + hex.substring(10, 12);
    }

    static byte[] magicPacket(String mac) {
        if (mac == null || !mac.matches("(?i)([0-9a-f]{12}|([0-9a-f]{2}:){5}[0-9a-f]{2}|([0-9a-f]{2}-){5}[0-9a-f]{2})")) {
            throw new IllegalArgumentException("MAC không hợp lệ");
        }
        String hex = mac.replace(":", "").replace("-", "");
        byte[] macBytes = new byte[6];
        for (int i = 0; i < 6; i++) macBytes[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        byte[] packet = new byte[102];
        for (int i = 0; i < 6; i++) packet[i] = (byte) 0xff;
        for (int repeat = 0; repeat < 16; repeat++) System.arraycopy(macBytes, 0, packet, 6 + repeat * 6, 6);
        return packet;
    }

    private static void requireField(String value) {
        if (value == null || value.isEmpty() || value.length() > 256 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("Invalid protocol field");
        }
    }
}
