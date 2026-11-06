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
    # Branding adds a fixed header/footer. Derive the gesture from the actual
    # scroll container instead of starting over the status bar or toolbar.
    for node in ui().iter("node"):
        if node.attrib.get("class") == "android.widget.ScrollView":
            left, bound_top, right, bound_bottom = map(int, re.findall(r"\d+", node.attrib["bounds"]))
            if bound_bottom - bound_top > 80:
                width = left + right
                top = bound_top + int((bound_bottom - bound_top) * 0.15)
                bottom = bound_bottom - int((bound_bottom - bound_top) * 0.15)
                break
    adb("shell", "input", "swipe", str(width // 2), str(bottom if up else top),
        str(width // 2), str(top if up else bottom), "250")


def scroll_top():
    swipe(False)
    swipe(False)


def tap(predicate, transition=None):
    deadline = time.monotonic() + 45
    scrolls = 0
    while time.monotonic() < deadline:
        tree = ui()
        if transition is not None and transition(tree):
            return
        matched = False
        for node in tree.iter("node"):
            if predicate(node.attrib):
                matched = True
            if predicate(node.attrib) and node.attrib.get("enabled", "true") == "true":
                left, top, right, bottom = map(int, re.findall(r"\d+", node.attrib["bounds"]))
                adb("shell", "input", "tap", str((left + right) // 2), str((top + bottom) // 2))
                if transition is None:
                    return
                time.sleep(0.3)
                break
        if not matched and any(node.attrib.get("class") == "android.widget.ScrollView" for node in tree.iter("node")):
            if scrolls < 3:
                swipe(True)
                scrolls += 1
            else:
                scroll_top()
                scrolls = 0
        time.sleep(0.3)
    raise AssertionError("Expected enabled control or its resulting UI transition was not observed")


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


def assert_branding():
    tree = ui()
    texts = {node.attrib.get("text", "") for node in tree.iter("node")}
    for label in ["Room TN20", "TN20", "Công ty TNHH TN20", "20.com.vn"]:
        assert label in texts, f"Missing Room TN20 branding: {label}"
    assert any(node.attrib.get("text") == "20.com.vn" and node.attrib.get("content-desc") == "https://20.com.vn"
               for node in tree.iter("node")), "Website label does not identify the TN20 website"
    print("ANDROID_BRANDING=PASS (Room TN20, TN20, company and website)")


def tap_text(text, transition=None):
    tap(lambda node: node.get("text", "").casefold() == text.casefold(), transition)


def has_id(tree, name):
    return any(by_id(name)(node.attrib) for node in tree.iter("node"))


def has_button(tree, text):
    return any(node.attrib.get("resource-id") == "android:id/button1" and
               node.attrib.get("text", "").casefold() == text.casefold() for node in tree.iter("node"))


def field_text(tree, name):
    for node in tree.iter("node"):
        if by_id(name)(node.attrib):
            return node.attrib.get("text", "")
    raise AssertionError(f"Missing edit field: {name}")


def type_field(name, value, secret=False):
    for _ in range(3):
        tap(by_id(name), lambda tree: any(by_id(name)(node.attrib) and node.attrib.get("focused") == "true"
                                         for node in tree.iter("node")))
        current = field_text(ui(), name)
        adb("shell", "input", "keyevent", "KEYCODE_MOVE_END")
        adb("shell", "input", "keyevent", *(["KEYCODE_DEL"] * max(16, len(current) + 4)))
        adb("shell", "input", "text", value.replace(" ", "%s"))
        actual = field_text(ui(), name)
        if (not secret and actual == value) or (secret and (not actual or len(actual) == len(value))):
            return
        print(f"ANDROID_TEXT_INPUT_RETRY={name}")
    raise AssertionError(f"ADB text entry did not stabilize for {name}")


def saved_rooms():
    xml = adb("shell", "run-as", PACKAGE, "cat", "shared_prefs/lanpower_rooms_v2.xml")
    values = ET.fromstring(xml).find("string[@name='rooms']")
    return json.loads(values.text or "[]")


def last_scans():
    return {room["id"]: room.get("lastScan", 0) for room in saved_rooms()}


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
    before = last_scans()
    tap(by_id("refresh"), lambda _tree: any(value > before.get(room_id, 0) for room_id, value in last_scans().items()))
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
    tap(lambda node: node.get("content-desc") == "room:" + name,
        lambda tree: has_id(tree, "select_all") and any(node.attrib.get("text") == name for node in tree.iter("node")))
    wait_idle()


def home():
    tap(by_id("back"), lambda tree: any(node.attrib.get("text") == "Các phòng" for node in tree.iter("node")))


def submit_room(name, key, hold_modal=False):
    tap(by_id("add_room"), lambda tree: has_id(tree, "room_name_input"))
    type_field("room_name_input", name)
    type_field("room_key_input", key, secret=True)
    if field_text(ui(), "room_name_input") != name:
        type_field("room_name_input", name)
    if hold_modal:
        before = control()["discoveryPackets"]
        time.sleep(21)
        tree = ui()
        for control_id in ["room_name_input", "room_key_input"]:
            assert any(by_id(control_id)(node.attrib) for node in tree.iter("node")), "Auto-refresh dismissed the Add Room dialog"
        assert control()["discoveryPackets"] == before, "Auto-refresh sent discovery while Add Room dialog was open"
        print("ANDROID_MODAL_AUTO_REFRESH_GUARD=PASS (>20 seconds, dialog retained, no discovery traffic)")
    # BACK can cancel a dialog when a hardware keyboard suppresses the IME.
    # AlertDialog keeps its positive action visible above any soft keyboard.
    assert field_text(ui(), "room_name_input") == name, "Room name changed after text entry"
    tap(by_id("android:id/button1"), lambda tree: not has_id(tree, "room_name_input"))


def add_room(name, key, hold_modal=False):
    submit_room(name, key, hold_modal)
    wait_for(lambda tree: any(node.attrib.get("content-desc") == "room:" + name
                              for node in tree.iter("node")), "Created room is missing")
    wait_idle()


def close_result(expected_error=None):
    tree = wait_for(lambda tree: any(node.attrib.get("resource-id") == "android:id/button1" and
                                     node.attrib.get("text", "").casefold() == "Đóng".casefold() for node in tree.iter("node")),
                    "Per-device operation results did not appear")
    if expected_error:
        text = " ".join(node.attrib.get("text", "") for node in tree.iter("node"))
        assert expected_error in text, f"Partial failure was not shown: {text}"
    tap_text("Đóng", lambda tree: not has_button(tree, "Đóng"))
    wait_idle()


def bulk(action, expected_ids=IDS_A, expected_error=None):
    start = len(control()["events"])
    tap(by_id("action_" + action), lambda tree: has_button(tree, "Thực hiện"))
    tap_text("Thực hiện", lambda tree: not has_button(tree, "Thực hiện"))
    events = wait_events(start, action, expected_ids)
    close_result(expected_error)
    return events


def legacy_prepare():
    test_key = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
    edit = lambda node: node.get("class") == "android.widget.EditText"
    for _ in range(3):
        tap(edit, lambda tree: any(edit(node.attrib) and node.attrib.get("focused") == "true" for node in tree.iter("node")))
        adb("shell", "input", "keyevent", "KEYCODE_MOVE_END")
        adb("shell", "input", "keyevent", *(["KEYCODE_DEL"] * 60))
        adb("shell", "input", "text", test_key)
        actual = next(node.attrib.get("text", "") for node in ui().iter("node") if edit(node.attrib))
        if not actual or len(actual) == len(test_key):
            break
    else:
        raise AssertionError("Legacy key entry did not stabilize")
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
assert_branding()
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
add_room(ROOM_B, "RoomB456", hold_modal=True)
before_duplicate = {room["id"]: room["name"] for room in saved_rooms()}
submit_room("CI Duplicate", "RoomA123")
wait_idle()
after_duplicate = {room["id"]: room["name"] for room in saved_rooms()}
assert after_duplicate == before_duplicate, "Duplicate key changed the persisted room identities or names"
assert "CI Duplicate" not in after_duplicate.values(), "Duplicate key was persisted as another room"
assert not any(node.attrib.get("content-desc") == "room:CI Duplicate" for node in ui().iter("node")), "Duplicate key created a second room"
print("ANDROID_DUPLICATE_ROOM_REJECTED=PASS (persisted room IDs and names unchanged)")
open_room(ROOM_A)
refresh()
assert visible_devices() == {"CI-A-01", "CI-A-02"}, "Room A did not isolate its discovered machines"

tap(lambda node: node.get("content-desc") == "device:CI-A-01",
    lambda tree: any(node.attrib.get("text", "").startswith("Hostname: CI-A-01") for node in tree.iter("node")))
tree = wait_text("Hostname: CI-A-01")
action_ids = detail_action_ids()
for action in ["wake", "shutdown", "restart", "freeze", "unfreeze"]:
    assert "action_" + action in action_ids, f"Missing detail action: {action}"
tap(by_id("back"), lambda tree: has_id(tree, "select_all"))

single_selection = lambda node: by_id("device_select")(node) and node.get("content-desc") == "Chọn CI-A-01"
tap(single_selection, lambda tree: any(single_selection(node.attrib) and node.attrib.get("checked") == "true"
                                      for node in tree.iter("node")))
bulk("freeze", {sorted(IDS_A)[0]})
tap(by_id("select_all"), lambda tree: any(by_id("select_all")(node.attrib) and node.attrib.get("checked") == "true"
                                         for node in tree.iter("node")))
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
scroll_top()
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
