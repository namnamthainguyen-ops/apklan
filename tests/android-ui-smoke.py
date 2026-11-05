"""Exercise key storage and LAN scan on an isolated Android emulator."""
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

PACKAGE = "com.lanpower.standalone"


def adb(*args):
    return subprocess.check_output(["adb", *args], text=True, timeout=30)


def ui():
    adb("shell", "uiautomator", "dump", "/sdcard/lanpower-ui.xml")
    return ET.fromstring(adb("shell", "cat", "/sdcard/lanpower-ui.xml"))


def tap(predicate):
    for node in ui().iter("node"):
        if predicate(node.attrib):
            left, top, right, bottom = map(int, re.findall(r"\d+", node.attrib["bounds"]))
            adb("shell", "input", "tap", str((left + right) // 2), str((top + bottom) // 2))
            return
    raise AssertionError("Expected control was not found")


def wait_text(text):
    deadline = time.monotonic() + 15
    while time.monotonic() < deadline:
        if any(text in node.attrib.get("text", "") for node in ui().iter("node")):
            return
        time.sleep(0.5)
    raise AssertionError(f"Expected status was not found: {text}")


# This all-zero test key is used only inside the disposable emulator.
test_key = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
if "--existing-key" not in sys.argv:
    tap(lambda node: node.get("class") == "android.widget.EditText")
    adb("shell", "input", "text", test_key)
    adb("shell", "input", "keyevent", "BACK")
tap(lambda node: node.get("text") == "Lưu khóa")
wait_text("Đã lưu Management Key bằng Android Keystore.")
preferences = adb("shell", "run-as", PACKAGE, "cat", "shared_prefs/lanpower_secure.xml")
assert "management_key_iv" in preferences and "management_key" in preferences
assert test_key not in preferences, "Management key was saved as plaintext"
if "--prepare-key-only" in sys.argv:
    print("ANDROID_OLD_KEY_PREPARED=PASS")
    sys.exit(0)

# Loading and saving again after a process restart exercises Keystore decryption.
adb("shell", "am", "force-stop", PACKAGE)
adb("shell", "am", "start", "-W", "-n", f"{PACKAGE}/.MainActivity")
tap(lambda node: node.get("text") == "Lưu khóa")
wait_text("Đã lưu Management Key bằng Android Keystore.")
tap(lambda node: node.get("text") == "Quét mạng LAN")
wait_text("Đã tìm thấy")
print("ANDROID_UI_SMOKE_TEST=PASS (encrypted key, process reload, LAN scan)")
