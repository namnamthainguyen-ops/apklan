using System;
using System.Collections.Generic;
using System.Security.Cryptography;
using System.Text;
using System.Web.Script.Serialization;
using RoomTN20.Runtime;

internal static class RuntimeUnit
{
    private static readonly JavaScriptSerializer Json = new JavaScriptSerializer();
    private static string Hash(byte[] bytes) { using (SHA256 sha = SHA256.Create()) return BitConverter.ToString(sha.ComputeHash(bytes)).Replace("-", "").ToLowerInvariant(); }
    private static void Reject(Action action, string name) { try { action(); } catch { return; } throw new Exception("Expected rejection: " + name); }
    private static int Main()
    {
        try
        {
            string version = new string('a', 32);
            string id = new string('b', 64);
            string device = "2bdf5c5f-d305-4482-b563-b399b57ff0d5";
            string key = Convert.ToBase64String(new byte[32]);
            Dictionary<string, object> file = new Dictionary<string, object> { { "id", id }, { "sha256", id }, { "name", "poster.png" }, { "type", "image/png" }, { "length", 16L }, { "durationSeconds", 10 } };
            Dictionary<string, object> playlist = new Dictionary<string, object> { { "schema", 1 }, { "version", version }, { "files", new object[] { file } } };
            string playlistJson = Json.Serialize(playlist);
            Controller.ValidatePlaylistJson(playlistJson, version, 16);
            Reject(delegate { Controller.ValidatePlaylistJson(playlistJson, version, 17); }, "wrong total");
            file["type"] = "application/x-msdownload";
            Reject(delegate { Controller.ValidatePlaylistJson(Json.Serialize(playlist), version, 16); }, "executable content");
            file["type"] = "image/png";
            file["length"] = 134217729L;
            Reject(delegate { Controller.ValidatePlaylistJson(Json.Serialize(playlist), version, 134217729L); }, "oversized file");
            file["length"] = 16L;
            playlist["files"] = new object[] { file, file };
            Reject(delegate { Controller.ValidatePlaylistJson(Json.Serialize(playlist), version, 32); }, "duplicate hashes");
            playlist["files"] = new object[] { file };
            byte[] payload = Encoding.UTF8.GetBytes(Json.Serialize(playlist));
            long timestamp = 1800000000;
            string nonce = new string('c', 32);
            string canonical = "media\n" + device + "\n" + timestamp + "\n" + nonce + "\n" + version + "\n16\n" + Hash(payload);
            string signature;
            using (HMACSHA256 hmac = new HMACSHA256(Convert.FromBase64String(key))) signature = Convert.ToBase64String(hmac.ComputeHash(Encoding.UTF8.GetBytes(canonical)));
            Dictionary<string, object> header = new Dictionary<string, object> { { "deviceId", device }, { "timestamp", timestamp }, { "nonce", nonce }, { "mediaVersion", version }, { "totalBytes", 16L }, { "payload", Convert.ToBase64String(payload) }, { "signature", signature } };
            MediaUploadDescriptor valid = Controller.ReadMediaEnvelopeJson(Json.Serialize(header), key, device, timestamp);
            if (valid.Version != version || valid.TotalBytes != 16 || valid.Nonce != nonce) throw new Exception("Valid upload descriptor mismatch.");
            header["transferMode"] = "delta-v1";
            Reject(delegate { Controller.ReadMediaEnvelopeJson(Json.Serialize(header), key, device, timestamp); }, "unsigned delta mode");
            using (HMACSHA256 hmac = new HMACSHA256(Convert.FromBase64String(key))) header["signature"] = Convert.ToBase64String(hmac.ComputeHash(Encoding.UTF8.GetBytes(canonical + "\ndelta-v1")));
            if (Controller.ReadMediaEnvelopeJson(Json.Serialize(header), key, device, timestamp).TransferMode != "delta-v1") throw new Exception("Delta mode not preserved");
            header["transferMode"] = "unknown";
            Reject(delegate { Controller.ReadMediaEnvelopeJson(Json.Serialize(header), key, device, timestamp); }, "unknown delta mode");
            header.Remove("transferMode");
            header["signature"] = signature;
            Reject(delegate { Controller.ReadMediaEnvelopeJson(Json.Serialize(header), key, device, timestamp + 121); }, "expired timestamp");
            Reject(delegate { Controller.ReadMediaEnvelopeJson(Json.Serialize(header), key, "other-device", timestamp); }, "wrong device");
            header["totalBytes"] = 17L;
            Reject(delegate { Controller.ReadMediaEnvelopeJson(Json.Serialize(header), key, device, timestamp); }, "unsigned total alteration");
            header["totalBytes"] = 16L;
            header["payload"] = Convert.ToBase64String(Encoding.UTF8.GetBytes("{}"));
            Reject(delegate { Controller.ReadMediaEnvelopeJson(Json.Serialize(header), key, device, timestamp); }, "unsigned playlist alteration");
            Controller.ValidatePlaylistJson(Json.Serialize(new { schema = 1, version = version, files = new object[0] }), version, 0);
            Console.WriteLine("RUNTIME_PROTOCOL=PASS (signed envelope, device/time binding, tamper, MIME/size/hash limits, empty playlist; no listener, GUI, disk or OS changes)");
            return 0;
        }
        catch (Exception error) { Console.Error.WriteLine(error); return 1; }
    }
}
