package com.lanpower.standalone;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int NAVY = Color.rgb(16, 42, 67);
    private static final int BLUE = Color.rgb(23, 105, 170);
    private static final int GREEN = Color.rgb(32, 145, 90);
    private static final int RED = Color.rgb(183, 45, 45);
    private static final int AMBER = Color.rgb(155, 95, 0);
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, RoomModel> rooms = new LinkedHashMap<>();
    private final Set<String> selected = new LinkedHashSet<>();
    private final List<Button> selectionButtons = new ArrayList<>();
    private SecureKeyStore keys;
    private RoomRepository repository;
    private LanTransport transport;
    private LinearLayout content;
    private TextView title;
    private TextView status;
    private TextView selectionSummary;
    private ProgressBar progress;
    private Button backButton;
    private Button addButton;
    private Button refreshButton;
    private String roomId;
    private String deviceId;
    private String statusMessage = "Cùng mạng LAN • Tự làm mới khoảng 20 giây khi mở app";
    private boolean busy;
    private boolean visible;
    private boolean loadFailed;
    private boolean updatingAll;
    private int openPrompts;
    private CheckBox selectAllBox;
    private volatile boolean destroyed;

    private final Runnable autoRefresh = new Runnable() {
        @Override public void run() {
            if (!visible || destroyed) return;
            if (!busy && openPrompts == 0 && !rooms.isEmpty()) refresh();
            handler.postDelayed(this, 20000);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        keys = new SecureKeyStore(this);
        repository = new RoomRepository(this, keys);
        // The opt-in fixture route is limited to debug builds on Android emulators.
        boolean emulator = Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk_gphone") || Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish");
        transport = new LanTransport(BuildConfig.DEBUG && emulator && getIntent().getBooleanExtra("lanpower_ci", false));
        buildUi();
        try {
            for (RoomModel room : repository.load()) rooms.put(room.id, room);
        } catch (Exception error) {
            loadFailed = true;
            statusMessage = "Không tải được cấu hình: " + readable(error) + ". Dữ liệu cũ vẫn được giữ nguyên.";
        }
        if (state != null) {
            roomId = state.getString("roomId");
            deviceId = state.getString("deviceId");
            ArrayList<String> savedSelection = state.getStringArrayList("selected");
            if (savedSelection != null) selected.addAll(savedSelection);
        }
        render();
    }

    @Override protected void onResume() {
        super.onResume();
        visible = true;
        handler.removeCallbacks(autoRefresh);
        handler.postDelayed(autoRefresh, 1200);
    }

    @Override protected void onPause() {
        visible = false;
        handler.removeCallbacks(autoRefresh);
        super.onPause();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("roomId", roomId);
        state.putString("deviceId", deviceId);
        state.putStringArrayList("selected", new ArrayList<>(selected));
        super.onSaveInstanceState(state);
    }

    @Override protected void onDestroy() {
        destroyed = true;
        handler.removeCallbacksAndMessages(null);
        transport.close();
        executor.shutdownNow();
        super.onDestroy();
    }

    @Override public void onBackPressed() {
        if (deviceId != null || roomId != null) navigateBack();
        else super.onBackPressed();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(244, 246, 248));
        title = text("Các phòng", 22, Color.WHITE);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setPadding(dp(18), dp(17), dp(18), dp(17));
        title.setBackgroundColor(NAVY);
        root.addView(title, matchWrap());

        LinearLayout navigation = row();
        navigation.setPadding(dp(10), dp(8), dp(10), dp(4));
        backButton = button("‹ Quay lại", NAVY, R.id.back);
        backButton.setOnClickListener(view -> navigateBack());
        addButton = button("+ Thêm phòng", BLUE, R.id.add_room);
        addButton.setOnClickListener(view -> addRoom());
        refreshButton = button("Quét LAN", GREEN, R.id.refresh);
        refreshButton.setOnClickListener(view -> refresh());
        navigation.addView(backButton, weighted());
        navigation.addView(addButton, weighted());
        navigation.addView(refreshButton, weighted());
        root.addView(navigation, matchWrap());

        status = text(statusMessage, 12, Color.DKGRAY);
        status.setPadding(dp(14), dp(6), dp(14), dp(7));
        root.addView(status, matchWrap());
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true);
        progress.setVisibility(View.GONE);
        root.addView(progress, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4)));

        ScrollView scroll = new ScrollView(this);
        content = column();
        content.setPadding(dp(12), dp(6), dp(12), dp(24));
        scroll.addView(content, matchWrap());
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
    }

    private void render() {
        content.removeAllViews();
        selectionButtons.clear();
        selectionSummary = null;
        selectAllBox = null;
        RoomModel room = rooms.get(roomId);
        if (room == null) { roomId = null; deviceId = null; }
        ManagedDevice device = room == null ? null : room.devices.get(deviceId);
        if (device == null) deviceId = null;
        title.setText(device != null ? device.hostname : room != null ? room.name : "Các phòng");
        status.setText(statusMessage);
        progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        backButton.setVisibility(room == null ? View.GONE : View.VISIBLE);
        addButton.setVisibility(room == null ? View.VISIBLE : View.GONE);
        backButton.setEnabled(!busy);
        addButton.setEnabled(!busy && !loadFailed);
        refreshButton.setEnabled(!busy && !rooms.isEmpty());
        if (room == null) {
            if (rooms.isEmpty()) {
                content.addView(note("Thêm phòng bằng tên và Key phòng đúng 8 ký tự ASCII hiển thị, không có khoảng trắng. Dùng cùng khóa đó khi cài agent Windows của phòng."));
            }
            for (RoomModel value : rooms.values()) content.addView(roomCard(value));
            content.addView(note("Máy đã lưu nhưng không phản hồi lần quét sẽ hiển thị Offline / chưa phản hồi. Máy đang tắt và chưa từng lưu cần được bật một lần để app nhận MAC."));
        } else if (device != null) {
            renderDetail(room, device);
        } else {
            renderRoom(room);
        }
    }

    private View roomCard(RoomModel room) {
        LinearLayout card = card();
        card.setId(R.id.room_card);
        card.setContentDescription("room:" + room.name);
        TextView name = text(room.name, 20, NAVY);
        name.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        card.addView(name, matchWrap());
        card.addView(text(roomCounts(room), 14, Color.DKGRAY), matchWrap());
        card.addView(note(room.legacy ? "Đã chuyển dữ liệu từ bản cũ • khóa Base64 cũ được giữ an toàn" : "Khóa phòng được mã hóa bằng Android Keystore"));
        if (!room.scanError.isEmpty()) card.addView(text("Chưa xác định hiện tại: " + room.scanError, 13, RED));
        card.setOnClickListener(view -> {
            if (busy) return;
            roomId = room.id;
            deviceId = null;
            selected.clear();
            render();
        });
        card.setOnLongClickListener(view -> {
            if (!busy) confirmRemoveRoom(room);
            return true;
        });
        return card;
    }

    private void renderRoom(RoomModel room) {
        selected.retainAll(room.devices.keySet());
        content.addView(text(roomCounts(room), 16, NAVY), matchWrap());
        if (!room.scanError.isEmpty()) content.addView(note("Lần quét thất bại, trạng thái hiện tại chưa xác định: " + room.scanError));
        CheckBox all = new CheckBox(this);
        selectAllBox = all;
        all.setId(R.id.select_all);
        all.setText("Chọn tất cả");
        all.setEnabled(!busy && !room.devices.isEmpty());
        all.setChecked(!room.devices.isEmpty() && selected.containsAll(room.devices.keySet()));
        all.setOnCheckedChangeListener((view, checked) -> {
            if (updatingAll) return;
            selected.clear();
            if (checked) selected.addAll(room.devices.keySet());
            render();
        });
        content.addView(all, matchWrap());
        selectionSummary = text("Đã chọn " + selected.size() + " máy", 13, NAVY);
        content.addView(selectionSummary, matchWrap());
        addActionButtons(content, room, null);
        if (room.devices.isEmpty()) content.addView(note("Chưa có máy được lưu trong phòng. Bật máy, cài agent với cùng khóa phòng rồi chọn Quét LAN."));
        for (ManagedDevice device : room.devices.values()) content.addView(deviceCard(room, device));
    }

    private View deviceCard(RoomModel room, ManagedDevice device) {
        LinearLayout card = card();
        card.setId(R.id.device_card);
        card.setContentDescription("device:" + device.hostname);
        LinearLayout heading = row();
        CheckBox check = new CheckBox(this);
        check.setId(R.id.device_select);
        check.setContentDescription("Chọn " + device.hostname);
        check.setChecked(selected.contains(device.id));
        check.setEnabled(!busy);
        check.setOnCheckedChangeListener((view, checked) -> {
            if (checked) selected.add(device.id); else selected.remove(device.id);
            updateSelection();
        });
        heading.addView(check);
        TextView name = text(device.hostname, 18, NAVY);
        name.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        heading.addView(name, weighted());
        heading.addView(text(presence(room, device), 12, room.scanError.isEmpty() && device.online ? GREEN : RED));
        card.addView(heading, matchWrap());
        card.addView(text(device.ip + "  •  " + device.mac + "\nUWF C: " + device.uwfDescription(), 13, Color.DKGRAY), matchWrap());
        card.addView(text("Chạm để xem chi tiết và thao tác", 12, BLUE), matchWrap());
        View.OnClickListener open = view -> {
            if (!busy) { deviceId = device.id; render(); }
        };
        card.setOnClickListener(open);
        name.setOnClickListener(open);
        return card;
    }

    private void renderDetail(RoomModel room, ManagedDevice device) {
        LinearLayout info = card();
        info.addView(text("Hostname: " + device.hostname + "\nIP: " + device.ip + "\nMAC: " + device.mac + "\nTrạng thái: " + presence(room, device)
                + "\nPhản hồi gần nhất: " + (device.lastSeen == 0 ? "Chưa biết" : DateFormat.getDateTimeInstance().format(new Date(device.lastSeen)))
                + "\nUWF ổ C: " + device.uwfDescription() + "\nAgent: " + (device.stateAuthenticated ? device.agentVersion : "Bản cũ / chưa xác thực trạng thái"), 15, NAVY), matchWrap());
        content.addView(info);
        if (!device.supportsUwf()) content.addView(note("Chưa xác thực được UWF. Nếu agent mới báo thiếu UWF: chạy client/Prepare-Uwf.ps1 một lần bằng Administrator, khởi động lại rồi quét. Agent cũ cần cập nhật."));
        content.addView(note("Đóng/bỏ đóng băng C: đặt trạng thái cho lần khởi động tiếp theo; app không tự khởi động lại máy. Xem Current/Next sau khi quét rồi tự chọn Khởi động lại khi sẵn sàng."));
        addActionButtons(content, room, device);
    }

    private void addActionButtons(LinearLayout parent, RoomModel room, ManagedDevice oneDevice) {
        String[] actions = {"wake", "shutdown", "restart", "freeze", "unfreeze"};
        int[] ids = {R.id.action_wake, R.id.action_shutdown, R.id.action_restart, R.id.action_freeze, R.id.action_unfreeze};
        int[] colors = {GREEN, RED, AMBER, BLUE, NAVY};
        LinearLayout first = row();
        LinearLayout second = row();
        for (int i = 0; i < actions.length; i++) {
            String action = actions[i];
            Button button = button(actionLabel(action), colors[i], ids[i]);
            button.setEnabled(!busy && (oneDevice == null ? !selected.isEmpty() : "wake".equals(action) || (oneDevice.online && room.scanError.isEmpty())));
            button.setOnClickListener(view -> {
                List<ManagedDevice> targets = oneDevice == null ? room.selected(selected) : Collections.singletonList(oneDevice.copy());
                confirmAction(room, targets, action);
            });
            if (oneDevice == null) selectionButtons.add(button);
            (i < 3 ? first : second).addView(button, weighted());
        }
        parent.addView(first, matchWrap());
        parent.addView(second, matchWrap());
    }

    private void updateSelection() {
        if (selectionSummary != null) selectionSummary.setText("Đã chọn " + selected.size() + " máy");
        for (Button button : selectionButtons) button.setEnabled(!busy && !selected.isEmpty());
        RoomModel room = rooms.get(roomId);
        if (selectAllBox != null && room != null) {
            updatingAll = true;
            selectAllBox.setChecked(!room.devices.isEmpty() && selected.containsAll(room.devices.keySet()));
            updatingAll = false;
        }
    }

    private void confirmAction(RoomModel room, List<ManagedDevice> targets, String action) {
        if (busy || targets.isEmpty()) return;
        String explanation = "wake".equals(action) ? "Magic Packet chỉ gửi yêu cầu bật; cần quét lại để xác nhận."
                : ("freeze".equals(action) || "unfreeze".equals(action)) ? "Thay đổi UWF C: có hiệu lực sau khi khởi động lại. App không tự khởi động lại máy."
                : "Các máy Offline / chưa rõ trạng thái sẽ được bỏ qua. Hãy lưu công việc trước khi tắt hoặc khởi động lại.";
        showPrompt(new AlertDialog.Builder(this).setTitle(actionLabel(action) + " • " + targets.size() + " máy")
                .setMessage("Phòng: " + room.name + "\n" + explanation).setNegativeButton("Hủy", null)
                .setPositiveButton("Thực hiện", (dialog, which) -> runAction(room, targets, action)).create());
    }

    private void runAction(RoomModel room, List<ManagedDevice> targets, String action) {
        if (busy || destroyed) return;
        final String key = room.key;
        final boolean scanFailed = !room.scanError.isEmpty();
        setBusy(true, "Đang " + actionLabel(action) + " cho " + targets.size() + " máy…");
        executor.execute(() -> {
            List<String> results = new ArrayList<>();
            int successes = 0;
            for (int index = 0; index < targets.size(); index++) {
                ManagedDevice device = targets.get(index);
                final int position = index + 1;
                if (destroyed) return;
                postResult(() -> {
                    statusMessage = "(" + position + "/" + targets.size() + ") " + actionLabel(action) + " • " + device.hostname;
                    status.setText(statusMessage);
                });
                try {
                    if (!"wake".equals(action) && (!device.online || scanFailed)) {
                        results.add(device.hostname + ": BỎ QUA — Offline / chưa xác định trạng thái");
                        continue;
                    }
                    if (!"wake".equals(action) && key.isEmpty()) throw new IllegalStateException("Không đọc được khóa mã hóa của phòng");
                    if (("freeze".equals(action) || "unfreeze".equals(action)) && !device.supportsUwf()) {
                        throw new IllegalStateException("Chưa có UWF hoặc agent cũ. Cập nhật agent, chạy client/Prepare-Uwf.ps1 một lần bằng Administrator, khởi động lại rồi quét");
                    }
                    String result = "wake".equals(action) ? transport.wake(device) : transport.command(key, device, action);
                    successes++;
                    results.add(device.hostname + ": OK — " + result);
                } catch (Exception error) {
                    results.add(device.hostname + ": LỖI — " + readable(error));
                }
            }
            final int successCount = successes;
            postResult(() -> {
                setBusy(false, "Hoàn tất " + actionLabel(action) + ": " + successCount + "/" + targets.size() + " máy nhận yêu cầu");
                showPrompt(new AlertDialog.Builder(this).setTitle("Kết quả " + successCount + "/" + targets.size())
                        .setMessage(String.join("\n\n", results)).setPositiveButton("Đóng", (dialog, which) -> refresh()).create());
            });
        });
    }

    private void addRoom() {
        if (busy) return;
        LinearLayout form = column();
        form.setPadding(dp(20), dp(8), dp(20), 0);
        EditText name = new EditText(this);
        name.setId(R.id.room_name_input);
        name.setSingleLine(true);
        name.setHint("Tên phòng");
        form.addView(name, matchWrap());
        EditText password = new EditText(this);
        password.setId(R.id.room_key_input);
        password.setSingleLine(true);
        password.setHint("Key phòng · 8 ký tự");
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        password.setSaveEnabled(false);
        password.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        form.addView(password, matchWrap());
        form.addView(note("Đúng 8 ký tự ASCII hiển thị, gồm cả ký tự đặc biệt; không có khoảng trắng, phân biệt hoa/thường. Dùng cùng key khi cài agent Windows."));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Thêm phòng").setView(form).setNegativeButton("Hủy", null).setPositiveButton("Tạo phòng", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String roomName = name.getText().toString().trim();
            String roomPassword = password.getText().toString().trim();
            if (roomName.isEmpty() || roomName.length() > 60) { name.setError("Tên phòng từ 1 đến 60 ký tự"); return; }
            for (RoomModel room : rooms.values()) if (room.name.equalsIgnoreCase(roomName)) { name.setError("Tên phòng đã tồn tại"); return; }
            if (!roomPassword.matches("[\\x21-\\x7E]{8}")) { password.setError("Đúng 8 ký tự ASCII hiển thị; không có khoảng trắng hoặc ký tự có dấu"); return; }
            dialog.dismiss();
            Map<String, String> existingKeys = new LinkedHashMap<>();
            for (RoomModel existing : rooms.values()) existingKeys.put(existing.key, existing.name);
            setBusy(true, "Đang tạo và mã hóa khóa phòng…");
            executor.execute(() -> {
                try {
                    String id = UUID.randomUUID().toString();
                    String derived = LanProtocol.deriveRoomKey(roomPassword);
                    if (existingKeys.containsKey(derived)) throw new IllegalArgumentException("Key này đã thuộc phòng " + existingKeys.get(derived) + ". Mỗi phòng cần một key riêng");
                    keys.saveRoom(id, derived);
                    RoomModel room = new RoomModel(id, roomName, false, derived);
                    postResult(() -> {
                        rooms.put(room.id, room);
                        try { repository.save(rooms.values()); }
                        catch (Exception error) { rooms.remove(room.id); setBusy(false, "Không tạo được phòng: " + readable(error)); return; }
                        setBusy(false, "Đã tạo phòng " + roomName);
                        refresh();
                    });
                } catch (Exception error) { postResult(() -> setBusy(false, "Không tạo được phòng: " + readable(error))); }
            });
        }));
        showPrompt(dialog);
    }

    private void confirmRemoveRoom(RoomModel room) {
        showPrompt(new AlertDialog.Builder(this).setTitle("Xóa phòng " + room.name + "?")
                .setMessage("Chỉ xóa phòng, khóa mã hóa và danh sách máy đã lưu khỏi app. Các máy Windows vẫn giữ nguyên cấu hình agent. Cần quét lại để lưu máy nếu thêm lại phòng.")
                .setNegativeButton("Hủy", null).setPositiveButton("Xóa phòng", (dialog, which) -> {
                    Map<String, RoomModel> previous = new LinkedHashMap<>(rooms);
                    rooms.remove(room.id);
                    try { repository.save(rooms.values()); }
                    catch (Exception error) { rooms.clear(); rooms.putAll(previous); setBusy(false, "Không xóa được phòng: " + readable(error)); return; }
                    try {
                        keys.deleteRoom(room.id);
                        statusMessage = "Đã xóa phòng " + room.name + " khỏi app";
                    } catch (Exception error) { statusMessage = "Đã xóa danh sách phòng; chưa dọn được khóa: " + readable(error); }
                    render();
                }).create());
    }

    private void showPrompt(AlertDialog dialog) {
        // Do not start a scan behind an in-progress form or power confirmation.
        openPrompts++;
        dialog.setOnDismissListener(ignored -> openPrompts = Math.max(0, openPrompts - 1));
        try { dialog.show(); }
        catch (RuntimeException error) { openPrompts--; throw error; }
    }

    private void refresh() {
        if (busy || destroyed || rooms.isEmpty()) return;
        List<RoomModel> targets = new ArrayList<>();
        RoomModel current = rooms.get(roomId);
        if (current == null) targets.addAll(rooms.values()); else targets.add(current);
        setBusy(true, "Đang quét " + targets.size() + " phòng…");
        executor.execute(() -> {
            Map<String, List<ManagedDevice>> found = new LinkedHashMap<>();
            Map<String, String> failures = new LinkedHashMap<>();
            WifiManager.MulticastLock lock = null;
            try {
                WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                if (wifi != null) { lock = wifi.createMulticastLock("LanPowerRooms"); lock.setReferenceCounted(false); lock.acquire(); }
                for (RoomModel room : targets) {
                    if (destroyed) return;
                    try {
                        if (room.key.isEmpty()) throw new IllegalStateException("Không đọc được khóa mã hóa của phòng");
                        found.put(room.id, transport.discover(room.key));
                    } catch (Exception error) { failures.put(room.id, readable(error)); }
                }
            } catch (Exception error) {
                for (RoomModel room : targets) failures.put(room.id, readable(error));
            } finally { if (lock != null && lock.isHeld()) lock.release(); }
            postResult(() -> {
                long now = System.currentTimeMillis();
                for (RoomModel room : targets) {
                    if (found.containsKey(room.id)) room.applyScan(found.get(room.id), now);
                    else room.scanError = failures.getOrDefault(room.id, "Quét chưa hoàn tất");
                }
                String message = failures.isEmpty() ? "Đã cập nhật " + found.size() + " phòng • " + DateFormat.getTimeInstance().format(new Date(now))
                        : "Đã quét " + found.size() + " phòng, " + failures.size() + " phòng lỗi. Mở phòng để xem chi tiết";
                try { repository.save(rooms.values()); }
                catch (Exception error) { message += " • Không lưu được: " + readable(error); }
                setBusy(false, message);
            });
        });
    }

    private void navigateBack() {
        if (busy) return;
        if (deviceId != null) deviceId = null;
        else { roomId = null; selected.clear(); }
        render();
    }

    private String roomCounts(RoomModel room) {
        if (!room.scanError.isEmpty()) return "Đã lưu " + room.devices.size() + " máy • Hiện tại chưa xác định (lỗi quét)";
        return room.devices.size() + " máy • " + room.onlineCount() + " online • " + (room.devices.size() - room.onlineCount()) + " offline / chưa phản hồi";
    }

    private String presence(RoomModel room, ManagedDevice device) {
        return !room.scanError.isEmpty() ? "Chưa xác định" : device.online ? "Online" : "Offline / chưa phản hồi";
    }

    private static String actionLabel(String action) {
        switch (action) {
            case "wake": return "Bật";
            case "shutdown": return "Tắt";
            case "restart": return "Khởi động lại";
            case "freeze": return "Đóng băng C:";
            case "unfreeze": return "Bỏ đóng băng C:";
            default: throw new IllegalArgumentException("Unsupported action");
        }
    }

    private void setBusy(boolean value, String message) { busy = value; statusMessage = message; render(); }
    private void postResult(Runnable result) { runOnUiThread(() -> { if (!destroyed) result.run(); }); }
    private static String readable(Exception error) { return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage(); }

    private LinearLayout card() {
        LinearLayout value = column();
        value.setPadding(dp(14), dp(12), dp(14), dp(12));
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.WHITE);
        background.setCornerRadius(dp(10));
        background.setStroke(dp(1), Color.rgb(220, 228, 235));
        value.setBackground(background);
        LinearLayout.LayoutParams params = matchWrap();
        params.setMargins(0, dp(5), 0, dp(5));
        value.setLayoutParams(params);
        return value;
    }

    private Button button(String value, int color, int id) {
        Button button = new Button(this);
        button.setId(id);
        button.setText(value);
        button.setTextSize(12);
        button.setTextColor(Color.WHITE);
        button.setAllCaps(false);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(6));
        button.setBackground(background);
        return button;
    }

    private TextView note(String value) { TextView view = text(value, 12, Color.DKGRAY); view.setPadding(0, dp(7), 0, dp(7)); return view; }
    private TextView text(String value, int size, int color) { TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(color); return view; }
    private LinearLayout column() { LinearLayout view = new LinearLayout(this); view.setOrientation(LinearLayout.VERTICAL); return view; }
    private LinearLayout row() { LinearLayout view = new LinearLayout(this); view.setOrientation(LinearLayout.HORIZONTAL); view.setGravity(Gravity.CENTER_VERTICAL); return view; }
    private LinearLayout.LayoutParams matchWrap() { return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); }
    private LinearLayout.LayoutParams weighted() { LinearLayout.LayoutParams value = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1); value.setMargins(dp(2), dp(3), dp(2), dp(3)); return value; }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
