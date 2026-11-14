"""Exercise the exact non-debuggable release through its public UI only."""
from pathlib import Path

# Reuse UI gestures, not debug storage access or the executable debug scenario.
helper_path = Path(__file__).with_name("android-ui-smoke.py")
helpers = helper_path.read_text(encoding="utf-8")
marker = 'if "--prepare-key-only" in sys.argv:'
assert helpers.count(marker) == 1, "Review release harness after debug harness entry-point changes"
exec(compile(helpers.split(marker)[0], str(helper_path), "exec"))


def refresh():
    wait_idle()
    before = control()["discoveryPackets"]
    tap(by_id("refresh"), lambda tree: any(n.get("text") == "Tìm máy mới" for n in tree.iter("node")))
    tap_text("Tìm máy mới", lambda _tree: control()["discoveryPackets"] > before)
    wait_idle()


def configure_network():
    scroll_top()
    tap(by_id("network_settings"), lambda tree: has_id(tree, "network_targets_input"))
    type_field("network_targets_input", "10.0.2.2")
    tap_text("Lưu mạng", lambda tree: not has_id(tree, "network_targets_input"))
    wait_idle()


def assert_saved_network():
    scroll_top()
    tap(by_id("network_settings"), lambda tree: has_id(tree, "network_targets_input"))
    assert field_text(ui(), "network_targets_input") == "10.0.2.2", "Release lost its configured network target"
    tap_text("Hủy", lambda tree: not has_id(tree, "network_targets_input"))


def release_menu():
    def item(name):
        return lambda attrs: by_id(name)(attrs) or attrs.get("content-desc") == name

    tap(by_id("update_menu"), lambda tree: any(item("update_check")(node.attrib) for node in tree.iter("node")))
    tree = ui()
    for name, label in [("update_check", "Kiểm tra cập nhật"), ("update_install", "Cài bản mới"), ("update_state", "Trạng thái cập nhật")]:
        row = next(node for node in tree.iter("node") if item(name)(node.attrib))
        assert any(node.attrib.get("text") == label for node in row.iter("node")), f"Missing release menu label: {label}"
        if name in {"update_check", "update_state"}:
            assert row.attrib.get("enabled") == "true", "Release update actions must not be disabled by CI flags"
    with open("emulator-evidence/release-update-menu.png", "wb") as picture:
        subprocess.run(["adb", "exec-out", "screencap", "-p"], stdout=picture, check=True, timeout=30)
    tap(item("update_state"), lambda snapshot: any(node.attrib.get("text") == "Cập nhật Room TN20" for node in snapshot.iter("node")))
    dialog = wait_text("Cập nhật Room TN20")
    assert not any("Kiểm thử: tạm tắt cập nhật" in node.attrib.get("text", "") for node in dialog.iter("node")), "Production APK accepted a debug-only update override"
    with open("emulator-evidence/release-update-status.png", "wb") as picture:
        subprocess.run(["adb", "exec-out", "screencap", "-p"], stdout=picture, check=True, timeout=30)
    tap_text("Đóng", lambda snapshot: not any(node.attrib.get("text") == "Cập nhật Room TN20" for node in snapshot.iter("node")))
    print("ANDROID_RELEASE_UPDATE_MENU=PASS (normal update controls, no debug override)")


private_access = subprocess.run(["adb", "shell", "run-as", PACKAGE, "id"], capture_output=True, text=True, timeout=30)
assert private_access.returncode != 0 and "not debuggable" in (private_access.stdout + private_access.stderr).lower(), "Published APK must reject run-as access"
wait_text("Các phòng")
assert_branding()
release_menu()

# Upgrade the old APK in place. Its encrypted key and saved offline PC must
# survive; configure the real directed route through the ordinary settings UI.
open_room("Phòng cũ")
assert visible_devices() == {"CI-L-04"}, "Release upgrade lost the saved legacy PC"
scroll_top()
wait_text("1 offline")
control("/set", {"devices": {"00000000-0000-4000-8000-000000000004": {"online": True}}})
configure_network()
refresh()
scroll_top()
wait_text("1 online")
home()
print("ANDROID_RELEASE_LEGACY_UPGRADE=PASS (same certificate, saved PC and encrypted key migrated)")

for name, room_key, expected in [(ROOM_A, "RoomA123", {"CI-A-01", "CI-A-02"}), (ROOM_B, "RoomB456", {"CI-B-03"})]:
    add_room(name, room_key)
    open_room(name)
    configure_network()
    refresh()
    assert visible_devices() == expected, "Release discovery crossed room keys"
    home()

open_room(ROOM_A)
tap(by_id("select_all"), lambda tree: any(by_id("select_all")(node.attrib) and node.attrib.get("checked") == "true" for node in tree.iter("node")))
bulk("freeze")
assert all(device["pending"] is True for device in control()["devices"] if device["id"] in IDS_A)
bulk("unfreeze")
bulk("restart")
tap(by_id("select_all"), lambda tree: any(by_id("select_all")(node.attrib) and node.attrib.get("checked") == "false" for node in tree.iter("node")))
select_second = lambda attrs: by_id("device_select")(attrs) and attrs.get("content-desc") == "Chọn CI-A-02"
tap(select_second, lambda tree: any(select_second(node.attrib) and node.attrib.get("checked") == "true" for node in tree.iter("node")))
target_id = sorted(IDS_A)[1]
bulk("shutdown", {target_id})
refresh()
scroll_top()
wait_text("1 online")
assert visible_devices() == {"CI-A-01", "CI-A-02"}, "Release removed an offline PC"
start = len(control()["events"])
tap(by_id("action_wake"), lambda tree: has_button(tree, "Thực hiện"))
tap_text("Thực hiện", lambda tree: not has_button(tree, "Thực hiện"))
wait_events(start, "wake-relay", {target_id})
close_result()
refresh()
scroll_top()
wait_text("2 online")
home()

adb("shell", "am", "force-stop", PACKAGE)
adb("shell", "am", "start", "-W", "-n", f"{PACKAGE}/.MainActivity")
wait_text("Các phòng")
for name, expected in [("Phòng cũ", {"CI-L-04"}), (ROOM_A, {"CI-A-01", "CI-A-02"}), (ROOM_B, {"CI-B-03"})]:
    open_room(name)
    assert visible_devices() == expected, "Release lost its saved room PCs after restart"
    assert_saved_network()
    refresh()
    scroll_top()
    wait_text(f"{len(expected)} online")
    home()
print("ANDROID_RELEASE_UI=PASS (non-debuggable, legacy upgrade, normal menu, directed discovery, room isolation, UWF/power/relay, offline persistence; public UI only)")
