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
    private final ExecutorService commandExecutor = Executors.newSingleThreadExecutor();
    private final ScanFence scanFence = new ScanFence();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, RoomModel> rooms = new LinkedHashMap<>();
    private final Set<String> selected = new LinkedHashSet<>();
    private final List<Button> selectionButtons = new ArrayList<>();
    private SecureKeyStore keys;
    private RoomRepository repository;
    private DeviceHistory history;
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
    private LinearLayout fixedActions;
    private TextView title;
    private TextView status;
    private TextView selectionSummary;
    private ProgressBar progress;
    private Button networkButton, mediaButton;
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
    private final Map<Boolean, CheckBox> groupSelection = new LinkedHashMap<>();
    private volatile boolean destroyed;

    private final Runnable autoRefresh = new Runnable() {
        @Override public void run() {
            if (!visible || destroyed) return;
            if (!busy && openPrompts == 0 && !rooms.isEmpty()) refresh(true, true);
            updater.check(false);
            handler.postDelayed(this, 20000);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        keys = new SecureKeyStore(this);
        repository = new RoomRepository(this, keys);
        history = new DeviceHistory(this);
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
        commandExecutor.shutdownNow();
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
        updateMenuButton.setMinHeight(dp(48));
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
        refreshButton.setOnClickListener(view -> {
            String[] modes = {"Quét nhanh", "Tìm máy mới"};
            showPrompt(new AlertDialog.Builder(this).setTitle("Quét LAN")
                    .setItems(modes, (dialog, index) -> refresh(index == 0)).create());
        });
        navigation.addView(backButton, weighted());
        navigation.addView(addButton, weighted());
        navigation.addView(refreshButton, weighted());
        networkButton = button("Mạng LAN", BLUE, R.id.network_settings);
        mediaButton = button("Quảng cáo", BLUE, R.id.room_media);
        navigation.addView(networkButton, weighted());
        navigation.addView(mediaButton, weighted());
        for (Button item : new Button[]{backButton, refreshButton, networkButton, mediaButton}) compact(item);
        root.addView(navigation, matchWrap());

        status = text(statusMessage, 12, Color.DKGRAY);
        status.setPadding(dp(14), dp(6), dp(14), dp(7));
        root.addView(status, matchWrap());
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true);
        progress.setVisibility(View.GONE);
        root.addView(progress, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4)));
        fixedActions = column();
        fixedActions.setPadding(dp(12), dp(2), dp(12), dp(2));
        root.addView(fixedActions, matchWrap());

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
        groupSelection.clear();
        RoomModel room = rooms.get(roomId);
        if (room == null) { roomId = null; deviceId = null; showingMedia = false; }
        ManagedDevice device = room == null ? null : room.devices.get(deviceId);
        if (device == null) deviceId = null;
        title.setText(showingMedia && room != null ? "Quảng cáo • " + room.name : device != null ? device.hostname : room != null ? room.name : "Các phòng");
        status.setText(statusMessage);
        progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        backButton.setVisibility(room == null ? View.GONE : View.VISIBLE);
        networkButton.setVisibility(room == null ? View.GONE : View.VISIBLE);
        mediaButton.setVisibility(room == null ? View.GONE : View.VISIBLE);
        networkButton.setEnabled(!busy);
        mediaButton.setEnabled(!busy);
        networkButton.setOnClickListener(view -> { if (room != null) editNetwork(room); });
        mediaButton.setOnClickListener(view -> { if (room != null) { showingMedia = true; deviceId = null; render(); } });
        addButton.setVisibility(room == null ? View.VISIBLE : View.GONE);
        backButton.setEnabled(!busy);
        addButton.setEnabled(!busy && !loadFailed);
        refreshButton.setEnabled(!busy && !rooms.isEmpty() && scanningRooms.isEmpty());
        fixedActions.removeAllViews();
        boolean roomList = room != null && device == null && !showingMedia;
        fixedActions.setVisibility(roomList ? View.VISIBLE : View.GONE);
        if (roomList) addActionButtons(fixedActions, room, null);
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

        if (!room.scanError.isEmpty()) content.addView(note("Lần quét thất bại, trạng thái hiện tại chưa xác định: " + room.scanError));
        selectionSummary = text("Đã chọn " + selected.size() + " máy", 13, NAVY);
        content.addView(selectionSummary, matchWrap());

        if (room.devices.isEmpty()) content.addView(note("Chưa có máy được lưu trong phòng. Bật máy, cài agent với cùng khóa phòng rồi chọn Quét LAN."));
        List<ManagedDevice> ordered = new ArrayList<>(room.devices.values());
        ordered.sort(java.util.Comparator.comparing((ManagedDevice device) -> device.hostname, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(device -> device.listKey()));
        for (boolean online : new boolean[]{true, false}) {
            int count = 0;
            for (ManagedDevice device : ordered) if (device.online == online) count++;
            if (count == 0) continue;
            CheckBox group = new CheckBox(this);
            group.setId(online ? R.id.select_all : R.id.select_offline);
            group.setText((online ? "Chọn tất cả Online · " : "Chọn tất cả Offline · ") + count);
            group.setTextColor(online ? GREEN : Color.DKGRAY);
            group.setTextSize(15);
            Set<String> groupIds = new LinkedHashSet<>();
            for (ManagedDevice device : ordered) if (device.online == online) groupIds.add(device.listKey());
            group.setChecked(selected.containsAll(groupIds));
            group.setEnabled(!busy);
            groupSelection.put(online, group);
            group.setOnCheckedChangeListener((view, checked) -> {
                if (updatingAll) return;
                if (checked) selected.addAll(groupIds); else selected.removeAll(groupIds);
                render();
            });
            group.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            group.setPadding(dp(10), dp(7), dp(10), dp(7));
            GradientDrawable background = new GradientDrawable();
            background.setColor(online ? Color.rgb(219, 247, 231) : Color.rgb(235, 238, 243));
            background.setCornerRadius(dp(10));
            group.setBackground(background);
            content.addView(group, matchWrap());
            for (ManagedDevice device : ordered) if (device.online == online) content.addView(deviceCard(room, device));
        }
    }

    private View deviceCard(RoomModel room, ManagedDevice device) {
        LinearLayout card = card();
        card.setId(R.id.device_card);
        card.setContentDescription("device:" + device.hostname);
        LinearLayout heading = row();
        CheckBox check = new CheckBox(this);
        check.setId(R.id.device_select);
        check.setContentDescription("Chọn " + device.hostname);
        check.setChecked(selected.contains(device.listKey()));
        check.setEnabled(!busy);
        check.setOnCheckedChangeListener((view, checked) -> {
            if (checked) selected.add(device.listKey()); else selected.remove(device.listKey());
            updateSelection();
        });
        heading.addView(check);
        LinearLayout identity = column();
        TextView name = text(device.hostname, 17, device.online ? NAVY : Color.DKGRAY);
        name.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        TextView address = text(device.ip, 14, Color.DKGRAY);
        identity.addView(name);
        identity.addView(address);
        heading.addView(identity, weighted());
        heading.addView(text("›", 26, Color.GRAY));
        card.addView(heading, matchWrap());
        View.OnClickListener open = view -> {
            if (!busy) { deviceId = device.listKey(); render(); }
        };
        card.setOnClickListener(open);
        name.setOnClickListener(open);
        identity.setOnClickListener(open);
        address.setOnClickListener(open);
        return card;
    }

    private void detailField(LinearLayout parent, String label, String value) {
        LinearLayout line = row();
        line.setPadding(dp(2), dp(8), dp(2), dp(8));
        TextView caption = text(label, 14, Color.DKGRAY);
        int icon = iconFor(label);
        if (icon != 0) {
            android.graphics.drawable.Drawable drawable = getDrawable(icon).mutate();
            drawable.setTint(NAVY);
            drawable.setBounds(0, 0, dp(20), dp(20));
            caption.setCompoundDrawables(drawable, null, null, null);
            caption.setCompoundDrawablePadding(dp(9));
        }
        line.addView(caption, weighted());
        TextView detail = text(value, 14, NAVY);
        detail.setGravity(Gravity.END);
        detail.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        line.addView(detail, weighted());
        parent.addView(line, matchWrap());
        View divider = new View(this);
        divider.setBackgroundColor(Color.rgb(226, 232, 239));
        parent.addView(divider, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
    }
    private void renderDetail(RoomModel room, ManagedDevice device) {
        LinearLayout info = card();
        detailField(info, "Trạng thái", presence(room, device));
        detailField(info, "Địa chỉ IP", device.ip);
        detailField(info, "Địa chỉ MAC", device.mac);
        detailField(info, "Lần thấy cuối", device.lastSeen == 0 ? "Chưa biết" : DateFormat.getDateTimeInstance().format(new Date(device.lastSeen)));
        detailField(info, "UWF ổ C", device.uwfDescription());
        detailField(info, "Phiên bản client", device.stateAuthenticated ? device.agentVersion : "Chưa xác thực");
        detailField(info, "Cập nhật", device.updateAuthenticated ? device.latestVersion + " • " + device.updateDescription() : "Chưa xác thực");
        detailField(info, "Quảng cáo", mediaStatus(room, device));
        detailField(info, "Màn hình", device.lockDescription());
        content.addView(info);
        Button log = button("Nhật ký máy", NAVY, View.NO_ID);
        log.setOnClickListener(view -> {
            Intent intent = new Intent(this, DeviceHistoryActivity.class);
            intent.putExtra("roomId", room.id);
            intent.putExtra("deviceId", device.listKey());
            intent.putExtra("hostname", device.hostname);
            intent.putExtra("ip", device.ip);
            startActivity(intent);
        });
        content.addView(log, matchWrap());
        if (device.updateAuthenticated && "error".equals(device.updateStatus))
            content.addView(note("Lỗi cập nhật: " + device.updateMessage));
        if (device.supports("pilotupdate")) content.addView(note("Nhóm cập nhật thử"));
        if (device.updateAuthenticated && "blocked-uwf".equals(device.updateStatus)) content.addView(note("Cập nhật đang chờ mở UWF ổ C:"));
        if (device.updateAuthenticated && "pending-reboot".equals(device.updateStatus)) content.addView(note("Cập nhật đang chờ khởi động lại để mở UWF"));
        if (room.duplicateId(device)) content.addView(note("Trùng ID client với máy khác. Kiểm tra ảnh ghost và cấu hình phần cứng."));
        if (room.restartChecks.containsKey(device.listKey())) content.addView(note(room.restartChecks.get(device.listKey()).message));
        String desktopStatus = room.scanError.isEmpty() ? device.desktopBlockReason() : "Cần quét lại máy";
        content.addView(note("Mở website / Đóng ứng dụng: " + (desktopStatus.isEmpty() ? "Phiên Windows sẵn sàng" : desktopStatus)));
        if (!device.supportsUwf()) content.addView(note("Chưa xác thực được UWF. Bộ cài client mới tự chuẩn bị UWF: cài hoặc cập nhật client rồi khởi động lại nếu được yêu cầu. Windows Home/Pro không hỗ trợ tính năng này."));
        content.addView(note("Đóng/bỏ đóng băng C: đặt trạng thái cho lần khởi động tiếp theo; app không tự khởi động lại máy. Xem Current/Next sau khi quét rồi tự chọn reboot khi sẵn sàng."));
        addActionButtons(content, room, device);
    }

    private void addActionButtons(LinearLayout parent, RoomModel room, ManagedDevice oneDevice) {
        if (oneDevice != null) {
            String[] commands = {"wake", "shutdown", "restart", "freeze", "unfreeze", "lock", "unlock", "open-web", "close-apps", "update-client"};
            String[] labels = {"Bật máy", "Tắt máy", "reboot", "Đóng băng", "Bỏ đóng băng", "Khóa", "Mở khóa", "Mở website", "Đóng ứng dụng", "Cập nhật client"};
            int[] actionIds = {R.id.action_wake, R.id.action_shutdown, R.id.action_restart, R.id.action_freeze, R.id.action_unfreeze, R.id.action_lock, R.id.action_unlock, View.NO_ID, View.NO_ID, View.NO_ID};
            for (int offset = 0; offset < commands.length; offset += 4) {
                LinearLayout line = row();
                for (int cell = 0; cell < 4; cell++) {
                    int index = offset + cell;
                    if (index >= commands.length) { line.addView(new View(this), weighted()); continue; }
                    String command = commands[index];
                    Button action = button(labels[index], BLUE, actionIds[index]);
                    action.setTextSize(12);
                    action.setMinHeight(dp(64));
                    action.setTextColor(index == 0 ? GREEN : index == 1 ? RED : BLUE);
                    action.setCompoundDrawableTintList(action.getTextColors());
                    GradientDrawable tile = new GradientDrawable();
                    tile.setColor(Color.WHITE);
                    tile.setCornerRadius(dp(10));
                    tile.setStroke(dp(1), Color.rgb(218, 226, 235));
                    action.setBackground(tile);
                    action.setEnabled(!busy && ("wake".equals(command) || VpnTargets.isPrivateAddress(oneDevice.ip)));
                    action.setOnClickListener(view -> {
                        List<ManagedDevice> target = Collections.singletonList(oneDevice.copy());
                        if ("open-web".equals(command) || "close-apps".equals(command)) desktopPrompt(room, target, "open-web".equals(command));
                        else confirmAction(room, target, command);
                    });
                    line.addView(action, weighted());
                }
                parent.addView(line, matchWrap());
            }
            return;
        }
        String[] actions = {"wake", "shutdown", "restart", "freeze", "unfreeze", "lock", "unlock"};
        int[] ids = {R.id.action_wake, R.id.action_shutdown, R.id.action_restart, R.id.action_freeze, R.id.action_unfreeze, R.id.action_lock, R.id.action_unlock};
        int[] colors = {GREEN, RED, AMBER, BLUE, NAVY, RED, GREEN};
        LinearLayout first = row();

        for (int i = 0; i < 3; i++) {
            String action = actions[i];
            Button button = button(actionLabel(action), colors[i], ids[i]);
            button.setEnabled(!busy && (oneDevice == null ? !selected.isEmpty() : "wake".equals(action) || VpnTargets.isPrivateAddress(oneDevice.ip)));
            button.setOnClickListener(view -> {
                List<ManagedDevice> targets = oneDevice == null ? room.selected(selected) : Collections.singletonList(oneDevice.copy());
                confirmAction(room, targets, action);
            });
            if (oneDevice == null) selectionButtons.add(button);
            compact(button);
            first.addView(button, weighted());
        }

        Button more = button("Thao tác", NAVY, View.NO_ID);
        more.setEnabled(!busy && (oneDevice != null || !selected.isEmpty()));
        if (oneDevice == null) selectionButtons.add(more);
        more.setOnClickListener(view -> {
            List<ManagedDevice> targets = oneDevice == null ? room.selected(selected) : Collections.singletonList(oneDevice.copy());
            String[] labels = {"Đóng băng", "Bỏ đóng băng", "Khóa", "Mở khóa", "Mở website", "Đóng ứng dụng", "Cập nhật client"};
            showPrompt(new AlertDialog.Builder(this).setTitle("Thao tác").setItems(labels, (dialog, index) -> {
                if (index < 4) confirmAction(room, targets, actions[index + 3]);
                else if (index < 6) desktopPrompt(room, targets, index == 4);
                else confirmAction(room, targets, "update-client");
            }).setNegativeButton("Đóng", null).create());
        });
        compact(more);
        first.addView(more, weighted());
        parent.addView(first, matchWrap());
    }

    private void desktopPrompt(RoomModel room, List<ManagedDevice> targets, boolean openWeb) {
        EditText url = new EditText(this);
        url.setSingleLine();
        url.setHint("20.com.vn");
        url.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle((openWeb ? "Mở website" : "Đóng ứng dụng") + " • " + targets.size() + " máy")
                .setNegativeButton("Hủy", null).setPositiveButton("Thực hiện", null);
        if (openWeb) builder.setView(url);
        else builder.setMessage("Đóng tất cả cửa sổ ứng dụng trong phiên người dùng hiện tại. Ứng dụng có thể yêu cầu lưu dữ liệu. Dịch vụ hệ thống và ứng dụng chỉ chạy nền vẫn hoạt động.");
        AlertDialog dialog = builder.create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String input = openWeb ? url.getText().toString().trim() : "";
            final String value = openWeb && !input.contains("://") ? "https://" + input : input;
            if (openWeb) {
                try {
                    java.net.URI parsed = new java.net.URI(value);
                    if (value.length() > 1024 || parsed.getHost() == null || parsed.getRawUserInfo() != null ||
                            (!"https".equalsIgnoreCase(parsed.getScheme()) && !"http".equalsIgnoreCase(parsed.getScheme())))
                        throw new IllegalArgumentException();
                } catch (Exception invalid) {
                    url.setError("Nhập tên miền hợp lệ, ví dụ 20.com.vn");
                    return;
                }
            }
            dialog.dismiss();
            scanFence.commandStarted();
            setBusy(true, "Đang gửi yêu cầu…");
            commandExecutor.execute(() -> {
                Map<String, ManagedDevice> fresh = new LinkedHashMap<>();
                String scanFailure = "";
                try {
                    Set<String> addresses = new LinkedHashSet<>();
                    for (ManagedDevice target : targets) addresses.add(target.ip);
                    for (ManagedDevice device : targets) fresh.put(device.endpointKey(), device.copy());
                } catch (Exception error) { scanFailure = readable(error); }

                final String scanError = scanFailure;
                java.util.concurrent.ExecutorService workers = java.util.concurrent.Executors.newFixedThreadPool(Math.min(4, targets.size()));
                java.util.concurrent.CompletionService<String> tasks = new java.util.concurrent.ExecutorCompletionService<>(workers);
                List<String> results = new ArrayList<>();
                String action = openWeb ? "open-web" : "close-apps";
                try {
                    for (ManagedDevice selectedDevice : targets) tasks.submit(() -> {
                        ManagedDevice target = fresh.get(selectedDevice.endpointKey());
                        String result = target == null
                                ? (scanError.isEmpty() ? "Máy không trả lời khi xác minh lại" : scanError)
                                : target.desktopBlockReason();
                        if (target != null && !result.isEmpty()) {
                            try {
                                for (ManagedDevice actual : transport.probe(room.key, Collections.singletonList(target)))
                                    if (actual.endpointKey().equals(target.endpointKey())) { target = actual; result = actual.desktopBlockReason(); break; }
                            } catch (Exception probeError) { }
                        }
                        if (target != null && result.isEmpty()) {
                            try { result = transport.desktop(room.key, target, action, value); }
                            catch (java.net.SocketTimeoutException error) { result = "CHƯA XÁC NHẬN — Máy có thể đã thực hiện lệnh; không tự gửi lệnh mới"; }
                            catch (LanTransport.CommandRejectedException error) { result = "BỊ TỪ CHỐI — " + readable(error); }
                            catch (Exception error) { result = "LỖI KẾT NỐI — " + readable(error); }
                        }
                        history.record(room.id, selectedDevice.listKey(), openWeb ? "Mở website" : "Đóng ứng dụng", result);
                        return selectedDevice.hostname + " (" + selectedDevice.ip + ") • " + result;
                    });
                    for (int i = 0; i < targets.size(); i++) {
                        String result = tasks.take().get();
                        results.add(result);
                        int count = results.size();
                        postResult(() -> status.setText(count + "/" + targets.size() + " • " + result));
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    results.add("Đã dừng chờ phản hồi");
                } catch (java.util.concurrent.ExecutionException error) {
                    results.add(readable(error));
                } finally { workers.shutdownNow(); }
                postResult(() -> {
                    for (ManagedDevice device : fresh.values())
                        if (room.findEndpoint(device.endpointKey()) != null) {
                            device.lastSeen = System.currentTimeMillis();
                            room.remember(device);
                        }
                    setBusy(false, "Đã xử lý yêu cầu");
                    showPrompt(new AlertDialog.Builder(this).setTitle("Kết quả từng máy")
                            .setMessage(String.join("\n", results)).setPositiveButton("Đóng", null).create());
                });
            });
        }));
        showPrompt(dialog);
    }

    private void updateSelection() {
        if (selectionSummary != null) selectionSummary.setText("Đã chọn " + selected.size() + " máy");
        for (Button button : selectionButtons) button.setEnabled(!busy && !selected.isEmpty());
        RoomModel room = rooms.get(roomId);
        if (room != null) {
            updatingAll = true;
            try {
                for (Map.Entry<Boolean, CheckBox> entry : groupSelection.entrySet()) {
                    Set<String> ids = new LinkedHashSet<>();
                    for (ManagedDevice device : room.devices.values())
                        if (device.online == entry.getKey()) ids.add(device.listKey());
                    entry.getValue().setChecked(!ids.isEmpty() && selected.containsAll(ids));
                }
            } finally { updatingAll = false; }
        }
    }
    private void confirmAction(RoomModel room, List<ManagedDevice> targets, String action) {
        if (busy || targets.isEmpty()) return;
        String explanation = "update-client".equals(action) ? "Client tự kiểm tra GitHub và cài bản mới có chữ ký. Máy đang đóng băng C: cần mở UWF và khởi động lại trước. Chọn vài máy thử trước, rồi cập nhật các máy còn lại. Theo dõi kết quả trong chi tiết máy."
                : "wake".equals(action) ? "Magic Packet chỉ gửi yêu cầu bật; cần quét lại để xác nhận. Qua VPN cần ít nhất một máy relay đang online trong LAN đích hoặc router hỗ trợ WOL."
                : ("freeze".equals(action) || "unfreeze".equals(action)) ? "Thay đổi UWF C: có hiệu lực sau khi khởi động lại. App không tự khởi động lại máy."
                : ("lock".equals(action) || "unlock".equals(action)) ? "Khóa/mở phiên sử dụng của phòng máy. Khi khóa, màn hình người dùng hiển thị quảng cáo đã gửi. Đây không phải khóa đăng nhập Windows. App sẽ xác nhận trạng thái thực sau lệnh."
                : "Gửi trực tiếp tới IP đã lưu, kể cả máy đang được đánh dấu Offline. Không có phản hồi sẽ báo chưa xác nhận. Hãy lưu công việc trước khi tắt hoặc khởi động lại.";
        showPrompt(new AlertDialog.Builder(this).setTitle(actionLabel(action) + " • " + targets.size() + " máy")
                .setMessage("Phòng: " + room.name + "\n" + explanation).setNegativeButton("Hủy", null)
                .setPositiveButton("Thực hiện", (dialog, which) -> runAction(room, targets, action)).create());
    }

    private void runAction(RoomModel room, List<ManagedDevice> targets, String action) {
        if (busy || destroyed) return;
        scanFence.commandStarted();
        final List<ManagedDevice> copies = new ArrayList<>();
        for (ManagedDevice target : targets) copies.add(target.copy());
        ManagedDevice candidate = null;
        for (ManagedDevice device : room.devices.values())
            if (device.online && device.supports("wolrelay")) { candidate = device.copy(); break; }
        final ManagedDevice relay = candidate;
        setBusy(true, "Đang gửi trực tiếp tới " + copies.size() + " máy…");
        commandExecutor.execute(() -> {
            try {
                List<String> results = CommandBatch.run(copies, 32,
                        device -> executeTarget(room, device, action, relay), (device, result, count) -> postResult(() -> {
                            statusMessage = count + "/" + copies.size() + " • " + result;
                            status.setText(statusMessage);
                        }));
                postResult(() -> {
                    setBusy(false, "Đã xử lý " + actionLabel(action) + " • " + copies.size() + " máy");
                    try { repository.save(rooms.values()); } catch (Exception ignored) { }
                    showPrompt(new AlertDialog.Builder(this).setTitle("Kết quả từng máy")
                            .setMessage(String.join("\n\n", results)).setPositiveButton("Đóng", null).create());
                });
            } catch (Exception error) {
                postResult(() -> setBusy(false, "Chưa xác nhận đầy đủ: " + readable(error)));
            }
        });
    }

    private boolean commandRenderScheduled;
    private void scheduleCommandRender() {
        if (commandRenderScheduled) return;
        commandRenderScheduled = true;
        handler.postDelayed(() -> {
            commandRenderScheduled = false;
            if (!destroyed && busy) render();
        }, 80);
    }

    private String executeTarget(RoomModel room, ManagedDevice requested, String action, ManagedDevice relay) {
        String outcome;
        ManagedDevice device = requested;
        boolean lockAction = "lock".equals(action) || "unlock".equals(action);
        try {
            if (destroyed) return requested.hostname + ": Đã dừng chờ";
            if (!"wake".equals(action) && room.key.isEmpty()) throw new IllegalStateException("Không đọc được khóa phòng");
            // Cached online/offline is informational. Always try the selected private IP directly.
            if (!"wake".equals(action) && !VpnTargets.isPrivateAddress(device.ip)) throw new IllegalStateException("IP máy không hợp lệ");
            String message;
            if ("wake".equals(action)) {
                try { message = transport.wake(device); }
                catch (Exception localError) {
                    if (relay == null) throw localError;
                    message = "Không gửi được broadcast cục bộ";
                }
                if (!room.networkTargets.trim().isEmpty() && relay != null)
                    message = "Relay " + relay.hostname + ": " + transport.relayWake(room.key, relay, device);
                outcome = "ĐÃ GỬI TÍN HIỆU BẬT — Chờ máy online • " + message;
            } else {
                message = transport.commandWithProgress(room.key, device, action, stage -> postResult(() -> status.setText(requested.hostname + " • " + stage)));
                if (CommandConfirmation.applyAcknowledgement(action, message, device)) {
                    device.online = true;
                    device.lastSeen = System.currentTimeMillis();
                    ManagedDevice confirmed = device;
                    postResult(() -> { room.remember(confirmed); scheduleCommandRender(); });
                    outcome = "ĐÃ XÁC NHẬN — " + CommandConfirmation.description(action, device);
                } else if (lockAction || "freeze".equals(action) || "unfreeze".equals(action)) {
                    outcome = confirmTarget(room, device, action);
                } else outcome = "ĐÃ NHẬN — Chưa xác nhận hoàn tất • " + message;
                if ("restart".equals(action)) postResult(() -> room.trackRestart(requested, System.currentTimeMillis()));
            }
        } catch (java.net.SocketTimeoutException error) {
            history.record(room.id, requested.listKey(), actionLabel(action), "CHỜ XÁC NHẬN • " + readable(error));
            outcome = lockAction || "freeze".equals(action) || "unfreeze".equals(action)
                    ? confirmTarget(room, requested, action) : "CHƯA XÁC NHẬN — Máy có thể đã thực hiện lệnh";
        } catch (LanTransport.CommandRejectedException error) {
            outcome = "BỊ TỪ CHỐI — " + (lockAction ? roomFeatureError(error) : readable(error));
        } catch (Exception error) {
            outcome = "LỖI KẾT NỐI — " + readable(error);
        }
        String result = requested.hostname + " (" + requested.ip + "): " + outcome;
        history.record(room.id, requested.listKey(), actionLabel(action), result);
        return result;
    }

    private String confirmTarget(RoomModel room, ManagedDevice requested, String action) {
        for (int attempt = 0; attempt < 3 && !destroyed; attempt++) {
            try {
                for (ManagedDevice actual : transport.probe(room.key, Collections.singletonList(requested))) {
                    if (!actual.endpointKey().equals(requested.endpointKey())) continue;
                    postResult(() -> { actual.lastSeen = System.currentTimeMillis(); room.remember(actual); scheduleCommandRender(); });
                    if (CommandConfirmation.matches(action, actual)) return "ĐÃ XÁC NHẬN — " + CommandConfirmation.description(action, actual);
                }
            } catch (Exception ignored) { }
            if (attempt < 2) try { Thread.sleep(75L * (attempt + 1)); } catch (InterruptedException stopped) { Thread.currentThread().interrupt(); break; }
        }
        return "CHƯA XÁC NHẬN — Chưa có bằng chứng máy đạt trạng thái yêu cầu; không tự chạy lệnh mới";
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

    private final Map<String, Set<String>> knownBeforeScan = new LinkedHashMap<>();
    private boolean scanRenderScheduled;
    private final Set<String> scanningRooms = new LinkedHashSet<>();

    private void refresh() { refresh(false); }

    private void refresh(boolean quick) { refresh(quick, false); }

    private void refresh(boolean quick, boolean periodic) {
        if (busy || destroyed || rooms.isEmpty() || !scanningRooms.isEmpty()) return;
        final long scanEpoch = scanFence.capture();
        final Map<String, List<ManagedDevice>> fastTargets = new LinkedHashMap<>();
        List<RoomModel> targets = new ArrayList<>();
        RoomModel current = rooms.get(roomId);
        if (current == null) targets.addAll(rooms.values()); else targets.add(current);
        Map<String, List<String>> knownAddresses = new LinkedHashMap<>();
        Set<String> quickRooms = new LinkedHashSet<>();
        long started = System.currentTimeMillis();
        for (RoomModel room : targets) {
            knownBeforeScan.put(room.id, new LinkedHashSet<>(room.devices.keySet()));
            Set<String> addresses = new LinkedHashSet<>();
            for (ManagedDevice device : room.devices.values()) if (VpnTargets.isPrivateAddress(device.ip)) addresses.add(device.ip);
            knownAddresses.put(room.id, new ArrayList<>(addresses));
            if (quick && !addresses.isEmpty() && (!periodic || started - room.lastFullScan < 300_000)) quickRooms.add(room.id);
            if (quickRooms.contains(room.id)) {
                List<ManagedDevice> snapshots = new ArrayList<>();
                for (ManagedDevice device : room.devices.values()) snapshots.add(device.copy());
                if (!snapshots.isEmpty()) fastTargets.put(room.id, snapshots);
            }
            scanningRooms.add(room.id);
        }
        if (!periodic) setBusy(true, "Đang quét " + targets.size() + " phòng…");
        else refreshButton.setEnabled(false);
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
                        List<String> known = knownAddresses.get(room.id);
                        boolean quickRoom = quickRooms.contains(room.id);
                        List<String> addresses = quickRoom ? known : scanTargets(room);
                        if (fastTargets.containsKey(room.id)) found.put(room.id, transport.probe(room.key, fastTargets.get(room.id)));
                        else found.put(room.id, transport.discover(room.key, addresses, known, device -> postResult(() -> {
                            if (!scanFence.accepts(scanEpoch)) return;
                            device.lastSeen = System.currentTimeMillis();
                            room.remember(device.copy());
                            if (!scanRenderScheduled) {
                                scanRenderScheduled = true;
                                handler.postDelayed(() -> {
                                    scanRenderScheduled = false;
                                    if (!destroyed && scanFence.accepts(scanEpoch) && !scanningRooms.isEmpty() && !busy) render();
                                }, 150);
                            }
                        }), quickRoom));
                        List<ManagedDevice> completed = found.get(room.id);
                        postResult(() -> {
                            scanningRooms.remove(room.id);
                            if (!scanFence.accepts(scanEpoch)) return;
                            room.applyScan(completed, System.currentTimeMillis(), !quickRoom);
                            render();
                        });
                    } catch (Exception error) { failures.put(room.id, readable(error)); }
                }
            } catch (Exception error) {
                for (RoomModel room : targets) failures.put(room.id, readable(error));
            } finally { if (lock != null && lock.isHeld()) lock.release(); }
            postResult(() -> {
                long now = System.currentTimeMillis();
                for (RoomModel room : targets) {
                    scanningRooms.remove(room.id);
                    if (scanFence.accepts(scanEpoch) && !found.containsKey(room.id)) room.scanError = failures.getOrDefault(room.id, "Quét chưa hoàn tất");
                }
                String message = failures.isEmpty() ? "Đã cập nhật " + found.size() + " phòng • " + DateFormat.getTimeInstance().format(new Date(now))
                        : "Đã quét " + found.size() + " phòng, " + failures.size() + " phòng lỗi. Mở phòng để xem chi tiết";
                try { repository.save(rooms.values()); }
                catch (Exception error) { message += " • Không lưu được: " + readable(error); }
                refreshButton.setEnabled(!busy && !rooms.isEmpty());
                if (!periodic) setBusy(false, message);
                else if (!busy && scanFence.accepts(scanEpoch)) { statusMessage = message; render(); }
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
        Set<String> unicast = new LinkedHashSet<>();
        for (ManagedDevice saved : room.devices.values()) if (VpnTargets.isPrivateAddress(saved.ip)) unicast.add(saved.ip);
        unicast.addAll(VpnTargets.expand(room.networkTargets));
        if (unicast.size() > 1024) throw new IllegalArgumentException("Đích VPN và IP đã lưu vượt 1024 địa chỉ/phòng. Thu hẹp CIDR trong Mạng LAN/VPN");
        return new ArrayList<>(unicast);
    }

    private String mediaStatus(RoomModel room, ManagedDevice device) {
        if ("none".equals(room.playlist.version)) return "Phòng chưa chọn nội dung";
        if (!device.online || !room.scanError.isEmpty()) return "Chờ gửi khi máy online; chọn Gửi lại cho phòng";
        if (!device.lockAuthenticated || !device.supportsMedia()) return device.mediaUnavailableReason();
        return room.playlist.version.equals(device.mediaVersion) ? "Đã có nội dung mới nhất của phòng" : "Chưa có bản mới của phòng; cần gửi lại";
    }

    private void renderMedia(RoomModel room) {
        content.addView(note("Nội dung chỉ dành cho phòng “" + room.name + "”. Thứ tự từ trên xuống; ảnh mặc định 10 giây, video phát đến hết rồi chuyển mục. Tối đa 20 tệp, 128 MiB mỗi tệp và 256 MiB mỗi phòng. Khi gửi lại, chỉ tải tệp còn thiếu hoặc hỏng; tệp đã bỏ sẽ ngừng phát."));
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
        content.addView(note("Gửi cập nhật chỉ tới máy online trong phòng này. Máy đang tắt/chưa phản hồi vẫn giữ nội dung cũ và hiển thị chờ gửi. Khi máy online, chọn Gửi lại cho cả phòng. Nội dung được lưu trong RoomTN20Data trên ổ Windows của máy con, với ngoại lệ UWF được chuẩn bị khi cài client."));
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
        final List<String> discoveryTargets;
        try { discoveryTargets = scanTargets(room); }
        catch (Exception error) { statusMessage = "Chưa gửi được: " + readable(error); render(); return; }
        setBusy(true, "Đang kiểm tra bộ nhận quảng cáo của phòng " + room.name + "…");
        executor.execute(() -> {
            List<String> results = new ArrayList<>();
            int successes = 0;
            final Map<String, ManagedDevice> fresh = new LinkedHashMap<>();
            try { for (ManagedDevice device : transport.discover(key, discoveryTargets)) fresh.put(device.endpointKey(), device); }
            catch (Exception error) { postResult(() -> setBusy(false, "Chưa gửi: không kiểm tra được máy trong phòng. " + readable(error))); return; }
            for (int i = 0; i < targets.size(); i++) {
                ManagedDevice saved = targets.get(i);
                ManagedDevice target = fresh.get(saved.endpointKey());
                if (destroyed) return;
                if (target == null || !target.online) {
                    String result = saved.hostname + ": CHỜ GỬI — Máy offline/chưa phản hồi; chọn gửi lại khi online";
                    results.add(result);
                    history.record(room.id, saved.listKey(), "Gửi quảng cáo", result);
                    continue;
                }
                final int position = i + 1;
                try {
                    String result = mediaTransfer.send(targetRoomId, key, target, playlist, mediaStorage, (done, total) -> {
                        long percent = total == 0 ? 100 : done * 100 / total;
                        postResult(() -> status.setText("(" + position + "/" + targets.size() + ") " + target.hostname + " • " + percent + "%"));
                    });
                    successes++;
                    results.add(target.hostname + ": OK — " + result);
                } catch (Exception error) { results.add(target.hostname + ": LỖI — " + roomFeatureError(error)); }
                finally { history.record(room.id, saved.listKey(), "Gửi quảng cáo", results.get(results.size() - 1)); }
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
        updateMenuButton.setText("");
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
        Set<String> known = knownBeforeScan.get(room.id);
        if (known != null) {
            int answered = 0;
            for (String id : known) {
                ManagedDevice device = room.devices.get(id);
                if (device != null && device.online && (scanningRooms.contains(room.id)
                        ? device.lastSeen > room.lastScan : device.missedScans == 0)) answered++;
            }
            int newMachines = 0;
            for (ManagedDevice device : room.devices.values()) if (!known.contains(device.listKey())) newMachines++;
            return "Đã nhận " + answered + "/" + known.size() + " máy đã lưu • " + newMachines
                    + " máy mới • " + (scanningRooms.contains(room.id) ? (known.size() - answered) + " đang kiểm tra"
                    : (answered + newMachines) + " online • " + (room.devices.size() - room.onlineCount())
                            + " offline • " + (room.onlineCount() - answered - newMachines) + " chưa phản hồi");
        }
        return room.devices.size() + " máy • " + room.onlineCount() + " online • " + (room.devices.size() - room.onlineCount()) + " offline / chưa phản hồi";
    }

    private String presence(RoomModel room, ManagedDevice device) {
        if (scanningRooms.contains(room.id) && device.lastSeen <= room.lastScan)
            return "Đang kiểm tra • " + (device.online ? "Online gần nhất" : "Chưa phản hồi gần nhất");
        if (!room.scanError.isEmpty()) return "Chưa xác định";
        if (!device.online) return "Offline / chưa phản hồi";
        return device.missedScans == 0 ? "Online" : "Chưa phản hồi (1/2)";
    }

    private static String actionLabel(String action) {
        switch (action) {
            case "wake": return "Bật";
            case "shutdown": return "Tắt";
            case "restart": return "reboot";
            case "freeze": return "Đóng băng";
            case "unfreeze": return "Bỏ đóng băng";
            case "lock": return "Khóa";
            case "unlock": return "Mở khóa";
            case "update-client": return "Cập nhật client";
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
        if (value.contains("MEDIA_STORAGE") || value.contains("MEDIA_ROOT")) return "Máy chưa có nơi lưu quảng cáo bền vững. Cài lại client mới để chuẩn bị thư mục trên ổ Windows và ngoại lệ UWF";
        if (value.contains("MEDIA_TOO_LARGE")) return "Nội dung vượt giới hạn dung lượng của máy";
        if (value.contains("MEDIA_HASH") || value.contains("MEDIA_SIGNATURE")) return "Không xác minh được nội dung gửi tới máy; nội dung cũ được giữ, hãy chọn gửi lại";
        return value;
    }

    private LinearLayout card() {
        LinearLayout value = column();
        value.setPadding(dp(12), dp(7), dp(12), dp(7));
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.WHITE);
        background.setCornerRadius(dp(10));
        background.setStroke(dp(1), Color.rgb(220, 228, 235));
        value.setBackground(background);
        LinearLayout.LayoutParams params = matchWrap();
        params.setMargins(0, dp(3), 0, dp(3));
        value.setLayoutParams(params);
        return value;
    }

    private void compact(Button button) {
        button.setPadding(dp(3), dp(5), dp(3), dp(5));
        button.setMaxLines(2);
        button.setMinHeight(dp(48));
    }

    private int iconFor(String label) {
        switch (label) {
            case "Bật": case "Tắt": case "Bật máy": case "Tắt máy": return R.drawable.ic_ui_power;
            case "reboot": case "Cập nhật": case "Cập nhật client": return R.drawable.ic_ui_restart;
            case "Đóng băng": case "UWF ổ C": return R.drawable.ic_ui_freeze;
            case "Bỏ đóng băng": return R.drawable.ic_ui_thaw;
            case "Khóa": return R.drawable.ic_ui_lock;
            case "Mở khóa": return R.drawable.ic_ui_unlock;
            case "Mở website": return R.drawable.ic_ui_globe;
            case "Đóng ứng dụng": return R.drawable.ic_ui_close_apps;
            case "Quét LAN": return R.drawable.ic_ui_scan;
            case "Mạng LAN": case "Địa chỉ IP": case "Địa chỉ MAC": return R.drawable.ic_ui_network;
            case "Quảng cáo": case "+ Chọn ảnh / video": return R.drawable.ic_ui_media;
            case "Gửi lại cho cả phòng": return R.drawable.ic_ui_restart;
            case "⋮": return R.drawable.ic_ui_menu_vertical;
            case "Thao tác": return R.drawable.ic_ui_controls;
            case "‹ Quay lại": return R.drawable.ic_ui_back;
            case "+ Thêm phòng": return R.drawable.ic_ui_add;
            case "Lần thấy cuối": case "Thời lượng": case "Nhật ký máy": return R.drawable.ic_ui_clock;
            case "Xóa": return R.drawable.ic_ui_trash;
            case "Lên": return R.drawable.ic_ui_up;
            case "Xuống": return R.drawable.ic_ui_down;
            case "Trạng thái": case "Màn hình": case "Phiên bản client": return R.drawable.ic_ui_computer;
            default: return 0;
        }
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
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(5), dp(6), dp(5), dp(6));
        button.setMaxLines(2);
        button.setMinHeight(dp(48));
        button.setStateListAnimator(null);
        int icon = iconFor(value);
        if (icon != 0) {
            android.graphics.drawable.Drawable drawable = getDrawable(icon).mutate();
            drawable.setTintList(button.getTextColors());
            drawable.setBounds(0, 0, dp(20), dp(20));
            button.setCompoundDrawables(null, drawable, null, null);
            button.setCompoundDrawablePadding(dp(4));
            if ("⋮".equals(value)) button.setText("");
            if ("‹ Quay lại".equals(value)) button.setText("Quay lại");
            if ("+ Thêm phòng".equals(value)) button.setText("Thêm phòng");
            if ("+ Chọn ảnh / video".equals(value)) button.setText("Chọn ảnh / video");
        }
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(6));
        android.content.res.ColorStateList ink = android.content.res.ColorStateList.valueOf(Color.argb(35, 140, 170, 200));
        button.setBackground(new android.graphics.drawable.RippleDrawable(ink, background, null));
        return button;
    }

    private TextView note(String value) { TextView view = text(value, 12, Color.DKGRAY); view.setPadding(0, dp(7), 0, dp(7)); return view; }
    private TextView text(String value, int size, int color) { TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(color); return view; }
    private LinearLayout column() { LinearLayout view = new LinearLayout(this); view.setOrientation(LinearLayout.VERTICAL); return view; }
    private LinearLayout row() { LinearLayout view = new LinearLayout(this); view.setOrientation(LinearLayout.HORIZONTAL); view.setGravity(Gravity.CENTER_VERTICAL); return view; }
    private LinearLayout.LayoutParams matchWrap() { return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); }
    private LinearLayout.LayoutParams weighted() { LinearLayout.LayoutParams value = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1); value.setMargins(dp(2), dp(2), dp(2), dp(2)); return value; }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
