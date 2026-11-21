package com.lanpower.standalone;

import org.junit.Test;
import org.json.JSONObject;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

public final class LanTransportCommandTest {
    private static final String KEY = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";

    private static JSONObject receive(DatagramSocket socket, DatagramPacket packet) throws Exception {
        socket.receive(packet);
        return new JSONObject(new String(packet.getData(), packet.getOffset(), packet.getLength(), StandardCharsets.UTF_8));
    }
    private static void send(DatagramSocket socket, DatagramPacket packet, JSONObject value) throws Exception {
        byte[] bytes = value.toString().getBytes(StandardCharsets.UTF_8);
        socket.send(new DatagramPacket(bytes, bytes.length, packet.getSocketAddress()));
    }
    private static JSONObject ack(JSONObject request, String status, String message, boolean validDetail) throws Exception {
        String id = request.getString("requestId");
        return new JSONObject().put("type", "ack").put("requestId", id).put("status", status).put("message", message)
                .put("signature", LanProtocol.hmac(KEY, "ack\n" + id + "\n" + status))
                .put("detailSignature", validDetail ? LanProtocol.hmac(KEY, "ack-detail\n" + id + "\n" + status + "\n" + message) : "forged");
    }

    @Test public void lostAckRetriesExactRequestAndOnlySignedReceiptUpdatesProgress() throws Exception {
        ExecutorService workers = Executors.newSingleThreadExecutor();
        try (DatagramSocket server = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            server.setSoTimeout(6000);
            Future<?> fixture = workers.submit(() -> {
                try {
                    DatagramPacket first = new DatagramPacket(new byte[8192], 8192);
                    JSONObject request = receive(server, first);
                    JSONObject receipt = new JSONObject().put("type", "command-received").put("requestId", request.getString("requestId"));
                    send(server, first, receipt.put("signature", "forged"));
                    send(server, first, receipt.put("signature", LanProtocol.hmac(KEY, LanProtocol.receiptCanonical(request.getString("requestId"), "id", "update-client"))));
                    DatagramPacket second = new DatagramPacket(new byte[8192], 8192);
                    JSONObject retry = receive(server, second);
                    assertEquals(request.toString(), retry.toString());
                    send(server, second, ack(request, "accepted", "Update task started", true));
                } catch (Exception e) { throw new RuntimeException(e); }
            });
            LanTransport transport = new LanTransport(false, server.getLocalPort());
            ManagedDevice device = new ManagedDevice("id", "pc", "127.0.0.1", "00:11:22:33:44:55");
            device.stateAuthenticated = true;
            device.capabilities = "commandretry";
            List<String> progress = new ArrayList<>();
            try { assertEquals("Update task started", transport.commandWithProgress(KEY, device, "update-client", progress::add)); }
            finally { transport.close(); }
            fixture.get(6, TimeUnit.SECONDS);
            assertEquals(2, progress.size());
            assertTrue(progress.get(1).startsWith("Đã nhận"));
        } finally { workers.shutdownNow(); }
    }

    @Test public void onlyVerifiedUnconfirmedDetailsArePendingAndTrueFailuresRemainRejected() throws Exception {
        for (int scenario = 0; scenario < 3; scenario++) {
            final int mode = scenario;
            ExecutorService workers = Executors.newSingleThreadExecutor();
            try (DatagramSocket server = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
                Future<?> fixture = workers.submit(() -> {
                    try {
                        DatagramPacket packet = new DatagramPacket(new byte[8192], 8192);
                        JSONObject request = receive(server, packet);
                        send(server, packet, ack(request, "rejected", mode == 2 ? "LOCK_RUNTIME_UNAVAILABLE" : "LOCK_PLAYER_UNCONFIRMED: saved", mode != 1));
                    } catch (Exception e) { throw new RuntimeException(e); }
                });
                LanTransport transport = new LanTransport(false, server.getLocalPort());
                ManagedDevice device = new ManagedDevice("id", "pc", "127.0.0.1", "00:11:22:33:44:55");
                try {
                    if (mode == 0) assertThrows(LanTransport.ResultPendingException.class, () -> transport.command(KEY, device, "lock"));
                    else assertThrows(LanTransport.CommandRejectedException.class, () -> transport.command(KEY, device, "lock"));
                } finally { transport.close(); }
                fixture.get(3, TimeUnit.SECONDS);
            } finally { workers.shutdownNow(); }
        }
    }

    @Test public void receiptAloneNeverMeansSuccessAndRetriesAreBounded() throws Exception {
        ExecutorService workers = Executors.newSingleThreadExecutor();
        try (DatagramSocket server = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            server.setSoTimeout(6000);
            Future<?> fixture = workers.submit(() -> {
                try {
                    String original = null;
                    for (int i = 0; i < 3; i++) {
                        DatagramPacket packet = new DatagramPacket(new byte[8192], 8192);
                        JSONObject request = receive(server, packet);
                        if (original == null) original = request.toString();
                        else assertEquals(original, request.toString());
                        if (i == 0) send(server, packet, new JSONObject().put("type", "command-received")
                                .put("requestId", request.getString("requestId"))
                                .put("signature", LanProtocol.hmac(KEY, LanProtocol.receiptCanonical(request.getString("requestId"), "id", "shutdown"))));
                    }
                } catch (Exception e) { throw new RuntimeException(e); }
            });
            LanTransport transport = new LanTransport(false, server.getLocalPort());
            ManagedDevice device = new ManagedDevice("id", "pc", "127.0.0.1", "00:11:22:33:44:55");
            device.stateAuthenticated = true;
            device.capabilities = "commandretry";
            try { assertThrows(SocketTimeoutException.class, () -> transport.command(KEY, device, "shutdown")); }
            finally { transport.close(); }
            fixture.get(6, TimeUnit.SECONDS);
        } finally { workers.shutdownNow(); }
    }
}
