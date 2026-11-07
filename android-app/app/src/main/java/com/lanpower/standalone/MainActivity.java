package com.lanpower.standalone;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.ClipData;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.Insets;
import android.graphics.drawable.GradientDrawable;
import android.net.wifi.WifiManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.Menu;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

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
    private RoomMediaStorage mediaStorage;
    private final MediaTransfer mediaTransfer = new MediaTransfer();
    private AppUpdater updater;
    private Button updateMenuButton;
    private PopupMenu updateMenu;
    private String updateStatusMessage;
    private boolean updateReady;
    private boolean updatesDisabled;
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
    private boolean showingMedia;
    private String importRoomId;
    private static final int PICK_MEDIA = 301;
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
            updater.check(false);
            handler.postDelayed(this, 20000);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        keys = new SecureKeyStore(this);
        repository = new RoomRepository(this, keys);
        mediaStorage = new RoomMediaStorage(this);
        // The opt-in fixture route is limited to debug builds on Android emulators.
        boolean emulator = Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk_gphone") || Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish");
        boolean ciFixture = BuildConfig.DEBUG && emulator && getIntent().getBooleanExtra("lanpower_ci", false);
        updatesDisabled = BuildConfig.DEBUG && emulator && (ciFixture || getIntent().getBooleanExtra("disable_updates", false));
        transport = new LanTransport(ciFixture);
        buildUi();
        updater = new AppUpdater(this, (message, available) -> postResult(() -> setUpdateStatus(message, available)), updatesDisabled);
        try {
            for (RoomModel room : repository.load()) rooms.put(room.id, room);
        } catch (Exception error) {
            loadFailed = true;
            statusMessage = "Không tải được cấu hình: " + readable(error) + ". Dữ liệu cũ vẫn được giữ nguyên.";
        }
        if (state != null) {
            roomId = state.getString("roomId");
            deviceId = state.getString("deviceId");
            showingMedia = state.getBoolean("showingMedia");
            importRoomId = state.getString("importRoomId");
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
        updater.check(false);
    }

    @Override protected void onPause() {
        visible = false;
        handler.removeCallbacks(autoRefresh);
        super.onPause();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("roomId", roomId);
        state.putString("deviceId", deviceId);
        state.putBoolean("showingMedia", showingMedia);
        state.putString("importRoomId", importRoomId);
        state.putStringArrayList("selected", new ArrayList<>(selected));
        super.onSaveInstanceState(state);
    }

    @Override protected void onDestroy() {
        destroyed = true;
        handler.removeCallbacksAndMessages(null);
        if (updateMenu != null) updateMenu.dismiss();
        transport.close();
        mediaTransfer.close();
        updater.close();
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
        LinearLayout header = row();
        header.setPadding(dp(18), dp(12), dp(18), dp(12));
        header.setBackgroundColor(NAVY);
        title = text("Các phòng", 22, Color.WHITE);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        LinearLayout brand = column();
        brand.setGravity(Gravity.END);
        TextView mark = text(getString(R.string.brand_name), 17, Color.WHITE);
        mark.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        brand.addView(mark);
        brand.addView(text(getString(R.string.app_name), 11, Color.rgb(205, 224, 240)));
        header.addView(brand);
        updateMenuButton = button("⋮", NAVY, R.id.update_menu);
        updateMenuButton.setPadding(0, 0, 0, 0);
        updateMenuButton.setTextSize(26);
        updateMenuButton.setContentDescription("Menu cập nhật");
        updateMenuButton.setOnClickListener(view -> showUpdatesMenu());
        LinearLayout.LayoutParams menuLayout = new LinearLayout.LayoutParams(dp(48), dp(48));
        menuLayout.setMargins(dp(8), 0, 0, 0);
        header.addView(updateMenuButton, menuLayout);
        root.addView(header, matchWrap());

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
        updateStatusMessage = "Room TN20 " + BuildConfig.VERSION_NAME + (updatesDisabled ? " • Kiểm thử: tạm tắt cập nhật" : " • Đang chờ kiểm tra bản mới");
        LinearLayout footer = row();
        footer.setPadding(dp(14), dp(7), dp(14), dp(7));
        footer.setBackgroundColor(Color.WHITE);
        footer.addView(text(getString(R.string.company_name), 11, Color.DKGRAY),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView website = text(getString(R.string.company_website_label), 12, BLUE);
        website.setContentDescription(getString(R.string.company_website_url));
        website.setPadding(dp(10), dp(5), 0, dp(5));
        website.setOnClickListener(view -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.company_website_url))));
            } catch (ActivityNotFoundException error) {
                Toast.makeText(this, R.string.browser_unavailable, Toast.LENGTH_LONG).show();
            }
        });
        footer.addView(website);
        root.addView(footer, matchWrap());
        // Android 15 enforces edge-to-edge for target 35. Reserve the actual
        // system-bar/cutout insets instead of guessing a fixed status-bar height.
        FrameLayout safeArea = new FrameLayout(this);
        safeArea.setBackgroundColor(NAVY);
        safeArea.addView(root, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        else getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(NAVY);
        safeArea.setOnApplyWindowInsetsListener((view, insets) -> {
            int left, top, right, bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                left = bars.left; top = bars.top; right = bars.right; bottom = bars.bottom;
            } else {
                left = insets.getSystemWindowInsetLeft(); top = insets.getSystemWindowInsetTop();
                right = insets.getSystemWindowInsetRight(); bottom = insets.getSystemWindowInsetBottom();
                if (Build.VERSION.SDK_INT >= 28 && insets.getDisplayCutout() != null) {
                    left = Math.max(left, insets.getDisplayCutout().getSafeInsetLeft());
                    top = Math.max(top, insets.getDisplayCutout().getSafeInsetTop());
                    right = Math.max(right, insets.getDisplayCutout().getSafeInsetRight());
                    bottom = Math.max(bottom, insets.getDisplayCutout().getSafeInsetBottom());
                }
            }
            view.setPadding(left, top, right, bottom);
            view.setContentDescription("safe-area:" + left + "," + top + "," + right + "," + bottom);
            return insets;
        });
        setContentView(safeArea);
        safeArea.requestApplyInsets();
    }

    private void render() {
        content.removeAllViews();
        selectionButtons.clear();
        selectionSummary = null;
        selectAllBox = null;
        RoomModel room = rooms.get(roomId);
        if (room == null) { roomId = null; deviceId = null; showingMedia = false; }
        ManagedDevice device = room == null ? null : room.devices.get(deviceId);
        if (device == null) deviceId = null;
        title.setText(showingMedia && room != null ? "Quảng cáo • " + room.name : device != null ? device.hostname : room != null ? room.name : "Các phòng");
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
        } else if (showingMedia) {
            renderMedia(room);
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
            showingMedia = false;
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
        Button network = button("Mạng LAN/VPN", BLUE, R.id.network_settings);
        network.setEnabled(!busy);
        network.setOnClickListener(view -> editNetwork(room));
        content.addView(network, matchWrap());
        Button media = button("Quảng cáo riêng của phòng", BLUE, R.id.room_media);
        media.setEnabled(!busy);
        media.setOnClickListener(view -> { showingMedia = true; deviceId = null; render(); });
        content.addView(media, matchWrap());
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
        card.addView(text(device.ip + "  •  " + device.mac + "\nUWF C: " + device.uwfDescription()
                + "\nMàn hình: " + device.lockDescription() + "\nQuảng cáo: " + mediaStatus(room, device), 13, Color.DKGRAY), matchWrap());
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
                + "\nUWF ổ C: " + device.uwfDescription() + "\nAgent: " + (device.stateAuthenticated ? device.agentVersion : "Bản cũ / chưa xác thực trạng thái")
                + "\nMàn hình: " + device.lockDescription() + "\nQuảng cáo phòng: " + mediaStatus(room, device)
                + "\nCập nhật Windows: " + (device.updateAuthenticated ? "Mã " + device.clientVersionCode + " • Mới nhất " + device.latestVersion + " • " + device.updateStatus + "\n" + device.updateMessage : "Chưa có thông tin đã xác thực"), 15, NAVY), matchWrap());
        content.addView(info);
        if (!device.supportsUwf()) content.addView(note("Chưa xác thực được UWF. Bộ cài client mới tự chuẩn bị UWF: cài hoặc cập nhật client rồi khởi động lại nếu được yêu cầu. Windows Home/Pro không hỗ trợ tính năng này."));
        content.addView(note("Đóng/bỏ đóng băng C: đặt trạng thái cho lần khởi động tiếp theo; app không tự khởi động lại máy. Xem Current/Next sau khi quét rồi tự chọn Khởi động lại khi sẵn sàng."));
        addActionButtons(content, room, device);
    }

    private void addActionButtons(LinearLayout parent, RoomModel room, ManagedDevice oneDevice) {
        String[] actions = {"wake", "shutdown", "restart", "freeze", "unfreeze", "lock", "unlock"};
        int[] ids = {R.id.action_wake, R.id.action_shutdown, R.id.action_restart, R.id.action_freeze, R.id.action_unfreeze, R.id.action_lock, R.id.action_unlock};
        int[] colors = {GREEN, RED, AMBER, BLUE, NAVY, RED, GREEN};
        LinearLayout first = row();
        LinearLayout second = row();
        LinearLayout third = row();
        for (int i = 0; i < actions.length; i++) {
            String action = actions[i];
            Button button = button(actionLabel(action), colors[i], ids[i]);
            button.setEnabled(!busy && (oneDevice == null ? !selected.isEmpty() : "wake".equals(action) || (oneDevice.online && room.scanError.isEmpty())));
            button.setOnClickListener(view -> {
                List<ManagedDevice> targets = oneDevice == null ? room.selected(selected) : Collections.singletonList(oneDevice.copy());
                confirmAction(room, targets, action);
            });
            if (oneDevice == null) selectionButtons.add(button);
            (i < 3 ? first : i < 5 ? second : third).addView(button, weighted());
        }
        parent.addView(first, matchWrap());
        parent.addView(second, matchWrap());
        parent.addView(third, matchWrap());
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
        String explanation = "wake".equals(action) ? "Magic Packet chỉ gửi yêu cầu bật; cần quét lại để xác nhận. Qua VPN cần ít nhất một máy relay đang online trong LAN đích hoặc router hỗ trợ WOL."
                : ("freeze".equals(action) || "unfreeze".equals(action)) ? "Thay đổi UWF C: có hiệu lực sau khi khởi động lại. App không tự khởi động lại máy."
                : ("lock".equals(action) || "unlock".equals(action)) ? "Khóa/mở phiên sử dụng của phòng máy. Khi khóa, màn hình người dùng hiển thị quảng cáo đã gửi. Đây không phải khóa đăng nhập Windows. App sẽ xác nhận trạng thái thực sau lệnh."
                : "Các máy Offline / chưa rõ trạng thái sẽ được bỏ qua. Hãy lưu công việc trước khi tắt hoặc khởi động lại.";
        showPrompt(new AlertDialog.Builder(this).setTitle(actionLabel(action) + " • " + targets.size() + " máy")
                .setMessage("Phòng: " + room.name + "\n" + explanation).setNegativeButton("Hủy", null)
                .setPositiveButton("Thực hiện", (dialog, which) -> runAction(room, targets, action)).create());
    }

    private void runAction(RoomModel room, List<ManagedDevice> targets, String action) {
        if (busy || destroyed) return;
        final String key = room.key;
        final boolean scanFailed = !room.scanError.isEmpty();
        final boolean vpnRoom = !room.networkTargets.trim().isEmpty();
        ManagedDevice eligibleRelay = null;
        for (ManagedDevice candidate : room.devices.values()) if (candidate.online && !scanFailed && candidate.supports("wolrelay")) { eligibleRelay = candidate.copy(); break; }
        final ManagedDevice relay = eligibleRelay;
        final boolean lockAction = "lock".equals(action) || "unlock".equals(action);
        final List<String> confirmationTargets;
        try { confirmationTargets = lockAction ? scanTargets(room) : Collections.emptyList(); }
        catch (Exception error) { statusMessage = "Không xác nhận được màn hình: " + readable(error); render(); return; }
        setBusy(true, "Đang " + actionLabel(action) + " cho " + targets.size() + " máy…");
        executor.execute(() -> {
            List<String> results = new ArrayList<>();
            int successes = 0;
            Map<String, ManagedDevice> waitingLock = new LinkedHashMap<>();
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
                        throw new IllegalStateException("UWF chưa sẵn sàng hoặc agent cũ. Cài lại client mới để tự chuẩn bị UWF, khởi động lại nếu được yêu cầu rồi quét");
                    }
                    if (lockAction && (!device.lockAuthenticated || !device.supports("sessionlock"))) throw new IllegalStateException("Cần cập nhật client có màn hình khóa/quảng cáo");
                    String result;
                    if ("wake".equals(action)) {
                        String localResult = null;
                        Exception localError = null;
                        try { localResult = transport.wake(device); } catch (Exception error) { localError = error; }
                        if (vpnRoom && relay != null && !key.isEmpty()) {
                            result = "Relay " + relay.hostname + ": " + transport.relayWake(key, relay, device) + " • Cần quét lại để xác nhận máy đã bật";
                        } else {
                            if (localError != null) throw localError;
                            result = localResult;
                        }
                    } else result = transport.command(key, device, action);
                    if (lockAction) waitingLock.put(device.id, device);
                    else { successes++; results.add(device.hostname + ": OK — " + result); }
                } catch (Exception error) {
                    results.add(device.hostname + ": LỖI — " + (lockAction ? roomFeatureError(error) : readable(error)));
                }
            }
            if (!waitingLock.isEmpty()) {
                try {
                    List<ManagedDevice> confirmed = transport.discover(key, confirmationTargets);
                    Map<String, ManagedDevice> byId = new LinkedHashMap<>();
                    for (ManagedDevice value : confirmed) byId.put(value.id, value);
                    String expected = "lock".equals(action) ? "locked" : "unlocked";
                    for (ManagedDevice requested : waitingLock.values()) {
                        ManagedDevice actual = byId.get(requested.id);
                        if (actual != null && actual.lockAuthenticated && expected.equals(actual.lockState)) {
                            successes++;
                            results.add(requested.hostname + ": OK — " + actual.lockDescription());
                        } else results.add(requested.hostname + ": CHƯA XÁC NHẬN — " + (actual == null ? "Máy chưa phản hồi" : actual.lockDescription()));
                    }
                    postResult(() -> { room.applyScan(confirmed, System.currentTimeMillis()); try { repository.save(rooms.values()); } catch (Exception ignored) { } });
                } catch (Exception error) {
                    for (ManagedDevice requested : waitingLock.values()) results.add(requested.hostname + ": CHƯA XÁC NHẬN — " + readable(error));
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
                        mediaStorage.cleanUnused(room.id, new RoomPlaylist());
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
                        found.put(room.id, transport.discover(room.key, scanTargets(room)));
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
        if (showingMedia) showingMedia = false;
        else if (deviceId != null) deviceId = null;
        else { roomId = null; selected.clear(); }
        render();
    }

    private static List<String> scanTargets(RoomModel room) {
        Set<String> unicast = new LinkedHashSet<>(VpnTargets.expand(room.networkTargets));
        for (ManagedDevice saved : room.devices.values()) if (VpnTargets.isPrivateAddress(saved.ip)) unicast.add(saved.ip);
        if (unicast.size() > 1024) throw new IllegalArgumentException("Đích VPN và IP đã lưu vượt 1024 địa chỉ/phòng. Thu hẹp CIDR trong Mạng LAN/VPN");
        return new ArrayList<>(unicast);
    }

    private String mediaStatus(RoomModel room, ManagedDevice device) {
        if ("none".equals(room.playlist.version)) return "Phòng chưa chọn nội dung";
        if (!device.online || !room.scanError.isEmpty()) return "Chờ gửi khi máy online; chọn Gửi lại cho phòng";
        if (!device.lockAuthenticated) return "Chưa xác thực / cần cập nhật client";
        return room.playlist.version.equals(device.mediaVersion) ? "Đã có nội dung mới nhất của phòng" : "Chưa có bản mới của phòng; cần gửi lại";
    }

    private void renderMedia(RoomModel room) {
        content.addView(note("Nội dung chỉ dành cho phòng “" + room.name + "”. Thứ tự từ trên xuống; ảnh mặc định 10 giây, video phát đến hết rồi chuyển mục. Tối đa 20 tệp, 128 MiB mỗi tệp và 256 MiB mỗi phòng."));
        LinearLayout actions = row();
        Button add = button("+ Chọn ảnh / video", BLUE, R.id.media_add);
        add.setEnabled(!busy && room.playlist.files.size() < RoomPlaylist.MAX_FILES);
        add.setOnClickListener(view -> pickMedia(room));
        Button send = button("Gửi lại cho cả phòng", GREEN, R.id.media_send);
        send.setEnabled(!busy && !"none".equals(room.playlist.version) && !room.devices.isEmpty());
        send.setOnClickListener(view -> confirmMediaSend(room));
        actions.addView(add, weighted());
        actions.addView(send, weighted());
        content.addView(actions, matchWrap());
        content.addView(text("Đã chọn " + room.playlist.files.size() + " tệp • " + (room.playlist.totalBytes() / (1024 * 1024)) + " MiB", 14, NAVY));
        for (int index = 0; index < room.playlist.files.size(); index++) {
            int position = index;
            MediaItem item = room.playlist.files.get(index);
            LinearLayout entry = card();
            entry.setContentDescription("media:" + item.name);
            entry.addView(text((index + 1) + ". " + item.name, 15, NAVY), matchWrap());
            entry.addView(note(("video/mp4".equals(item.type) ? "Video MP4 • phát đến hết" : "Ảnh • " + item.durationSeconds + " giây") + " • " + (item.length / 1024) + " KiB"));
            LinearLayout controls = row();
            Button up = button("Lên", NAVY, View.NO_ID);
            up.setContentDescription("Lên " + item.name);
            up.setEnabled(!busy && index > 0);
            up.setOnClickListener(view -> { RoomPlaylist next = room.playlist.copy(); Collections.swap(next.files, position, position - 1); savePlaylist(room, next); });
            Button down = button("Xuống", NAVY, View.NO_ID);
            down.setContentDescription("Xuống " + item.name);
            down.setEnabled(!busy && index < room.playlist.files.size() - 1);
            down.setOnClickListener(view -> { RoomPlaylist next = room.playlist.copy(); Collections.swap(next.files, position, position + 1); savePlaylist(room, next); });
            Button duration = button("Thời lượng", BLUE, View.NO_ID);
            duration.setEnabled(!busy && !"video/mp4".equals(item.type));
            duration.setOnClickListener(view -> editDuration(room, position));
            Button remove = button("Xóa", RED, View.NO_ID);
            remove.setEnabled(!busy);
            remove.setOnClickListener(view -> { RoomPlaylist next = room.playlist.copy(); next.files.remove(position); savePlaylist(room, next); });
            controls.addView(up, weighted()); controls.addView(down, weighted()); controls.addView(duration, weighted()); controls.addView(remove, weighted());
            entry.addView(controls, matchWrap());
            content.addView(entry);
        }
        if (room.playlist.files.isEmpty()) content.addView(note("Chưa chọn ảnh/video. Nội dung phòng khác không được dùng thay cho phòng này."));
        content.addView(note("Gửi cập nhật chỉ tới máy online trong phòng này. Máy đang tắt/chưa phản hồi vẫn giữ nội dung cũ và hiển thị chờ gửi. Khi máy online, chọn Gửi lại cho cả phòng. Nội dung được lưu riêng trên máy con; giữ nguyên trạng thái đóng băng C:."));
        for (ManagedDevice device : room.devices.values()) content.addView(text(device.hostname + ": " + mediaStatus(room, device), 13, Color.DKGRAY), matchWrap());
    }

    private void savePlaylist(RoomModel room, RoomPlaylist next) {
        if (busy) return;
        RoomPlaylist previous = room.playlist;
        try {
            next.changed();
            next.validate();
            room.playlist = next;
            repository.save(rooms.values());
            mediaStorage.cleanUnused(room.id, next);
            statusMessage = "Đã lưu thứ tự nội dung riêng của phòng " + room.name + ". Chọn Gửi lại để cập nhật máy con";
        } catch (Exception error) { room.playlist = previous; statusMessage = "Chưa lưu được quảng cáo: " + readable(error); }
        render();
    }

    private void editDuration(RoomModel room, int position) {
        EditText seconds = new EditText(this);
        seconds.setInputType(InputType.TYPE_CLASS_NUMBER);
        seconds.setText(String.valueOf(room.playlist.files.get(position).durationSeconds));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Thời lượng ảnh (1–600 giây)").setView(seconds)
                .setNegativeButton("Hủy", null).setPositiveButton("Lưu", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            try {
                int value = Integer.parseInt(seconds.getText().toString());
                if (value < 1 || value > 600) throw new IllegalArgumentException();
                RoomPlaylist next = room.playlist.copy(); next.files.get(position).durationSeconds = value;
                dialog.dismiss(); savePlaylist(room, next);
            } catch (Exception error) { seconds.setError("Nhập từ 1 đến 600 giây"); }
        }));
        showPrompt(dialog);
    }

    private void pickMedia(RoomModel room) {
        if (busy) return;
        importRoomId = room.id;
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE)
                .putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/jpeg", "image/png", "video/mp4"}).putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try { startActivityForResult(pick, PICK_MEDIA); }
        catch (ActivityNotFoundException error) { importRoomId = null; statusMessage = "Máy chưa có trình chọn tệp phù hợp"; render(); }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_MEDIA) return;
        String targetRoomId = importRoomId;
        importRoomId = null;
        if (resultCode != RESULT_OK || data == null) return;
        RoomModel target = rooms.get(targetRoomId);
        if (target == null || busy) { statusMessage = "Phòng không còn sẵn sàng; hãy chọn lại nội dung"; render(); return; }
        List<Uri> sources = new ArrayList<>();
        if (data.getClipData() != null) for (int i = 0; i < data.getClipData().getItemCount(); i++) sources.add(data.getClipData().getItemAt(i).getUri());
        else if (data.getData() != null) sources.add(data.getData());
        final RoomPlaylist snapshot = target.playlist.copy();
        setBusy(true, "Đang sao chép nội dung riêng cho phòng " + target.name + "…");
        executor.execute(() -> {
            try {
                RoomPlaylist imported = mediaStorage.importFiles(target.id, snapshot, sources);
                postResult(() -> {
                    target.playlist = imported;
                    try {
                        repository.save(rooms.values()); mediaStorage.cleanUnused(target.id, imported);
                        setBusy(false, "Đã chọn nội dung cho " + target.name + ". Chọn Gửi lại để cập nhật máy con");
                    } catch (Exception error) { target.playlist = snapshot; mediaStorage.cleanUnused(target.id, snapshot); setBusy(false, "Không lưu được nội dung: " + readable(error)); }
                });
            } catch (Exception error) { mediaStorage.cleanUnused(target.id, snapshot); postResult(() -> setBusy(false, "Không thêm được nội dung: " + readable(error))); }
        });
    }

    private void confirmMediaSend(RoomModel room) {
        if (busy) return;
        showPrompt(new AlertDialog.Builder(this).setTitle("Cập nhật quảng cáo • " + room.name)
                .setMessage("Gửi danh sách đang chọn tới tất cả máy online của riêng phòng “" + room.name + "”. Máy offline sẽ chờ gửi lại; nội dung các phòng khác giữ nguyên.")
                .setNegativeButton("Hủy", null).setPositiveButton("Gửi cho phòng", (dialog, which) -> sendRoomMedia(room)).create());
    }

    private void sendRoomMedia(RoomModel room) {
        if (busy || destroyed) return;
        final String targetRoomId = room.id;
        final String key = room.key;
        final RoomPlaylist playlist = room.playlist.copy();
        final List<ManagedDevice> targets = new ArrayList<>();
        for (ManagedDevice device : room.devices.values()) targets.add(device.copy());
        final boolean scanFailed = !room.scanError.isEmpty();
        setBusy(true, "Đang gửi quảng cáo riêng cho phòng " + room.name + "…");
        executor.execute(() -> {
            List<String> results = new ArrayList<>();
            int successes = 0;
            for (int i = 0; i < targets.size(); i++) {
                ManagedDevice target = targets.get(i);
                if (destroyed) return;
                if (!target.online || scanFailed) { results.add(target.hostname + ": CHỜ GỬI — Máy offline/chưa phản hồi; chọn gửi lại khi online"); continue; }
                final int position = i + 1;
                try {
                    String result = mediaTransfer.send(targetRoomId, key, target, playlist, mediaStorage, (done, total) -> {
                        long percent = total == 0 ? 100 : done * 100 / total;
                        postResult(() -> status.setText("(" + position + "/" + targets.size() + ") " + target.hostname + " • " + percent + "%"));
                    });
                    successes++;
                    results.add(target.hostname + ": OK — " + result);
                } catch (Exception error) { results.add(target.hostname + ": LỖI — " + roomFeatureError(error)); }
            }
            final int successCount = successes;
            postResult(() -> {
                setBusy(false, "Đã cập nhật " + successCount + "/" + targets.size() + " máy của phòng " + room.name + ". Quét lại để xác nhận phiên bản");
                showPrompt(new AlertDialog.Builder(this).setTitle("Quảng cáo • " + room.name).setMessage(String.join("\n\n", results))
                        .setPositiveButton("Đóng", (dialog, which) -> refresh()).create());
            });
        });
    }

    private void editNetwork(RoomModel room) {
        if (busy) return;
        LinearLayout form = column();
        form.setPadding(dp(20), dp(8), dp(20), 0);
        EditText targets = new EditText(this);
        targets.setId(R.id.network_targets_input);
        targets.setMinLines(3);
        targets.setHint("100.64.0.10\n192.168.10.0/24");
        targets.setText(room.networkTargets);
        targets.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        form.addView(targets, matchWrap());
        form.addView(note("Để trống để chỉ quét LAN và IP máy đã lưu. Thêm IPv4 hoặc CIDR riêng/CGNAT qua VPN, phân cách bằng dòng, dấu phẩy hoặc khoảng trắng. CIDR /22–/32, tối đa 1024 đích/phòng. Điện thoại phải kết nối VPN và agent cho phép mạng VPN trong firewall."));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Mạng LAN/VPN • " + room.name).setView(form)
                .setNegativeButton("Hủy", null).setPositiveButton("Lưu mạng", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String value = targets.getText().toString().trim();
            try {
                VpnTargets.expand(value);
                String previous = room.networkTargets;
                room.networkTargets = value;
                try { repository.save(rooms.values()); }
                catch (Exception error) { room.networkTargets = previous; throw error; }
                dialog.dismiss();
                statusMessage = "Đã lưu mạng LAN/VPN của " + room.name;
                render();
                refresh();
            } catch (Exception error) { targets.setError(readable(error)); }
        }));
        showPrompt(dialog);
    }

    private void setUpdateStatus(String message, boolean available) {
        updateStatusMessage = message;
        updateReady = available;
        updateMenuButton.setText(available ? "⋮ •" : "⋮");
        updateMenuButton.setTextSize(available ? 21 : 26);
        updateMenuButton.setContentDescription(available ? "Menu cập nhật • Có bản mới" : "Menu cập nhật");
        if (updateMenu != null) updateMenu.getMenu().findItem(R.id.update_install).setEnabled(available && !busy);
    }

    private void showUpdatesMenu() {
        if (updateMenu != null || destroyed) return;
        PopupMenu popup = new PopupMenu(this, updateMenuButton, Gravity.END);
        popup.getMenu().add(Menu.NONE, R.id.update_status, 0, "Room TN20 " + BuildConfig.VERSION_NAME).setEnabled(false);
        popup.getMenu().add(Menu.NONE, R.id.update_check, 1, "Kiểm tra cập nhật")
                .setEnabled(!updatesDisabled).setContentDescription("update_check");
        popup.getMenu().add(Menu.NONE, R.id.update_install, 2, "Cài bản mới")
                .setEnabled(updateReady && !busy && !updatesDisabled).setContentDescription("update_install");
        popup.getMenu().add(Menu.NONE, R.id.update_state, 3, "Trạng thái cập nhật").setContentDescription("update_state");
        popup.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.update_check) {
                updater.check(true);
                Toast.makeText(this, "Đang kiểm tra cập nhật. Xem kết quả trong menu → Trạng thái cập nhật.", Toast.LENGTH_LONG).show();
                return true;
            }
            if (item.getItemId() == R.id.update_install) {
                // A menu item is invoked before PopupMenu's dismiss callback.
                // Defer installation until that callback releases the prompt guard.
                handler.post(this::installNewVersion);
                return true;
            }
            if (item.getItemId() == R.id.update_state) {
                showPrompt(new AlertDialog.Builder(this).setTitle("Cập nhật Room TN20")
                        .setMessage(updateStatusMessage).setPositiveButton("Đóng", null).create());
                return true;
            }
            return false;
        });
        popup.setOnDismissListener(ignored -> {
            updateMenu = null;
            openPrompts = Math.max(0, openPrompts - 1);
        });
        updateMenu = popup;
        openPrompts++;
        try { popup.show(); }
        catch (RuntimeException error) { updateMenu = null; openPrompts--; throw error; }
    }

    private void installNewVersion() {
        if (updatesDisabled || busy || openPrompts > 0) return;
        try {
            updater.validateReady();
            if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
                setUpdateStatus("Bật quyền cài ứng dụng từ nguồn này trong Android, quay lại rồi chọn Cài bản mới trong menu góc trên bên phải.", true);
                Toast.makeText(this, updateStatusMessage, Toast.LENGTH_LONG).show();
                startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
                return;
            }
            Intent pendingConfirmation = UpdateInstallReceiver.pendingUserAction;
            if (pendingConfirmation != null) {
                UpdateInstallReceiver.pendingUserAction = null;
                startActivity(pendingConfirmation);
                return;
            }
            Intent install = new Intent(Intent.ACTION_INSTALL_PACKAGE).setData(UpdateApkProvider.APK_URI)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            install.setClipData(ClipData.newRawUri("Room TN20 update", UpdateApkProvider.APK_URI));
            startActivity(install);
            setUpdateStatus("Android đang yêu cầu xác nhận cài đặt. Dữ liệu phòng được giữ khi cập nhật thành công.", true);
        } catch (Exception error) {
            setUpdateStatus("Chưa mở được cài đặt: " + readable(error), updateReady);
            Toast.makeText(this, updateStatusMessage, Toast.LENGTH_LONG).show();
        }
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
            case "lock": return "Khóa màn hình";
            case "unlock": return "Mở màn hình";
            default: throw new IllegalArgumentException("Unsupported action");
        }
    }

    private void setBusy(boolean value, String message) { busy = value; statusMessage = message; render(); }
    private void postResult(Runnable result) { runOnUiThread(() -> { if (!destroyed) result.run(); }); }
    private static String readable(Exception error) { return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage(); }

    private static String roomFeatureError(Exception error) {
        String value = readable(error);
        if (value.contains("LOCK_UI_UNAVAILABLE")) return "Chưa có phiên người dùng để hiển thị. Yêu cầu khóa/mở đã được lưu và sẽ áp dụng khi màn hình hoạt động; hiện chưa xác nhận thành công";
        if (value.contains("LOCK_UI_TIMEOUT")) return "Màn hình chưa xác nhận yêu cầu khóa/mở. Yêu cầu đang chờ; hãy kiểm tra máy và quét lại";
        if (value.contains("MEDIA_STORAGE") || value.contains("MEDIA_ROOT")) return "Máy chưa có nơi lưu quảng cáo bền vững. Cài lại client mới và kiểm tra ổ dữ liệu không đóng băng";
        if (value.contains("MEDIA_TOO_LARGE")) return "Nội dung vượt giới hạn dung lượng của máy";
        if (value.contains("MEDIA_HASH") || value.contains("MEDIA_SIGNATURE")) return "Không xác minh được nội dung gửi tới máy; nội dung cũ được giữ, hãy chọn gửi lại";
        return value;
    }

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
