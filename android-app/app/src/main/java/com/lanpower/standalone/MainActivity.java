package com.lanpower.standalone;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.text.InputType;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class MainActivity extends Activity {
    private static final int AGENT_PORT = 17840;
    private static final int WOL_PORT = 9;
    private static final int NAVY = Color.rgb(16, 42, 67);
    private static final int BLUE = Color.rgb(23, 105, 170);
    private static final int GREEN = Color.rgb(32, 164, 100);
    private static final int RED = Color.rgb(183, 45, 45);
    private static final int AMBER = Color.rgb(173, 108, 0);
    private static final String PREFS = "lanpower_devices";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Map<String, Device> devices = new LinkedHashMap<>();
    private EditText keyInput;
    private TextView statusText;
    private TextView summaryText;
    private LinearLayout deviceList;
    private ProgressBar progress;
    private SecureKeyStore secureKeyStore;
    private volatile String activeKey = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        secureKeyStore = new SecureKeyStore(this);
        buildUi();
        activeKey = secureKeyStore.load();
        keyInput.setText(activeKey);
        loadSavedDevices();
        renderDevices();
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(244, 246, 248));

        TextView header = label("LanPower Standalone", 21, Color.WHITE);
        header.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(20), dp(18), dp(20), dp(18));
        header.setBackgroundColor(NAVY);
        root.addView(header, matchWrap());

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        controls.setPadding(dp(16), dp(12), dp(16), dp(7));
        keyInput = new EditText(this);
        keyInput.setHint("Management Key");
        keyInput.setSingleLine(true);
        keyInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        controls.addView(keyInput, matchWrap());

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        Button save = actionButton("Lưu khóa", BLUE);
        save.setOnClickListener(view -> saveKey());
        buttons.addView(save, weightedWrap());
        Button scan = actionButton("Quét mạng LAN", GREEN);
        scan.setOnClickListener(view -> scanNetwork());
        LinearLayout.LayoutParams scanParams = weightedWrap();
        scanParams.setMarginStart(dp(8));
        buttons.addView(scan, scanParams);
        controls.addView(buttons, matchWrap());

        statusText = label("App hoạt động trực tiếp, không cần máy chủ.", 13, Color.DKGRAY);
        statusText.setPadding(0, dp(7), 0, 0);
        controls.addView(statusText, matchWrap());
        summaryText = label("Chưa phát hiện máy", 16, NAVY);
        summaryText.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        summaryText.setPadding(0, dp(8), 0, dp(8));
        controls.addView(summaryText, matchWrap());
        progress = new ProgressBar(this);
        progress.setVisibility(View.GONE);
        controls.addView(progress, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4)));
        root.addView(controls, matchWrap());

        ScrollView scroll = new ScrollView(this);
        deviceList = new LinearLayout(this);
        deviceList.setOrientation(LinearLayout.VERTICAL);
        deviceList.setPadding(dp(12), dp(3), dp(12), dp(24));
        scroll.addView(deviceList, matchWrap());
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
    }

    private boolean saveKey() {
        String value = keyInput.getText().toString().trim();
        try {
            byte[] decoded = Base64.decode(value, Base64.DEFAULT);
            if (decoded.length != 32) throw new IllegalArgumentException("Khóa phải có 32 byte");
            secureKeyStore.save(value);
            activeKey = value;
            statusText.setText("Đã lưu Management Key bằng Android Keystore.");
            return true;
        } catch (Exception error) {
            toast("Management Key không hợp lệ: " + error.getMessage());
            return false;
        }
    }

    private void scanNetwork() {
        if (!saveKey()) return;
        for (Device device : devices.values()) device.online = false;
        setBusy(true, "Đang phát hiện agent trong LAN…");
        executor.execute(() -> {
            WifiManager.MulticastLock lock = null;
            try {
                WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                if (wifi != null) {
                    lock = wifi.createMulticastLock("LanPowerDiscovery");
                    lock.setReferenceCounted(false);
                    lock.acquire();
                }
                List<Device> found = discover(activeKey);
                runOnUiThread(() -> {
                    for (Device device : found) devices.put(device.id, device);
                    saveDevices();
                    renderDevices();
                    setBusy(false, "Đã tìm thấy " + found.size() + " máy trong lần quét này.");
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    renderDevices();
                    setBusy(false, "Quét thất bại: " + error.getMessage());
                });
            } finally {
                if (lock != null && lock.isHeld()) lock.release();
            }
        });
    }

    private List<Device> discover(String key) throws Exception {
        String nonce = UUID.randomUUID().toString().replace("-", "");
        JSONObject request = new JSONObject()
                .put("type", "discover")
                .put("nonce", nonce)
                .put("signature", hmac(key, "discover\n" + nonce));
        byte[] requestBytes = request.toString().getBytes(StandardCharsets.UTF_8);
        Map<String, Device> found = new LinkedHashMap<>();

        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setBroadcast(true);
            socket.setSoTimeout(350);
            for (InetAddress broadcast : broadcastAddresses()) {
                socket.send(new DatagramPacket(requestBytes, requestBytes.length, broadcast, AGENT_PORT));
            }
            long deadline = System.currentTimeMillis() + 2600;
            while (System.currentTimeMillis() < deadline) {
                try {
                    byte[] buffer = new byte[8192];
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    socket.receive(packet);
                    String json = new String(packet.getData(), packet.getOffset(), packet.getLength(), StandardCharsets.UTF_8);
                    JSONObject response = new JSONObject(json);
                    if (!"discovery".equals(response.optString("type"))) continue;
                    if (!nonce.equals(response.optString("requestNonce"))) continue;
                    String canonical = "discovery\n" + nonce + "\n" + response.optString("deviceId") + "\n"
                            + response.optString("hostname") + "\n" + response.optString("mac");
                    if (!constantTimeEquals(response.optString("signature"), hmac(key, canonical))) continue;
                    Device device = Device.fromDiscovery(response, packet.getAddress().getHostAddress());
                    device.online = true;
                    found.put(device.id, device);
                } catch (SocketTimeoutException ignored) { }
            }
        }
        return new ArrayList<>(found.values());
    }

    private void wake(Device device) {
        setBusy(true, "Đang gửi Magic Packet tới " + device.hostname + "…");
        executor.execute(() -> {
            try {
                byte[] packet = magicPacket(device.mac);
                try (DatagramSocket socket = new DatagramSocket()) {
                    socket.setBroadcast(true);
                    for (InetAddress broadcast : broadcastAddresses()) {
                        socket.send(new DatagramPacket(packet, packet.length, broadcast, WOL_PORT));
                    }
                }
                runOnUiThread(() -> setBusy(false, "Đã gửi Wake-on-LAN tới " + device.hostname + "."));
            } catch (Exception error) {
                runOnUiThread(() -> setBusy(false, "Wake-on-LAN thất bại: " + error.getMessage()));
            }
        });
    }

    private void confirmCommand(Device device, String action, String label) {
        new AlertDialog.Builder(this)
                .setTitle(label)
                .setMessage(label + " " + device.hostname + "?")
                .setNegativeButton("Hủy", null)
                .setPositiveButton("Thực hiện", (dialog, which) -> sendCommand(device, action, label))
                .show();
    }

    private void sendCommand(Device device, String action, String label) {
        if (!saveKey()) return;
        setBusy(true, "Đang gửi lệnh " + label.toLowerCase() + "…");
        executor.execute(() -> {
            try {
                String requestId = UUID.randomUUID().toString();
                String nonce = UUID.randomUUID().toString().replace("-", "");
                long timestamp = Instant.now().getEpochSecond();
                String canonical = "command\n" + requestId + "\n" + device.id + "\n" + action + "\n" + timestamp + "\n" + nonce;
                JSONObject request = new JSONObject()
                        .put("type", "command")
                        .put("requestId", requestId)
                        .put("deviceId", device.id)
                        .put("action", action)
                        .put("timestamp", timestamp)
                        .put("nonce", nonce)
                        .put("signature", hmac(activeKey, canonical));
                byte[] bytes = request.toString().getBytes(StandardCharsets.UTF_8);
                try (DatagramSocket socket = new DatagramSocket()) {
                    socket.setSoTimeout(3500);
                    InetAddress address = InetAddress.getByName(device.ip);
                    socket.send(new DatagramPacket(bytes, bytes.length, address, AGENT_PORT));
                    byte[] responseBuffer = new byte[4096];
                    DatagramPacket responsePacket = new DatagramPacket(responseBuffer, responseBuffer.length);
                    socket.receive(responsePacket);
                    JSONObject response = new JSONObject(new String(responsePacket.getData(), responsePacket.getOffset(), responsePacket.getLength(), StandardCharsets.UTF_8));
                    String status = response.optString("status");
                    String expected = hmac(activeKey, "ack\n" + requestId + "\n" + status);
                    if (!requestId.equals(response.optString("requestId")) || !constantTimeEquals(response.optString("signature"), expected)) {
                        throw new SecurityException("Phản hồi agent không hợp lệ");
                    }
                    if (!"accepted".equals(status)) throw new SecurityException(response.optString("message", "Agent từ chối lệnh"));
                }
                runOnUiThread(() -> setBusy(false, "Agent đã nhận lệnh " + label.toLowerCase() + "."));
            } catch (Exception error) {
                runOnUiThread(() -> setBusy(false, "Không gửi được lệnh: " + error.getMessage()));
            }
        });
    }

    private Set<InetAddress> broadcastAddresses() throws Exception {
        Set<InetAddress> addresses = new LinkedHashSet<>();
        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
        if (interfaces != null) {
            for (NetworkInterface network : Collections.list(interfaces)) {
                if (!network.isUp() || network.isLoopback()) continue;
                for (InterfaceAddress address : network.getInterfaceAddresses()) {
                    InetAddress broadcast = address.getBroadcast();
                    if (broadcast instanceof Inet4Address) addresses.add(broadcast);
                }
            }
        }
        addresses.add(InetAddress.getByName("255.255.255.255"));
        return addresses;
    }

    private static byte[] magicPacket(String mac) {
        String hex = mac.replaceAll("[^0-9A-Fa-f]", "");
        if (hex.length() != 12) throw new IllegalArgumentException("MAC không hợp lệ");
        byte[] macBytes = new byte[6];
        for (int i = 0; i < 6; i++) macBytes[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        byte[] packet = new byte[102];
        for (int i = 0; i < 6; i++) packet[i] = (byte) 0xff;
        for (int repeat = 0; repeat < 16; repeat++) System.arraycopy(macBytes, 0, packet, 6 + repeat * 6, 6);
        return packet;
    }

    private static String hmac(String keyBase64, String value) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(Base64.decode(keyBase64, Base64.DEFAULT), "HmacSHA256"));
        return Base64.encodeToString(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
    }

    private static boolean constantTimeEquals(String left, String right) {
        byte[] a = left.getBytes(StandardCharsets.US_ASCII);
        byte[] b = right.getBytes(StandardCharsets.US_ASCII);
        if (a.length != b.length) return false;
        int difference = 0;
        for (int i = 0; i < a.length; i++) difference |= a[i] ^ b[i];
        return difference == 0;
    }

    private void renderDevices() {
        deviceList.removeAllViews();
        int online = 0;
        for (Device device : devices.values()) {
            if (device.online) online++;
            deviceList.addView(deviceCard(device));
        }
        summaryText.setText("Đã lưu: " + devices.size() + "  •  Vừa phát hiện: " + online);
        if (devices.isEmpty()) {
            TextView empty = label("Nhập Management Key rồi chọn “Quét mạng LAN”.", 15, Color.DKGRAY);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(10), dp(42), dp(10), dp(42));
            deviceList.addView(empty, matchWrap());
        }
    }

    private View deviceCard(Device device) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.WHITE);
        background.setCornerRadius(dp(10));
        background.setStroke(dp(1), Color.rgb(224, 230, 236));
        card.setBackground(background);
        LinearLayout.LayoutParams cardParams = matchWrap();
        cardParams.setMargins(0, dp(5), 0, dp(5));
        card.setLayoutParams(cardParams);

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = label(device.hostname, 18, NAVY);
        name.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        titleRow.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView state = label(device.online ? "● Đã phát hiện" : "● Chưa phản hồi", 13, device.online ? GREEN : RED);
        titleRow.addView(state);
        card.addView(titleRow, matchWrap());
        TextView details = label(device.ip + "  •  " + device.mac + "\nUWF: " + device.uwf, 13, Color.DKGRAY);
        details.setPadding(0, dp(5), 0, dp(9));
        card.addView(details, matchWrap());

        LinearLayout actions = new LinearLayout(this);
        Button wake = actionButton("Bật", GREEN);
        wake.setOnClickListener(view -> wake(device));
        actions.addView(wake, weightedWrap());
        Button shutdown = actionButton("Tắt", RED);
        shutdown.setEnabled(device.online);
        shutdown.setOnClickListener(view -> confirmCommand(device, "shutdown", "Tắt máy"));
        LinearLayout.LayoutParams middle = weightedWrap();
        middle.setMarginStart(dp(7));
        actions.addView(shutdown, middle);
        Button restart = actionButton("Restart", AMBER);
        restart.setEnabled(device.online);
        restart.setOnClickListener(view -> confirmCommand(device, "restart", "Khởi động lại"));
        LinearLayout.LayoutParams end = weightedWrap();
        end.setMarginStart(dp(7));
        actions.addView(restart, end);
        card.addView(actions, matchWrap());
        return card;
    }

    private void loadSavedDevices() {
        String saved = getSharedPreferences(PREFS, MODE_PRIVATE).getString("devices", "[]");
        try {
            JSONArray array = new JSONArray(saved);
            for (int i = 0; i < array.length(); i++) {
                Device device = Device.fromSaved(array.getJSONObject(i));
                devices.put(device.id, device);
            }
        } catch (Exception ignored) { }
    }

    private void saveDevices() {
        JSONArray array = new JSONArray();
        for (Device device : devices.values()) array.put(device.toJson());
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("devices", array.toString()).apply();
    }

    private void setBusy(boolean busy, String text) {
        progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        statusText.setText(text);
    }

    private Button actionButton(String text, int color) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(Color.WHITE);
        button.setTextSize(13);
        button.setAllCaps(false);
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(7));
        button.setBackground(background);
        return button;
    }

    private TextView label(String text, int size, int color) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams weightedWrap() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private static final class Device {
        String id;
        String hostname;
        String ip;
        String mac;
        String uwf;
        boolean online;

        static Device fromDiscovery(JSONObject value, String ip) {
            Device device = new Device();
            device.id = value.optString("deviceId");
            device.hostname = value.optString("hostname", "Không rõ tên");
            device.ip = ip;
            device.mac = value.optString("mac", "");
            device.uwf = value.optString("uwf", "unknown");
            return device;
        }

        static Device fromSaved(JSONObject value) {
            Device device = new Device();
            device.id = value.optString("id");
            device.hostname = value.optString("hostname", "Không rõ tên");
            device.ip = value.optString("ip", "");
            device.mac = value.optString("mac", "");
            device.uwf = value.optString("uwf", "unknown");
            device.online = false;
            return device;
        }

        JSONObject toJson() {
            JSONObject value = new JSONObject();
            try {
                value.put("id", id).put("hostname", hostname).put("ip", ip).put("mac", mac).put("uwf", uwf);
            } catch (Exception ignored) { }
            return value;
        }
    }
}
