package com.lanpower.standalone;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** One socket at a time; callers serialize work and the Activity closes it on destruction. */
final class LanTransport {
    private static final int PORT = 17840;
    private final boolean emulatorFixture;
    private volatile DatagramSocket activeSocket;
    private volatile boolean closed;

    LanTransport(boolean emulatorFixture) { this.emulatorFixture = emulatorFixture; }

    void close() {
        closed = true;
        DatagramSocket socket = activeSocket;
        if (socket != null) socket.close();
    }

    List<ManagedDevice> discover(String key) throws Exception {
        String nonce = UUID.randomUUID().toString().replace("-", "");
        JSONObject request = new JSONObject().put("type", "discover").put("nonce", nonce)
                .put("signature", LanProtocol.hmac(key, "discover\n" + nonce));
        byte[] bytes = request.toString().getBytes(StandardCharsets.UTF_8);
        Map<String, ManagedDevice> found = new LinkedHashMap<>();
        try (DatagramSocket socket = openSocket()) {
            socket.setBroadcast(true);
            sendToAddresses(socket, bytes, PORT);
            long deadline = System.nanoTime() + 2_600_000_000L;
            while (System.nanoTime() < deadline && !closed) {
                socket.setSoTimeout((int) Math.max(1, Math.min(350, (deadline - System.nanoTime()) / 1_000_000L)));
                try {
                    DatagramPacket packet = new DatagramPacket(new byte[8193], 8193);
                    socket.receive(packet);
                    if (packet.getLength() > 8192 || packet.getPort() != PORT || !(packet.getAddress() instanceof Inet4Address)) continue;
                    JSONObject response = json(packet);
                    if (!"discovery".equals(response.optString("type")) || !nonce.equals(response.optString("requestNonce"))) continue;
                    String id = response.optString("deviceId");
                    String hostname = response.optString("hostname");
                    String mac = response.optString("mac");
                    if (!LanProtocol.verify(key, LanProtocol.discoveryCanonical(nonce, id, hostname, mac), response.optString("signature"))) continue;
                    ManagedDevice device = new ManagedDevice(id, hostname, packet.getAddress().getHostAddress(), mac);
                    device.online = true;
                    String version = response.optString("agentVersion");
                    String uwf = response.optString("uwf");
                    String capabilities = response.optString("capabilities");
                    try {
                        if (LanProtocol.verify(key, LanProtocol.stateCanonical(nonce, id, version, uwf, capabilities), response.optString("stateSignature"))) {
                            device.agentVersion = version;
                            device.uwf = uwf;
                            device.capabilities = capabilities;
                            device.stateAuthenticated = true;
                        }
                    } catch (IllegalArgumentException invalidState) {
                        // Legacy agents remain usable for power; unsigned state never enables UWF.
                    }
                    found.put(id, device);
                } catch (SocketTimeoutException ignored) { }
                catch (JSONException | IllegalArgumentException malformed) {
                    // Keep listening for other machines after invalid datagrams.
                }
            }
            if (closed) throw new IOException("Đã đóng kết nối");
        } finally { activeSocket = null; }
        return new ArrayList<>(found.values());
    }

    String wake(ManagedDevice device) throws Exception {
        try (DatagramSocket socket = openSocket()) {
            socket.setBroadcast(true);
            sendToAddresses(socket, LanProtocol.magicPacket(device.mac), 9);
        } finally { activeSocket = null; }
        return "Đã gửi Magic Packet; cần quét lại để xác nhận máy bật";
    }

    String command(String key, ManagedDevice device, String action) throws Exception {
        String requestId = UUID.randomUUID().toString();
        String nonce = UUID.randomUUID().toString().replace("-", "");
        long timestamp = Instant.now().getEpochSecond();
        JSONObject request = new JSONObject().put("type", "command").put("requestId", requestId).put("deviceId", device.id)
                .put("action", action).put("timestamp", timestamp).put("nonce", nonce)
                .put("signature", LanProtocol.hmac(key, LanProtocol.commandCanonical(requestId, device.id, action, timestamp, nonce)));
        byte[] bytes = request.toString().getBytes(StandardCharsets.UTF_8);
        try (DatagramSocket socket = openSocket()) {
            socket.connect(InetAddress.getByName(device.ip), PORT);
            socket.send(new DatagramPacket(bytes, bytes.length, socket.getInetAddress(), PORT));
            long timeout = ("freeze".equals(action) || "unfreeze".equals(action)) ? 30_000_000_000L : 3_500_000_000L;
            long deadline = System.nanoTime() + timeout;
            while (System.nanoTime() < deadline && !closed) {
                socket.setSoTimeout((int) Math.max(1, (deadline - System.nanoTime()) / 1_000_000L));
                DatagramPacket packet = new DatagramPacket(new byte[4097], 4097);
                socket.receive(packet);
                if (packet.getLength() > 4096) continue;
                try {
                    JSONObject response = json(packet);
                    String status = response.optString("status");
                    if (!"ack".equals(response.optString("type")) || !requestId.equals(response.optString("requestId")) ||
                            !LanProtocol.verifyAcknowledgement(key, requestId, status, response.optString("signature"))) continue;
                    String message = response.optString("message");
                    boolean verifiedDetail = LanProtocol.verifyAckDetail(key, requestId, status, message, response.optString("detailSignature"));
                    if (!"accepted".equals(status)) throw new IOException(verifiedDetail ? message : "Agent từ chối lệnh (không có chi tiết đã xác thực)");
                    if ("freeze".equals(action) || "unfreeze".equals(action)) {
                        if (!verifiedDetail) throw new IOException("Chưa xác thực được kết quả UWF; cần agent mới và quét lại trạng thái");
                        return message;
                    }
                    return verifiedDetail ? message : "Agent đã nhận lệnh; quét lại để kiểm tra trạng thái";
                } catch (JSONException | IllegalArgumentException malformed) {
                    // Wait for a valid, authenticated ACK within the original deadline.
                }
            }
            throw new SocketTimeoutException("Không nhận được phản hồi agent");
        } finally { activeSocket = null; }
    }

    private static JSONObject json(DatagramPacket packet) throws JSONException {
        return new JSONObject(new String(packet.getData(), packet.getOffset(), packet.getLength(), StandardCharsets.UTF_8));
    }

    private DatagramSocket openSocket() throws IOException {
        DatagramSocket socket = new DatagramSocket();
        activeSocket = socket;
        if (closed) { socket.close(); throw new IOException("Đã đóng kết nối"); }
        return socket;
    }

    private void sendToAddresses(DatagramSocket socket, byte[] bytes, int port) throws Exception {
        int sent = 0;
        IOException lastError = null;
        for (InetAddress address : addresses()) {
            try {
                socket.send(new DatagramPacket(bytes, bytes.length, address, port));
                sent++;
            } catch (IOException error) { lastError = error; }
        }
        if (sent == 0) throw new IOException("Không gửi được gói LAN trên bất kỳ giao diện mạng nào", lastError);
    }

    private Set<InetAddress> addresses() throws Exception {
        Set<InetAddress> result = new LinkedHashSet<>();
        Enumeration<NetworkInterface> networks = NetworkInterface.getNetworkInterfaces();
        if (networks != null) for (NetworkInterface network : Collections.list(networks)) {
            if (!network.isUp() || network.isLoopback()) continue;
            for (InterfaceAddress address : network.getInterfaceAddresses()) if (address.getBroadcast() instanceof Inet4Address) result.add(address.getBroadcast());
        }
        result.add(InetAddress.getByName("255.255.255.255"));
        if (emulatorFixture) result.add(InetAddress.getByName("10.0.2.2"));
        return result;
    }
}
