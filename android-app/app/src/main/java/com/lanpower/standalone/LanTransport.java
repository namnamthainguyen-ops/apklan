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
    private final Set<DatagramSocket> activeSockets = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    LanTransport(boolean emulatorFixture) { this.emulatorFixture = emulatorFixture; }

    void close() {
        closed = true;
        for (DatagramSocket socket : activeSockets) socket.close();
    }

    List<ManagedDevice> discover(String key, List<String> unicastTargets) throws Exception {
        return discover(key, unicastTargets, Collections.emptyList(), device -> { });
    }

    List<ManagedDevice> discover(String key, List<String> unicastTargets, List<String> knownTargets, java.util.function.Consumer<ManagedDevice> onFound) throws Exception {
        return discover(key, unicastTargets, knownTargets, onFound, false);
    }

    List<ManagedDevice> discover(String key, List<String> unicastTargets, List<String> knownTargets, java.util.function.Consumer<ManagedDevice> onFound, boolean quick) throws Exception {
        String nonce = UUID.randomUUID().toString().replace("-", "");
        JSONObject request = new JSONObject().put("type", "discover").put("nonce", nonce)
                .put("signature", LanProtocol.hmac(key, "discover\n" + nonce));
        byte[] bytes = request.toString().getBytes(StandardCharsets.UTF_8);
        Map<String, ManagedDevice> found = new LinkedHashMap<>();
        DatagramSocket socket = openSocket();
        try (DatagramSocket managed = socket) {
            socket.setBroadcast(true);
            socket.setReceiveBufferSize(1024 * 1024);
            Set<InetAddress> broadcast = quick ? Collections.emptySet() : addresses();
            java.util.Iterator<String> pending = unicastTargets.iterator();
            boolean sent = false;
            try { if (!quick) { sendToAddresses(socket, bytes, PORT, broadcast); sent = true; } }
            catch (IOException unavailable) { if (!pending.hasNext() || closed) throw unavailable; }
            long start = System.nanoTime();
            long deadline = start + 2_600_000_000L;
            long retryAt = start + 1_200_000_000L;
            boolean retried = knownTargets.isEmpty();
            Set<String> unanswered = new LinkedHashSet<>(knownTargets);
            while (System.nanoTime() < deadline && !closed) {
                if (!retried && !pending.hasNext() && System.nanoTime() >= retryAt) {
                    retried = true;
                    pending = new ArrayList<>(unanswered).iterator();
                }
                if (pending.hasNext()) {
                    Set<InetAddress> batch = new LinkedHashSet<>();
                    for (int i = 0; i < 32 && pending.hasNext(); i++) {
                        String target = pending.next();
                        if (VpnTargets.isPrivateAddress(target)) batch.add(InetAddress.getByName(target));
                    }
                    if (!batch.isEmpty()) {
                        try { sendToAddresses(socket, bytes, PORT, batch); sent = true; }
                        catch (IOException unreachable) { if (closed) throw unreachable; }
                    }
                }
                if (!pending.hasNext() && !sent) throw new IOException("Không gửi được gói dò LAN/VPN");
                socket.setSoTimeout((int) Math.max(1, Math.min(pending.hasNext() ? 20 : 350, (deadline - System.nanoTime()) / 1_000_000L)));
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
                    try {
                        long code = response.optLong("clientVersionCode", -1);
                        String latest = response.optString("latestVersion", "unknown");
                        String updateStatus = response.optString("updateStatus", "unknown");
                        String updateMessage = response.optString("updateMessage", "");
                        if (LanProtocol.verify(key, LanProtocol.updateCanonical(nonce, id, code, latest, updateStatus, updateMessage), response.optString("updateSignature"))) {
                            device.clientVersionCode = code;
                            device.latestVersion = latest;
                            device.updateStatus = updateStatus;
                            device.updateMessage = updateMessage;
                            device.updateAuthenticated = true;
                        }
                    } catch (IllegalArgumentException invalidUpdate) { }
                    try {
                        String lockState = response.optString("lockState");
                        String mediaVersion = response.optString("mediaVersion", "none");
                        int mediaPort = response.optInt("mediaPort", 0);
                        String mediaCert = response.optString("mediaCertSha256");
                        if (LanProtocol.verify(key, LanProtocol.lockStateCanonical(nonce, id, lockState, mediaVersion, mediaPort, mediaCert), response.optString("lockSignature"))) {
                            device.lockState = lockState;
                            device.mediaVersion = mediaVersion;
                            device.mediaPort = mediaPort;
                            device.mediaCertSha256 = mediaCert;
                            device.lockAuthenticated = true;
                        }
                    } catch (IllegalArgumentException invalidLockState) { }

                    try {
                        String runtimeStatus = response.optString("runtimeStatus");
                        String runtimeMessage = response.optString("runtimeMessage");
                        if (LanProtocol.verify(key, LanProtocol.runtimeCanonical(nonce, id, runtimeStatus, runtimeMessage), response.optString("runtimeSignature"))) {
                            device.runtimeAuthenticated = true;
                            device.runtimeStatus = runtimeStatus;
                            device.runtimeMessage = runtimeMessage;
                        }
                    } catch (IllegalArgumentException invalidRuntime) { }
                    unanswered.remove(device.ip);
                    if (found.put(device.listKey(), device) == null) onFound.accept(device);
                    if (quick && unanswered.isEmpty()) break;
                } catch (SocketTimeoutException ignored) { }
                catch (JSONException | IllegalArgumentException malformed) {
                    // Keep listening for other machines after invalid datagrams.
                }
            }
            if (closed) throw new IOException("Đã đóng kết nối");
        } finally { activeSockets.remove(socket); }
        return new ArrayList<>(found.values());
    }

    String wake(ManagedDevice device) throws Exception {
        DatagramSocket socket = openSocket();
        try (DatagramSocket managed = socket) {
            socket.setBroadcast(true);
            sendToAddresses(socket, LanProtocol.magicPacket(device.mac), 9, addresses());
        } finally { activeSockets.remove(socket); }
        return "Đã gửi Magic Packet trong LAN cục bộ; VPN không chuyển broadcast tin cậy. Cần relay đang online trong LAN đích để bật máy từ xa; quét lại để xác nhận";
    }

    String command(String key, ManagedDevice device, String action) throws Exception {
        return command(key, device, action, null);
    }

    String relayWake(String key, ManagedDevice relay, ManagedDevice target) throws Exception {
        return command(key, relay, "wake", LanProtocol.normalizeMac(target.mac));
    }

    String desktop(String key, ManagedDevice device, String action, String value) throws Exception {
        return command(key, device, action, LanProtocol.normalizeMac(device.mac), value);
    }

    private String command(String key, ManagedDevice device, String action, String targetMac) throws Exception {
        return command(key, device, action, targetMac, null);
    }

    private String command(String key, ManagedDevice device, String action, String targetMac, String desktopValue) throws Exception {
        String requestId = UUID.randomUUID().toString();
        String nonce = UUID.randomUUID().toString().replace("-", "");
        long timestamp = Instant.now().getEpochSecond();
        String canonical = "wake".equals(action) ? LanProtocol.wakeCanonical(requestId, device.id, timestamp, nonce, targetMac)
                : LanProtocol.commandCanonical(requestId, device.id, action, timestamp, nonce);
        if (desktopValue != null) canonical += "\n" + targetMac + "\n" + desktopValue;
        JSONObject request = new JSONObject().put("type", "command").put("requestId", requestId).put("deviceId", device.id)
                .put("action", action).put("timestamp", timestamp).put("nonce", nonce)
                .put("signature", LanProtocol.hmac(key, canonical));
        if (targetMac != null) request.put("targetMac", targetMac);
        if (desktopValue != null) request.put("desktopValue", desktopValue);
        byte[] bytes = request.toString().getBytes(StandardCharsets.UTF_8);
        DatagramSocket socket = openSocket();
        try (DatagramSocket managed = socket) {
            socket.connect(InetAddress.getByName(device.ip), PORT);
            DatagramPacket outgoing = new DatagramPacket(bytes, bytes.length, socket.getInetAddress(), PORT);
            socket.send(outgoing);
            long timeout = desktopValue != null ? 12_000_000_000L : ("freeze".equals(action) || "unfreeze".equals(action)) ? 30_000_000_000L
                    : ("lock".equals(action) || "unlock".equals(action)) ? 15_000_000_000L : 3_500_000_000L;
            long deadline = System.nanoTime() + timeout;
            boolean canRetry = desktopValue != null && device.supports("desktopretry");
            long retryAt = System.nanoTime() + 8_500_000_000L;
            if (canRetry) deadline += 8_500_000_000L;
            while (System.nanoTime() < deadline && !closed) {
                long next = canRetry ? retryAt : deadline;
                socket.setSoTimeout((int) Math.max(1, (next - System.nanoTime()) / 1_000_000L));
                DatagramPacket packet = new DatagramPacket(new byte[4097], 4097);
                try { socket.receive(packet); }
                catch (SocketTimeoutException timeoutError) {
                    if (!canRetry) break;
                    canRetry = false;
                    socket.send(outgoing);
                    continue;
                }
                if (packet.getLength() > 4096) continue;
                try {
                    JSONObject response = json(packet);
                    String status = response.optString("status");
                    if (!"ack".equals(response.optString("type")) || !requestId.equals(response.optString("requestId")) ||
                            !LanProtocol.verifyAcknowledgement(key, requestId, status, response.optString("signature"))) continue;
                    String message = response.optString("message");
                    boolean verifiedDetail = LanProtocol.verifyAckDetail(key, requestId, status, message, response.optString("detailSignature"));
                    if (!"accepted".equals(status)) throw new CommandRejectedException(verifiedDetail ? message : "Agent từ chối lệnh (không có chi tiết đã xác thực)");
                    if ("freeze".equals(action) || "unfreeze".equals(action) || "wake".equals(action) || "lock".equals(action) || "unlock".equals(action)) {
                        if (!verifiedDetail) throw new SocketTimeoutException("Chưa xác thực được chi tiết kết quả; cần agent mới và quét lại trạng thái");
                        return message;
                    }
                    return verifiedDetail ? message : "Agent đã nhận lệnh; quét lại để kiểm tra trạng thái";
                } catch (JSONException | IllegalArgumentException malformed) {
                    // Wait for a valid, authenticated ACK within the original deadline.
                }
            }
            throw new SocketTimeoutException(desktopValue != null && !closed
                    ? "Chưa xác nhận kết quả. Máy có thể đã thực hiện lệnh."
                    : "Không nhận được phản hồi agent");
        } finally { activeSockets.remove(socket); }
    }

    static final class CommandRejectedException extends IOException {
        CommandRejectedException(String message) { super(message); }
    }

    private static JSONObject json(DatagramPacket packet) throws JSONException {
        return new JSONObject(new String(packet.getData(), packet.getOffset(), packet.getLength(), StandardCharsets.UTF_8));
    }

    private DatagramSocket openSocket() throws IOException {
        DatagramSocket socket = new DatagramSocket();
        activeSockets.add(socket);
        if (closed) { activeSockets.remove(socket); socket.close(); throw new IOException("Đã đóng kết nối"); }
        return socket;
    }

    private void sendToAddresses(DatagramSocket socket, byte[] bytes, int port, Set<InetAddress> destinations) throws Exception {
        int sent = 0;
        IOException lastError = null;
        for (InetAddress address : destinations) {
            if (closed) throw new IOException("Đã đóng kết nối");
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
