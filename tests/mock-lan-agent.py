"""Disposable LAN protocol fixture. It never executes OS power or UWF commands."""
import argparse
import base64
import hashlib
import hmac
import json
import select
import signal
import socket
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ROOM_KEYS = {"A": "RoomA123", "B": "RoomB456"}
KEYS = {room: hashlib.pbkdf2_hmac("sha256", key.encode(), b"LanPower/RoomKey/v1", 210000, 32)
        for room, key in ROOM_KEYS.items()}
KEYS["L"] = bytes(32)
LOCK = threading.RLock()
DEVICES = {}
EVENTS = []
DISCOVERY_COUNT = 0
DISCOVERY_BY_ROOM = {}


def reset():
    global DISCOVERY_COUNT
    with LOCK:
        DEVICES.clear()
        EVENTS.clear()
        DISCOVERY_COUNT = 0
        DISCOVERY_BY_ROOM.clear()
        for index, room in enumerate(["A", "A", "B", "L"], 1):
            device_id = f"00000000-0000-4000-8000-{index:012d}"
            DEVICES[device_id] = {"id": device_id, "hostname": f"CI-{room}-{index:02d}", "room": room,
                                  "mac": f"02:00:00:00:00:{index:02x}", "online": True,
                                  "frozen": False, "pending": None, "locked": False, "mediaVersion": "none", "failActions": [], "nonces": set()}


def sign(key, canonical):
    return base64.b64encode(hmac.new(key, canonical.encode(), hashlib.sha256).digest()).decode()


def uwf(device):
    value = "ON" if device["frozen"] else "OFF"
    next_value = device["frozen"] if device["pending"] is None else device["pending"]
    return value + (" (next ON)" if next_value else " (next OFF)")


def snapshot():
    with LOCK:
        return {"devices": [{**{k: v for k, v in device.items() if k != "nonces"}, "uwf": uwf(device)}
                            for device in DEVICES.values()], "events": list(EVENTS),
                "discoveryPackets": DISCOVERY_COUNT, "discoveryByRoom": dict(DISCOVERY_BY_ROOM)}


class Control(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def do_GET(self):
        data = json.dumps(snapshot()).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", "0"))) or b"{}")
        if self.path == "/reset":
            reset()
        elif self.path == "/set":
            with LOCK:
                for device_id, changes in body.get("devices", {}).items():
                    for key, value in changes.items():
                        if key in {"online", "frozen", "pending", "locked", "failActions"}:
                            DEVICES[device_id][key] = value
        else:
            self.send_error(404)
            return
        self.do_GET()


def send(udp, remote, value):
    udp.sendto(json.dumps(value, separators=(",", ":")).encode(), remote)


def handle_request(udp, remote, data):
    global DISCOVERY_COUNT
    try:
        message = json.loads(data)
        with LOCK:
            if message.get("type") == "discover":
                DISCOVERY_COUNT += 1
                nonce = message["nonce"]
                for device in DEVICES.values():
                    key = KEYS[device["room"]]
                    if not device["online"] or not hmac.compare_digest(message["signature"], sign(key, f"discover\n{nonce}")):
                        continue
                    DISCOVERY_BY_ROOM[device["room"]] = DISCOVERY_BY_ROOM.get(device["room"], 0) + 1
                    response = {"type": "discovery", "requestNonce": nonce, "deviceId": device["id"],
                                "hostname": device["hostname"], "mac": device["mac"], "agentVersion": "1.3.0",
                                "uwf": uwf(device), "capabilities": "power,uwf,wolrelay,sessionlock,media",
                                "lockState": "locked" if device["locked"] else "unlocked", "mediaVersion": device["mediaVersion"],
                                "mediaPort": 17842, "mediaCertSha256": "a" * 64}
                    response["signature"] = sign(key, f"discovery\n{nonce}\n{device['id']}\n{device['hostname']}\n{device['mac']}")
                    response["stateSignature"] = sign(key, f"state\n{nonce}\n{device['id']}\n1.3.0\n{response['uwf']}\n{response['capabilities']}")
                    response["lockSignature"] = sign(key, f"lockstate\n{nonce}\n{device['id']}\n{response['lockState']}\n{device['mediaVersion']}\n17842\n{response['mediaCertSha256']}")
                    send(udp, remote, response)
                return
            if message.get("type") != "command":
                return
            device = DEVICES.get(message.get("deviceId"))
            if not device or not device["online"]:
                return
            key = KEYS[device["room"]]
            canonical = f"command\n{message['requestId']}\n{device['id']}\n{message['action']}\n{message['timestamp']}\n{message['nonce']}"
            if message.get("action") == "wake":
                canonical += "\n" + message["targetMac"]
            if abs(time.time() - int(message["timestamp"])) > 120 or message["nonce"] in device["nonces"]:
                return
            if not hmac.compare_digest(message["signature"], sign(key, canonical)):
                return
            device["nonces"].add(message["nonce"])
            action = message["action"]
            rejected = action in device["failActions"] or action not in {"freeze", "unfreeze", "shutdown", "restart", "wake", "lock", "unlock"}
            status = "rejected" if rejected else "accepted"
            detail = "TEST_UWF_FAILURE" if rejected else "Power action accepted."
            if not rejected and action in {"freeze", "unfreeze"}:
                device["pending"] = action == "freeze"
                detail = f"UWF {action} scheduled. Restart required. {uwf(device)}"
            if not rejected and action in {"lock", "unlock"}:
                device["locked"] = action == "lock"
                detail = "Interactive display confirmed " + action + "."
            response = {"type": "ack", "requestId": message["requestId"], "status": status, "message": detail}
            response["signature"] = sign(key, f"ack\n{message['requestId']}\n{status}")
            response["detailSignature"] = sign(key, f"ack-detail\n{message['requestId']}\n{status}\n{detail}")
            send(udp, remote, response)
            EVENTS.append({"deviceId": device["id"], "hostname": device["hostname"], "action": 'relay-request' if action == 'wake' else action, "status": status})
            if not rejected and action == "wake":
                for target in DEVICES.values():
                    if target["mac"] == message.get("targetMac"):
                        target["online"] = True
                        EVENTS.append({"deviceId": target["id"], "hostname": target["hostname"], "relayDeviceId": device["id"],
                                       "action": "wake-relay", "targetMac": target["mac"], "status": "accepted"})
            if not rejected and action == "shutdown":
                device["online"] = False
            elif not rejected and action == "restart":
                if device["pending"] is not None:
                    device["frozen"] = device["pending"]
                    device["pending"] = None
    except (KeyError, ValueError, TypeError, UnicodeDecodeError):
        return


def handle_wake(data):
    if len(data) != 102 or data[:6] != b"\xff" * 6:
        return
    mac = data[6:12]
    if data[6:] != mac * 16:
        return
    with LOCK:
        for device in DEVICES.values():
            if bytes.fromhex(device["mac"].replace(":", "")) == mac:
                device["online"] = True
                EVENTS.append({"deviceId": device["id"], "hostname": device["hostname"], "action": "wake", "status": "sent"})


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=17840)
    parser.add_argument("--http-port", type=int, default=17841)
    parser.add_argument("--wol-port", type=int, default=9)
    args = parser.parse_args()
    reset()
    udp = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    udp.bind(("0.0.0.0", args.port))
    wol = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    wol.bind(("0.0.0.0", args.wol_port))
    server = ThreadingHTTPServer(("127.0.0.1", args.http_port), Control)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    stopped = threading.Event()
    signal.signal(signal.SIGTERM, lambda *_: stopped.set())
    print("MOCK_LAN_AGENT=READY", flush=True)
    try:
        while not stopped.is_set():
            for sock in select.select([udp, wol], [], [], 0.5)[0]:
                data, remote = sock.recvfrom(65535)
                if sock is udp:
                    handle_request(udp, remote, data)
                else:
                    handle_wake(data)
    finally:
        server.shutdown()
        udp.close()
        wol.close()


if __name__ == "__main__":
    main()
