"""Exercise real room discovery/commands against the disposable LAN fixture."""
import json
import re
import subprocess
import sys
import time
import urllib.request
import xml.etree.ElementTree as ET

PACKAGE = "com.lanpower.standalone"
CONTROL = "http://127.0.0.1:17841"
ROOM_A = "CI Room A"
ROOM_B = "CI Room B"
IDS_A = {f"00000000-0000-4000-8000-{i:012d}" for i in [1, 2]}
SCREEN = None


def adb(*args):
    return subprocess.check_output(["adb", *args], text=True, timeout=30)


def ui():
    adb("shell", "uiautomator", "dump", "/sdcard/lanpower-ui.xml")
    return ET.fromstring(adb("shell", "cat", "/sdcard/lanpower-ui.xml"))


def by_id(name):
    return lambda node: node.get("resource-id") == name or node.get("resource-id", "").endswith(":id/" + name)


def swipe(up):
    global SCREEN
    if SCREEN is None:
        dimensions = re.search(r"(\d+)x(\d+)", adb("shell", "wm", "size"))
        SCREEN = tuple(map(int, dimensions.groups()))
    width, height = SCREEN
    top, bottom = int(height * 0.28), int(height * 0.82)
    adb("shell", "input", "swipe", str(width // 2), str(bottom if up else top),
        str(width // 2), str(top if up else bottom), "250")


def scroll_top():
    swipe(False)
    swipe(False)


def tap(predicate):
    deadline = time.monotonic() + 30
    scrolls = 0
    while time.monotonic() < deadline:
        tree = ui()
        matched = False
        for node in tree.iter("node"):
            if predicate(node.attrib):
                matched = True
            if predicate(node.attrib) and node.attrib.get("enabled", "true") == "true":
                left, top, right, bottom = map(int, re.findall(r"\d+", node.attrib["bounds"]))
                adb("shell", "input", "tap", str((left + right) // 2), str((top + bottom) // 2))
                return
        if not matched and any(node.attrib.get("class") == "android.widget.ScrollView" for node in tree.iter("node")):
            if scrolls < 3:
                swipe(True)
                scrolls += 1
            else:
                scroll_top()
                scrolls = 0
        time.sleep(0.3)
    raise AssertionError("Expected enabled control was not found")


def wait_for(predicate, message, seconds=30):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        tree = ui()
        if predicate(tree):
            return tree
        time.sleep(0.3)
    raise AssertionError(message)


def wait_text(text):
    return wait_for(lambda tree: any(text in node.attrib.get("text", "") for node in tree.iter("node")),
                    f"Expected status was not found: {text}")


def tap_text(text):
    tap(lambda node: node.get("text") == text)


def control(path="/state", payload=None):
    request = urllib.request.Request(CONTROL + path,
                                     data=None if payload is None else json.dumps(payload).encode(),
                                     headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(request, timeout=5) as response:
        return json.load(response)


def wait_events(start, action, device_ids):
    deadline = time.monotonic() + 25
    while time.monotonic() < deadline:
        events = [event for event in control()["events"][start:] if event["action"] == action]
        if {event["deviceId"] for event in events} == device_ids:
            return events
        time.sleep(0.2)
    raise AssertionError(f"Fixture did not receive {action} for exactly {device_ids}")


def wait_idle():
    wait_for(lambda tree: any(by_id("refresh")(node.attrib) and node.attrib.get("enabled") == "true"
                              for node in tree.iter("node")), "Room refresh never became available")


def refresh():
    wait_idle()
    tap(by_id("refresh"))
    wait_idle()


def visible_devices():
    found = set()
    scroll_top()
    for _ in range(3):
        found.update(node.attrib.get("content-desc", "")[7:] for node in ui().iter("node")
                     if node.attrib.get("content-desc", "").startswith("device:"))
        swipe(True)
    scroll_top()
    return found


def detail_action_ids():
    found = set()
    for _ in range(3):
        found.update(node.attrib.get("resource-id", "").split(":id/")[-1] for node in ui().iter("node"))
        swipe(True)
    scroll_top()
    return found


def open_room(name):
    wait_idle()
    tap(lambda node: node.get("content-desc") == "room:" + name)
    wait_for(lambda tree: any(by_id("select_all")(node.attrib) for node in tree.iter("node")), "Room did not open")
    wait_idle()


def home():
    tap(by_id("back"))
    wait_text("Các phòng")


def submit_room(name, key):
    tap(by_id("add_room"))
    tap(by_id("room_name_input"))
    adb("shell", "input", "text", name.replace(" ", "%s"))
    tap(by_id("room_key_input"))
    adb("shell", "input", "text", key)
    adb("shell", "input", "keyevent", "BACK")
    tap_text("Tạo phòng")


def add_room(name, key):
    submit_room(name, key)
    wait_for(lambda tree: any(node.attrib.get("content-desc") == "room:" + name
                              for node in tree.iter("node")), "Created room is missing")
    wait_idle()


def close_result(expected_error=None):
    tree = wait_for(lambda tree: any(node.attrib.get("resource-id") == "android:id/button1" and
                                     node.attrib.get("text") == "Đóng" for node in tree.iter("node")),
                    "Per-device operation results did not appear")
    if expected_error:
        text = " ".join(node.attrib.get("text", "") for node in tree.iter("node"))
        assert expected_error in text, f"Partial failure was not shown: {text}"
    tap_text("Đóng")
    wait_idle()


def bulk(action, expected_ids=IDS_A, expected_error=None):
    start = len(control()["events"])
    tap(by_id("action_" + action))
    tap_text("Thực hiện")
    events = wait_events(start, action, expected_ids)
    close_result(expected_error)
    return events


def legacy_prepare():
    test_key = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
    tap(lambda node: node.get("class") == "android.widget.EditText")
    adb("shell", "input", "text", test_key)
    adb("shell", "input", "keyevent", "BACK")
    tap_text("Lưu khóa")
    wait_text("Đã lưu Management Key bằng Android Keystore.")
    preferences = adb("shell", "run-as", PACKAGE, "cat", "shared_prefs/lanpower_secure.xml")
    assert test_key not in preferences, "Legacy key was saved as plaintext"
    adb("shell", "am", "force-stop", PACKAGE)
    adb("push", "tests/legacy-devices.xml", "/data/local/tmp/lanpower-legacy-devices.xml")
    adb("shell", "run-as", PACKAGE, "cp", "/data/local/tmp/lanpower-legacy-devices.xml", "shared_prefs/lanpower_devices.xml")
    print("ANDROID_OLD_KEY_PREPARED=PASS")


if "--prepare-key-only" in sys.argv:
    legacy_prepare()
    sys.exit(0)

if "--existing-key" not in sys.argv:
    control("/reset", {})
wait_text("Các phòng")
if "--existing-key" in sys.argv:
    open_room("Phòng cũ")
    assert visible_devices() == {"CI-L-04"}, "Saved legacy PC was not migrated while offline"
    wait_text("1 offline")
    control("/set", {"devices": {"00000000-0000-4000-8000-000000000004": {"online": True}}})
    refresh()
    assert visible_devices() == {"CI-L-04"}, "Migrated encrypted key failed authenticated discovery"
    wait_text("1 online")
    home()

add_room(ROOM_A, "RoomA123")
add_room(ROOM_B, "RoomB456")
submit_room("CI Duplicate", "RoomA123")
wait_text("Key này đã thuộc phòng")
assert not any(node.attrib.get("content-desc") == "room:CI Duplicate" for node in ui().iter("node")), "Duplicate key created a second room"
open_room(ROOM_A)
refresh()
assert visible_devices() == {"CI-A-01", "CI-A-02"}, "Room A did not isolate its discovered machines"

tap(lambda node: node.get("content-desc") == "device:CI-A-01")
tree = wait_text("10.0.2.2")
action_ids = detail_action_ids()
for action in ["wake", "shutdown", "restart", "freeze", "unfreeze"]:
    assert "action_" + action in action_ids, f"Missing detail action: {action}"
tap(by_id("back"))

tap(lambda node: node.get("resource-id", "").endswith(":id/device_select") and node.get("content-desc") == "Chọn CI-A-01")
bulk("freeze", {sorted(IDS_A)[0]})
tap(by_id("select_all"))
events = bulk("freeze")
assert all(event["status"] == "accepted" for event in events)
assert all(device["pending"] is True for device in control()["devices"] if device["id"] in IDS_A)

second = sorted(IDS_A)[1]
control("/set", {"devices": {second: {"failActions": ["unfreeze"]}}})
events = bulk("unfreeze", expected_error="TEST_UWF_FAILURE")
assert {event["status"] for event in events} == {"accepted", "rejected"}
control("/set", {"devices": {second: {"failActions": []}}})
bulk("unfreeze")
bulk("restart")
bulk("shutdown")
refresh()
wait_text("2 offline")
assert visible_devices() == {"CI-A-01", "CI-A-02"}, "Offline computers disappeared from saved room"
bulk("wake")
refresh()
wait_text("2 online")
home()

open_room(ROOM_B)
refresh()
assert visible_devices() == {"CI-B-03"}, "Room B displayed devices from another room"
home()
preferences = adb("shell", "run-as", PACKAGE, "cat", "shared_prefs/lanpower_secure.xml")
rooms = adb("shell", "run-as", PACKAGE, "cat", "shared_prefs/lanpower_rooms_v2.xml")
for key in ["RoomA123", "RoomB456"]:
    assert key not in preferences and key not in rooms, "Room key was stored as plaintext"

control("/set", {"devices": {device_id: {"online": False} for device_id in IDS_A}})
adb("shell", "am", "force-stop", PACKAGE)
adb("shell", "am", "start", "-W", "-n", f"{PACKAGE}/.MainActivity", "--ez", "lanpower_ci", "true")
wait_text("Các phòng")
for name, expected in [(ROOM_A, {"CI-A-01", "CI-A-02"}), (ROOM_B, {"CI-B-03"})]:
    open_room(name)
    assert visible_devices() == expected, "Saved room device identities were lost after restart"
    if name == ROOM_A:
        wait_text("2 offline")
        control("/set", {"devices": {device_id: {"online": True} for device_id in IDS_A}})
    refresh()
    assert visible_devices() == expected, "Stored encrypted room key failed discovery after restart"
    wait_text(f"{len(expected)} online")
    home()
print("ANDROID_UI_SMOKE_TEST=PASS (multiple rooms, 8-character keys, real UDP isolation, online/offline, details, bulk UWF/power/WOL, encrypted persistence)")
