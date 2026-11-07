using System;
using System.Collections.Generic;
using System.IO;
using System.Net;
using System.Net.Security;
using System.Net.Sockets;
using System.Security.Authentication;
using System.Security.Cryptography;
using System.Text;
using System.Web.Script.Serialization;
using RoomTN20.Runtime;

internal static class MediaTransferSmoke
{
    private static readonly JavaScriptSerializer Json = new JavaScriptSerializer();
    private static readonly byte[] Key = new byte[32]; // Disposable public fixture.
    private static string Id = "00000000-0000-4000-8000-000000000001";
    private static string Hash(byte[] bytes) { using (SHA256 sha = SHA256.Create()) return BitConverter.ToString(sha.ComputeHash(bytes)).Replace("-", "").ToLowerInvariant(); }
    private static string Sign(string value) { using (HMACSHA256 hmac = new HMACSHA256(Key)) return Convert.ToBase64String(hmac.ComputeHash(Encoding.UTF8.GetBytes(value))); }
    private static void Assert(bool value, string detail) { if (!value) throw new Exception(detail); }
    private static Dictionary<string, object> Send(int port, string pin, string version, byte[] declared, byte[] actual, string target)
    {
        string nonce = Guid.NewGuid().ToString("N");
        string hash = Hash(declared);
        byte[] payload = Encoding.UTF8.GetBytes(Json.Serialize(new { schema = 1, version = version,
            files = new[] { new { id = hash, name = "fixture.png", type = "image/png", length = declared.Length, sha256 = hash, durationSeconds = 10 } } }));
        long timestamp = DateTimeOffset.UtcNow.ToUnixTimeSeconds();
        string canonical = "media\n" + target + "\n" + timestamp + "\n" + nonce + "\n" + version + "\n" + declared.Length + "\n" + Hash(payload);
        byte[] header = Encoding.UTF8.GetBytes(Json.Serialize(new { deviceId = target, timestamp = timestamp, nonce = nonce, mediaVersion = version,
            totalBytes = declared.Length, payload = Convert.ToBase64String(payload), signature = Sign(canonical) }) + "\n");
        using (TcpClient tcp = new TcpClient("127.0.0.1", port))
        using (SslStream tls = new SslStream(tcp.GetStream(), false, delegate(object sender, System.Security.Cryptography.X509Certificates.X509Certificate cert,
            System.Security.Cryptography.X509Certificates.X509Chain chain, SslPolicyErrors errors) { return Hash(cert.GetRawCertData()) == pin; }))
        {
            tls.ReadTimeout = tls.WriteTimeout = 10000;
            tls.AuthenticateAsClient("RoomTN20", null, SslProtocols.Tls12, false);
            tls.Write(header, 0, header.Length); tls.Write(actual, 0, actual.Length); tls.Flush();
            MemoryStream bytes = new MemoryStream();
            for (int value; (value = tls.ReadByte()) != -1; ) { if (value == 10) break; bytes.WriteByte((byte)value); Assert(bytes.Length <= 32768, "Unbounded response"); }
            Dictionary<string, object> reply = (Dictionary<string, object>)Json.DeserializeObject(Encoding.UTF8.GetString(bytes.ToArray()));
            string expected = Sign("media-result\n" + reply["nonce"] + "\n" + reply["deviceId"] + "\n" + reply["version"] + "\n" + reply["status"] + "\n" + reply["message"]);
            Assert((string)reply["signature"] == expected, "Response signature mismatch");
            return reply;
        }
    }
    private static int Main(string[] args)
    {
        if (Environment.GetEnvironmentVariable("GITHUB_ACTIONS") != "true") throw new Exception("Disposable GitHub runner only");
        string root = args[0];
        TcpListener probe = new TcpListener(IPAddress.Loopback, 0); probe.Start(); int port = ((IPEndPoint)probe.LocalEndpoint).Port; probe.Stop();
        string fakePlayer = Path.Combine(root, "not-a-program.fixture");
        // A text fixture cannot be executed or display a user interface.
        File.WriteAllText(fakePlayer, "Not an executable program.");
        string data = Path.Combine(root, "data"); Directory.CreateDirectory(data);
        byte[] png = Convert.FromBase64String("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aX1sAAAAASUVORK5CYII=");
        using (Controller runtime = Controller.Start(data, Convert.ToBase64String(Key), Id, port, args[1], fakePlayer))
        {
            string pin = runtime.Snapshot().MediaCertSha256;
            string v1 = new string('1', 32), v2 = new string('2', 32), v3 = new string('3', 32);
            var first = Send(port, pin, v1, png, png, Id);
            Assert((string)first["status"] == "accepted", "First upload failed: " + first["message"]);
            Assert(runtime.Snapshot().MediaVersion == v1, "Published version mismatch");
            byte[] damaged = (byte[])png.Clone(); damaged[damaged.Length - 1] ^= 1;
            var corrupt = Send(port, pin, v2, png, damaged, Id);
            Assert((string)corrupt["status"] == "rejected", "Corrupt transfer accepted");
            Assert(runtime.Snapshot().MediaVersion == v1, "Corrupt transfer replaced good content");
            var second = Send(port, pin, v2, png, png, Id);
            Assert((string)second["status"] == "accepted", "Second upload failed: " + second["message"]);
            var retry = Send(port, pin, v2, png, png, Id);
            Assert((string)retry["status"] == "accepted", "Identical retry failed: " + retry["message"]);
            var wrong = Send(port, pin, v3, png, png, "00000000-0000-4000-8000-000000000099");
            Assert((string)wrong["status"] == "rejected", "Wrong device accepted");
            Assert(runtime.Snapshot().MediaVersion == v2, "Wrong device changed content");
            Assert(File.Exists(Path.Combine(data, "Media", v1, Hash(png) + ".png")), "Previous good version missing");
            Console.WriteLine("MEDIA_TLS_TRANSFER=PASS (actual TLS/pin, signed PNG upload, corruption rejection, atomic version, retained previous version, idempotent retry, device isolation; no GUI/UWF/power actions)");
        }
        return 0;
    }
}
