using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.IO.Pipes;
using System.Net;
using System.Net.Security;
using System.Net.Sockets;
using System.Runtime.InteropServices;
using System.Runtime.Versioning;
using System.Security.AccessControl;
using System.Security.Authentication;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Security.Principal;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;

[assembly: System.Reflection.AssemblyVersion("1.4.0.0")]
[assembly: System.Reflection.AssemblyFileVersion("1.4.0.0")]
[assembly: System.Reflection.AssemblyProduct("Room TN20")]
[assembly: System.Reflection.AssemblyCompany("Công ty TNHH TN20")]
[assembly: System.Reflection.AssemblyTitle("Room TN20 Runtime")]
[assembly: TargetFramework(".NETFramework,Version=v4.7.2")]

namespace RoomTN20.Runtime
{
    public sealed class RuntimeSnapshot
    {
        public string LockState { get; internal set; }
        public bool DesiredLocked { get; internal set; }
        public string MediaVersion { get; internal set; }
        public int MediaPort { get; internal set; }
        public string MediaCertSha256 { get; internal set; }
        public int PlayerCount { get; internal set; }
    }
    public sealed class MediaUploadDescriptor
    {
        public string Nonce { get; internal set; }
        public string Version { get; internal set; }
        public long TotalBytes { get; internal set; }
        public string PlaylistJson { get; internal set; }
        public string TransferMode { get; internal set; }
        internal Dictionary<string, object> Playlist;
    }

    public sealed class Controller : IDisposable
    {
        private readonly string root;
        private readonly string deviceId;
        private readonly string playerPath;
        private readonly byte[] key;
        private readonly X509Certificate2 certificate;
        private readonly TcpListener listener;
        private readonly object gate = new object();
        private readonly object uploadGate = new object();
        private readonly List<PlayerConnection> players = new List<PlayerConnection>();
        private readonly Dictionary<string, long> nonces = new Dictionary<string, long>(StringComparer.Ordinal);
        private readonly Timer timer;
        private NamedPipeServerStream waitingPipe;
        private volatile bool disposed;
        private bool desiredLocked;
        private string generation = Guid.NewGuid().ToString("N");
        private DateTime lastLaunch = DateTime.MinValue;
        private readonly int port;
        private readonly string pin;
        private int mediaWorkers;
        private int securedItems;
        private string playerIssue = "PLAYER_NOT_CONNECTED";

        public static Controller Start(string dataRoot, string keyBase64, string deviceId, int port, string certificatePath, string playerExePath)
        {
            return new Controller(dataRoot, keyBase64, deviceId, port, certificatePath, playerExePath);
        }
        private Controller(string dataRoot, string keyBase64, string id, int mediaPort, string certificatePath, string playerExePath)
        {
            root = DataFiles.CanonicalRoot(dataRoot);
            if (!Directory.Exists(root)) throw new InvalidOperationException("RUNTIME_DATA_ROOT_MISSING");
            key = Convert.FromBase64String(keyBase64);
            if (key.Length != 32) throw new InvalidOperationException("RUNTIME_KEY_INVALID");
            Guid parsed;
            if (!Guid.TryParse(id, out parsed)) throw new InvalidOperationException("RUNTIME_DEVICE_ID_INVALID");
            if (mediaPort < 1024 || mediaPort > 65535) throw new InvalidOperationException("RUNTIME_PORT_INVALID");
            deviceId = id;
            playerPath = Path.GetFullPath(playerExePath);
            DataFiles.RejectReparse(playerPath);
            DataFiles.RejectReparse(certificatePath);
            if (!File.Exists(playerPath)) throw new InvalidOperationException("RUNTIME_PLAYER_MISSING");
            foreach (string directory in new string[] { root, Path.Combine(root, "Media"), Path.Combine(root, "State") })
            {
                DataFiles.RejectReparse(directory);
                Directory.CreateDirectory(directory);
                SecureData(directory);
            }
            foreach (FileSystemInfo entry in new DirectoryInfo(root).GetFileSystemInfos())
                if (!(entry is DirectoryInfo) || (entry.Name != "Media" && entry.Name != "State")) throw new InvalidOperationException("RUNTIME_DATA_ROOT_CONTAINS_UNRELATED_ITEMS");
            SecureTree(root, 0);
            certificate = new X509Certificate2(certificatePath, "", X509KeyStorageFlags.MachineKeySet | X509KeyStorageFlags.PersistKeySet);
            if (!certificate.HasPrivateKey) throw new InvalidOperationException("RUNTIME_CERTIFICATE_HAS_NO_PRIVATE_KEY");
            pin = DataFiles.Hash(certificate.RawData);
            port = mediaPort;
            string statePath = Path.Combine(root, "State", "lock.json");
            DataFiles.RejectReparse(statePath);
            if (File.Exists(statePath))
            {
                Dictionary<string, object> state = DataFiles.Object(File.ReadAllText(statePath));
                object locked;
                if (!state.TryGetValue("locked", out locked) || !(locked is bool)) throw new InvalidOperationException("RUNTIME_LOCK_STATE_INVALID");
                desiredLocked = (bool)locked;
            }
            listener = new TcpListener(IPAddress.Any, port);
            listener.Start(4);
            ThreadPool.QueueUserWorkItem(delegate { AcceptMedia(); });
            ThreadPool.QueueUserWorkItem(delegate { AcceptPlayers(); });
            timer = new Timer(delegate { Tick(); }, null, 1000, 3000);
        }

        private static void SecureData(string path)
        {
            DirectorySecurity acl = new DirectorySecurity();
            SecurityIdentifier administrators = new SecurityIdentifier(WellKnownSidType.BuiltinAdministratorsSid, null);
            acl.SetOwner(administrators);
            acl.SetAccessRuleProtection(true, false);
            foreach (SecurityIdentifier sid in new SecurityIdentifier[] { administrators, new SecurityIdentifier(WellKnownSidType.LocalSystemSid, null) })
                acl.AddAccessRule(new FileSystemAccessRule(sid, FileSystemRights.FullControl, InheritanceFlags.ContainerInherit | InheritanceFlags.ObjectInherit, PropagationFlags.None, AccessControlType.Allow));
            acl.AddAccessRule(new FileSystemAccessRule(new SecurityIdentifier(WellKnownSidType.BuiltinUsersSid, null), FileSystemRights.ReadAndExecute,
                InheritanceFlags.ContainerInherit | InheritanceFlags.ObjectInherit, PropagationFlags.None, AccessControlType.Allow));
            Directory.SetAccessControl(path, acl);
        }
        private void SecureTree(string path, int depth)
        {
            if (depth > 8 || ++securedItems > 10000) throw new InvalidOperationException("RUNTIME_DATA_TREE_LIMIT_EXCEEDED");
            DataFiles.RejectReparse(path);
            SecureData(path);
            foreach (FileSystemInfo item in new DirectoryInfo(path).GetFileSystemInfos())
            {
                if ((item.Attributes & FileAttributes.ReparsePoint) != 0) throw new InvalidOperationException("RUNTIME_DATA_REPARSE_REJECTED");
                if (item is DirectoryInfo) { SecureTree(item.FullName, depth + 1); continue; }
                if (++securedItems > 10000) throw new InvalidOperationException("RUNTIME_DATA_TREE_LIMIT_EXCEEDED");
                FileSecurity acl = new FileSecurity();
                SecurityIdentifier administrators = new SecurityIdentifier(WellKnownSidType.BuiltinAdministratorsSid, null);
                acl.SetOwner(administrators);
                acl.SetAccessRuleProtection(true, false);
                foreach (SecurityIdentifier sid in new SecurityIdentifier[] { administrators, new SecurityIdentifier(WellKnownSidType.LocalSystemSid, null) })
                    acl.AddAccessRule(new FileSystemAccessRule(sid, FileSystemRights.FullControl, AccessControlType.Allow));
                acl.AddAccessRule(new FileSystemAccessRule(new SecurityIdentifier(WellKnownSidType.BuiltinUsersSid, null), FileSystemRights.ReadAndExecute, AccessControlType.Allow));
                File.SetAccessControl(item.FullName, acl);
            }
        }
        public RuntimeSnapshot Snapshot()
        {
            lock (gate)
            {
                uint session = WTSGetActiveConsoleSessionId();
                PlayerConnection current = players.Find(delegate(PlayerConnection player) { return player.SessionId == session; });
                return new RuntimeSnapshot { DesiredLocked = desiredLocked, LockState = current == null || current.Unavailable || (DateTime.UtcNow - current.LastAckUtc).TotalSeconds > 10 ? "ui-offline" :
                    current.Generation != generation ? "pending" : current.ReportedLocked ? "locked" : "unlocked",
                    MediaVersion = DataFiles.CurrentVersion(root), MediaPort = port, MediaCertSha256 = pin, PlayerCount = players.Count };
            }
        }
        public string SetLocked(bool locked)
        {
            string requestGeneration;
            lock (gate)
            {
                DataFiles.AtomicText(Path.Combine(root, "State", "lock.json"), DataFiles.Json.Serialize(new { locked = locked, changedUtc = DateTime.UtcNow.ToString("o") }));
                desiredLocked = locked;
                generation = Guid.NewGuid().ToString("N");
                requestGeneration = generation;
                Broadcast();
            }
            EnsurePlayer(true);
            DateTime deadline = DateTime.UtcNow.AddSeconds(8);
            while (DateTime.UtcNow < deadline)
            {
                lock (gate)
                {
                    if (generation != requestGeneration) throw new InvalidOperationException("LOCK_REQUEST_SUPERSEDED");
                    uint session = WTSGetActiveConsoleSessionId();
                    PlayerConnection current = players.Find(delegate(PlayerConnection player) { return player.SessionId == session; });
                    if (current != null && !current.Unavailable && current.Generation == requestGeneration && current.ReportedLocked == locked)
                        return locked ? "Room TN20 display locked; the interactive player confirmed the screen." : "Room TN20 display unlocked; the interactive player confirmed the screen.";
                }
                Thread.Sleep(100);
            }
            uint active = WTSGetActiveConsoleSessionId();
            if(active==0 || active==UInt32.MaxValue) throw new InvalidOperationException("LOCK_NO_CONSOLE_USER: No signed-in console session is available. Desired state was saved.");
            lock(gate) throw new InvalidOperationException("LOCK_PLAYER_UNCONFIRMED: session=" + active + "; " + playerIssue + ". Desired state saved; display has not confirmed.");
        }
        private void Tick()
        {
            if (disposed) return;
            try
            {
                string recovery = Path.Combine(root, "State", "recovery-unlock.json");
                DataFiles.RejectReparse(recovery);
                if (File.Exists(recovery))
                {
                    lock (gate)
                    {
                        DataFiles.AtomicText(Path.Combine(root, "State", "lock.json"), DataFiles.Json.Serialize(new { locked = false, changedUtc = DateTime.UtcNow.ToString("o"), recovery = true }));
                        desiredLocked = false;
                        generation = Guid.NewGuid().ToString("N");
                        File.Delete(recovery);
                        Broadcast();
                    }
                }
                EnsurePlayer();
                lock (gate) Broadcast();
            }
            catch { /* No system lock/reboot fallback. Desired state remains recoverable. */ }
        }
        private void EnsurePlayer(bool force = false)
        {
            lock (gate)
            {
                uint session = WTSGetActiveConsoleSessionId();
                if (disposed || session == UInt32.MaxValue || session == 0 || players.Exists(delegate(PlayerConnection player) { return player.SessionId == session; }) ||
                    (!force && (DateTime.UtcNow - lastLaunch).TotalSeconds < 30)) return;
                lastLaunch = DateTime.UtcNow;
                LaunchInteractivePlayer(session);
            }
        }
        private void LaunchInteractivePlayer(uint session)
        {
            IntPtr token;
            if (!WTSQueryUserToken(session, out token)) { playerIssue = "USER_TOKEN_FAILED error=" + Marshal.GetLastWin32Error(); return; }
            IntPtr environment = IntPtr.Zero;
            PROCESS_INFORMATION process = new PROCESS_INFORMATION();
            try
            {
                bool hasEnvironment = CreateEnvironmentBlock(out environment, token, false);
                STARTUPINFO start = new STARTUPINFO();
                start.cb = Marshal.SizeOf(typeof(STARTUPINFO));
                start.lpDesktop = @"winsta0\default";
                string command = "\"" + playerPath + "\" --data-root \"" + root + "\"";
                bool started = CreateProcessAsUser(token, playerPath, new StringBuilder(command), IntPtr.Zero, IntPtr.Zero, false,
                    hasEnvironment ? 0x00000400 : 0, environment, Path.GetDirectoryName(playerPath), ref start, out process);
                playerIssue = started ? "PLAYER_STARTED_WAITING_FOR_CONNECTION" : "PLAYER_START_FAILED error=" + Marshal.GetLastWin32Error();
            }
            finally
            {
                if (environment != IntPtr.Zero) DestroyEnvironmentBlock(environment);
                if (process.hProcess != IntPtr.Zero) CloseHandle(process.hProcess);
                if (process.hThread != IntPtr.Zero) CloseHandle(process.hThread);
                CloseHandle(token);
            }
        }
        private void Broadcast()
        {
            string message = DataFiles.Json.Serialize(new { type = "state", generation = generation, locked = desiredLocked, mediaVersion = DataFiles.CurrentVersion(root) });
            foreach (PlayerConnection player in players.ToArray())
            {
                if (Interlocked.CompareExchange(ref player.WriterBusy, 1, 0) != 0) continue;
                PlayerConnection target = player;
                ThreadPool.QueueUserWorkItem(delegate
                {
                    try
                    {
                        if (!target.Writer.WriteLineAsync(message).Wait(2000) || !target.Writer.FlushAsync().Wait(2000)) throw new IOException("PLAYER_PIPE_TIMEOUT");
                    }
                    catch { target.Pipe.Dispose(); lock (gate) players.Remove(target); }
                    finally { Interlocked.Exchange(ref target.WriterBusy, 0); }
                });
            }
        }
        private void AcceptPlayers()
        {
            while (!disposed)
            {
                NamedPipeServerStream pipe = null;
                try
                {
                    PipeSecurity security = new PipeSecurity();
                    security.SetAccessRuleProtection(true, false);
                    security.AddAccessRule(new PipeAccessRule(new SecurityIdentifier(WellKnownSidType.LocalSystemSid, null), PipeAccessRights.FullControl, AccessControlType.Allow));
                    security.AddAccessRule(new PipeAccessRule(new SecurityIdentifier(WellKnownSidType.BuiltinAdministratorsSid, null), PipeAccessRights.FullControl, AccessControlType.Allow));
                    security.SetOwner(new SecurityIdentifier(WellKnownSidType.LocalSystemSid, null));
                    DataFiles.GrantDisplayAccess(security);
                    pipe = new NamedPipeServerStream(DataFiles.PipeName(root), PipeDirection.InOut, 32, PipeTransmissionMode.Byte, PipeOptions.Asynchronous, 4096, 4096, security);
                    waitingPipe = pipe;
                    pipe.WaitForConnection();
                    NamedPipeServerStream connected = pipe;
                    ThreadPool.QueueUserWorkItem(delegate { HandlePlayer(connected); });
                    pipe = null;
                }
                catch { if (!disposed) Thread.Sleep(1000); }
                finally { if (pipe != null) pipe.Dispose(); }
            }
        }
        private void HandlePlayer(NamedPipeServerStream pipe)
        {
            PlayerConnection player = null;
            try
            {
                uint processId;
                if (!GetNamedPipeClientProcessId(pipe.SafePipeHandle.DangerousGetHandle(), out processId)) throw new InvalidOperationException("PLAYER_IDENTITY_UNAVAILABLE");
                Process process = Process.GetProcessById((int)processId);
                uint session = (uint)process.SessionId;
                string image = ProcessImage((int)processId);
                process.Dispose();
                if (session == 0 || !String.Equals(image, playerPath, StringComparison.OrdinalIgnoreCase)) throw new InvalidOperationException("PLAYER_IDENTITY_REJECTED");
                player = new PlayerConnection { Pipe = pipe, SessionId = session, Writer = new StreamWriter(pipe, new UTF8Encoding(false), 4096, true) };
                lock (gate)
                {
                    foreach (PlayerConnection previous in players.FindAll(delegate(PlayerConnection value) { return value.SessionId == session; })) { previous.Pipe.Dispose(); players.Remove(previous); }
                    players.Add(player);
                    playerIssue = "PLAYER_CONNECTED_WAITING_FOR_SCREEN_ACK";
                    Broadcast();
                }
                while (!disposed && pipe.IsConnected)
                {
                    string line = ReadLine(pipe, 4096);
                    Dictionary<string, object> acknowledgement = DataFiles.Object(line);
                    string reported = DataFiles.Text(acknowledgement, "state");
                    if (reported != "locked" && reported != "unlocked" && reported != "ui-offline") continue;
                    lock (gate)
                    {
                        player.Generation = DataFiles.Text(acknowledgement, "generation");
                        player.ReportedLocked = reported == "locked";
                        player.Unavailable = reported == "ui-offline";
                        player.LastAckUtc = DateTime.UtcNow;
                    }
                }
            }
            catch { }
            finally { lock (gate) { if (player != null) players.Remove(player); } pipe.Dispose(); }
        }
        private void AcceptMedia()
        {
            while (!disposed)
            {
                try
                {
                    TcpClient client = listener.AcceptTcpClient();
                    if (Interlocked.Increment(ref mediaWorkers) > 4) { Interlocked.Decrement(ref mediaWorkers); client.Close(); continue; }
                    ThreadPool.QueueUserWorkItem(delegate { try { HandleMedia(client); } catch { client.Close(); } finally { Interlocked.Decrement(ref mediaWorkers); } });
                }
                catch { if (!disposed) Thread.Sleep(1000); }
            }
        }
        private void HandleMedia(TcpClient client)
        {
            using (client)
            {
                client.ReceiveTimeout = client.SendTimeout = 60000;
                using (SslStream stream = new SslStream(client.GetStream(), false))
                {
                    string nonce = "";
                    string version = "none";
                    string staging = null;
                    bool acquired = false;
                    try
                    {
                        stream.ReadTimeout = stream.WriteTimeout = 60000;
                        stream.AuthenticateAsServer(certificate, false, SslProtocols.Tls12, false);
                        long now = DateTimeOffset.UtcNow.ToUnixTimeSeconds();
                        MediaUploadDescriptor upload = ReadMediaEnvelopeJson(ReadLine(stream, 32768), Convert.ToBase64String(key), deviceId, now);
                        nonce = upload.Nonce;
                        version = upload.Version;
                        long total = upload.TotalBytes;
                        byte[] payload = Encoding.UTF8.GetBytes(upload.PlaylistJson);
                        lock (nonces)
                        {
                            foreach (string used in new List<string>(nonces.Keys)) if (nonces[used] < now - 600) nonces.Remove(used);
                            if (nonces.ContainsKey(nonce)) throw new InvalidOperationException("MEDIA_NONCE_REPLAYED");
                            if (nonces.Count >= 4096) throw new InvalidOperationException("MEDIA_AUTHENTICATED_REQUEST_LIMIT");
                            nonces[nonce] = now;
                        }
                        Dictionary<string, object> playlist = upload.Playlist;
                        acquired = Monitor.TryEnter(uploadGate, 5000);
                        if (!acquired) throw new InvalidOperationException("MEDIA_UPLOAD_BUSY");
                        string mediaRoot = Path.Combine(root, "Media");
                        DataFiles.RejectReparse(mediaRoot);
                        PruneVersions(mediaRoot);
                        DriveInfo drive = new DriveInfo(Path.GetPathRoot(root));
                        if (drive.AvailableFreeSpace < total + 67108864) throw new InvalidOperationException("MEDIA_DISK_SPACE_LOW");
                        staging = Path.Combine(mediaRoot, ".staging-" + Guid.NewGuid().ToString("N"));
                        Directory.CreateDirectory(staging);
                        HashSet<string> missing = null;
                        if (upload.TransferMode == "delta-v1") missing = PrepareMediaSync(stream, mediaRoot, staging, playlist, nonce, version);
                        foreach (object item in DataFiles.Items(playlist, "files"))
                        {
                            Dictionary<string, object> metadata = (Dictionary<string, object>)item;
                            if (missing == null || missing.Contains(DataFiles.Text(metadata, "id"))) ReceiveFile(stream, staging, metadata);
                        }
                        File.WriteAllBytes(Path.Combine(staging, "playlist.json"), payload);
                        string destination = Path.Combine(mediaRoot, version);
                        if (Directory.Exists(destination))
                        {
                            DataFiles.RejectReparse(destination);
                            string existing = Path.Combine(destination, "playlist.json");
                            DataFiles.RejectReparse(existing);
                            if (!File.Exists(existing) || DataFiles.Hash(File.ReadAllBytes(existing)) != DataFiles.Hash(payload)) throw new InvalidOperationException("MEDIA_VERSION_CONFLICT");
                            foreach (object item in DataFiles.Items(playlist, "files"))
                            {
                                Dictionary<string, object> metadata = (Dictionary<string, object>)item;
                                string stored = Path.Combine(destination, DataFiles.Text(metadata, "id") + DataFiles.Extension(DataFiles.Text(metadata, "type")));
                                DataFiles.RejectReparse(stored);
                                using (FileStream file = File.OpenRead(stored))
                                using (SHA256 hash = SHA256.Create())
                                    if (file.Length != DataFiles.Number(metadata, "length") || DataFiles.Hex(hash.ComputeHash(file)) != DataFiles.Text(metadata, "sha256")) throw new InvalidOperationException("MEDIA_EXISTING_CONTENT_MISMATCH");
                            }
                            RemoveStaging(staging);
                        }
                        else Directory.Move(staging, destination);
                        staging = null;
                        string previousVersion = DataFiles.CurrentVersion(root);
                        if (previousVersion == version) previousVersion = PreviousVersion(mediaRoot);
                        DataFiles.AtomicText(Path.Combine(mediaRoot, "current.json"), DataFiles.Json.Serialize(new { version = version, previousVersion = previousVersion }));
                        lock (gate) Broadcast();
                        WriteMediaResult(stream, nonce, version, "accepted", "Playlist verified and stored. The display will use it when locked.");
                        PruneVersions(mediaRoot);
                    }
                    catch (Exception error)
                    {
                        try { WriteMediaResult(stream, nonce, version, "rejected", SafeMessage(error.Message)); } catch { }
                    }
                    finally
                    {
                        if (staging != null) { try { RemoveStaging(staging); } catch { } }
                        if (acquired) Monitor.Exit(uploadGate);
                    }
                }
            }
        }
        public static void ValidatePlaylistJson(string json, string version, long totalBytes) { ValidatePlaylist(DataFiles.Object(json), version, totalBytes); }
        public static MediaUploadDescriptor ReadMediaEnvelopeJson(string headerJson, string keyBase64, string expectedDeviceId, long unixNow)
        {
            if (Encoding.UTF8.GetByteCount(headerJson) > 32768) throw new InvalidOperationException("MEDIA_HEADER_TOO_LARGE");
            Dictionary<string, object> header = DataFiles.Object(headerJson);
            string target = DataFiles.Text(header, "deviceId");
            string nonce = DataFiles.Text(header, "nonce");
            string version = DataFiles.Text(header, "mediaVersion");
            long timestamp = DataFiles.Number(header, "timestamp");
            long total = DataFiles.Number(header, "totalBytes");
            if (target != expectedDeviceId || !Regex.IsMatch(nonce, @"\A[0-9a-f]{32}\z") || !Regex.IsMatch(version, @"\A[0-9a-f]{32}\z") || total < 0 || total > 268435456) throw new InvalidOperationException("MEDIA_HEADER_INVALID");
            if (timestamp < unixNow - 120 || timestamp > unixNow + 120) throw new InvalidOperationException("MEDIA_TIMESTAMP_EXPIRED");
            byte[] payload = Convert.FromBase64String(DataFiles.Text(header, "payload"));
            if (payload.Length > 24000) throw new InvalidOperationException("MEDIA_PLAYLIST_TOO_LARGE");
            string canonical = "media\n" + target + "\n" + timestamp.ToString(System.Globalization.CultureInfo.InvariantCulture) + "\n" + nonce + "\n" + version + "\n" + total.ToString(System.Globalization.CultureInfo.InvariantCulture) + "\n" + DataFiles.Hash(payload);
            string mode = header.ContainsKey("transferMode") ? DataFiles.Text(header, "transferMode") : "";
            if (mode != "" && mode != "delta-v1") throw new InvalidOperationException("MEDIA_TRANSFER_MODE_UNSUPPORTED");
            if (mode != "") canonical += "\n" + mode;
            byte[] signingKey = Convert.FromBase64String(keyBase64);
            byte[] expected;
            if (signingKey.Length != 32) throw new InvalidOperationException("MEDIA_KEY_INVALID");
            using (HMACSHA256 hmac = new HMACSHA256(signingKey)) expected = hmac.ComputeHash(Encoding.UTF8.GetBytes(canonical));
            Array.Clear(signingKey, 0, signingKey.Length);
            byte[] actual = Convert.FromBase64String(DataFiles.Text(header, "signature"));
            int difference = expected.Length ^ actual.Length;
            for (int index = 0; index < expected.Length && index < actual.Length; index++) difference |= expected[index] ^ actual[index];
            if (difference != 0) throw new InvalidOperationException("MEDIA_AUTHENTICATION_FAILED");
            string json = new UTF8Encoding(false, true).GetString(payload);
            Dictionary<string, object> playlist = DataFiles.Object(json);
            ValidatePlaylist(playlist, version, total);
            return new MediaUploadDescriptor { Nonce = nonce, Version = version, TotalBytes = total, PlaylistJson = json, Playlist = playlist, TransferMode = mode };
        }
        private HashSet<string> PrepareMediaSync(Stream output, string mediaRoot, string staging, Dictionary<string, object> playlist, string nonce, string version)
        {
            HashSet<string> missing = new HashSet<string>(StringComparer.Ordinal);
            HashSet<string> wanted = new HashSet<string>(StringComparer.Ordinal);
            string current = DataFiles.CurrentVersion(root);
            string[] sources = new string[] { current, PreviousVersion(mediaRoot) };
            foreach (object value in DataFiles.Items(playlist, "files"))
            {
                Dictionary<string, object> metadata = (Dictionary<string, object>)value;
                string id = DataFiles.Text(metadata, "id");
                wanted.Add(id);
                string fileName = id + DataFiles.Extension(DataFiles.Text(metadata, "type"));
                string destination = Path.Combine(staging, fileName);
                bool reused = false;
                foreach (string sourceVersion in sources)
                {
                    if (!Regex.IsMatch(sourceVersion, @"\A[0-9a-f]{32}\z")) continue;
                    string directory = Path.Combine(mediaRoot, sourceVersion);
                    string source = Path.Combine(directory, fileName);
                    DataFiles.RejectReparse(directory);
                    DataFiles.RejectReparse(source);
                    if (!File.Exists(source)) continue;
                    // Verify the staged copy, not a filename or a stale cache index.
                    File.Copy(source, destination, false);
                    using (FileStream file = File.OpenRead(destination))
                    using (SHA256 hash = SHA256.Create())
                        reused = file.Length == DataFiles.Number(metadata, "length") && DataFiles.Hex(hash.ComputeHash(file)) == id;
                    if (reused) break;
                    File.Delete(destination);
                }
                if (!reused) missing.Add(id);
            }
            List<object> removed = new List<object>();
            if (Regex.IsMatch(current, @"\A[0-9a-f]{32}\z"))
            {
                string previousPlaylist = Path.Combine(mediaRoot, current, "playlist.json");
                DataFiles.RejectReparse(previousPlaylist);
                Dictionary<string, object> previous = DataFiles.Object(File.ReadAllText(previousPlaylist));
                if (DataFiles.Items(previous, "files").Length > 20) throw new InvalidOperationException("MEDIA_PREVIOUS_PLAYLIST_INVALID");
                foreach (object value in DataFiles.Items(previous, "files"))
                {
                    Dictionary<string, object> metadata = (Dictionary<string, object>)value;
                    if (!wanted.Contains(DataFiles.Text(metadata, "id"))) removed.Add(new { id = DataFiles.Text(metadata, "id"), name = DataFiles.Text(metadata, "name") });
                }
            }
            byte[] plan = Encoding.UTF8.GetBytes(DataFiles.Json.Serialize(new { missing = new List<string>(missing), removed = removed }));
            string signature = Sign("media-plan\n" + nonce + "\n" + deviceId + "\n" + version + "\n" + DataFiles.Hash(plan));
            byte[] line = Encoding.UTF8.GetBytes(DataFiles.Json.Serialize(new { type = "media-plan", nonce = nonce, deviceId = deviceId, version = version, payload = Convert.ToBase64String(plan), signature = signature }) + "\n");
            output.Write(line, 0, line.Length);
            output.Flush();
            return missing;
        }
        private static void ValidatePlaylist(Dictionary<string, object> playlist, string version, long totalBytes)
        {
            if (!Regex.IsMatch(version, @"\A[0-9a-f]{32}\z") || DataFiles.Number(playlist, "schema") != 1 || DataFiles.Text(playlist, "version") != version) throw new InvalidOperationException("MEDIA_PLAYLIST_VERSION_INVALID");
            object[] files = DataFiles.Items(playlist, "files");
            if (files.Length > 20) throw new InvalidOperationException("MEDIA_TOO_MANY_FILES");
            long sum = 0;
            HashSet<string> ids = new HashSet<string>(StringComparer.Ordinal);
            foreach (object value in files)
            {
                Dictionary<string, object> file = value as Dictionary<string, object>;
                if (file == null) throw new InvalidOperationException("MEDIA_FILE_INVALID");
                string id = DataFiles.Text(file, "id");
                string name = DataFiles.Text(file, "name");
                string type = DataFiles.Text(file, "type");
                if (!Regex.IsMatch(id, @"\A[0-9a-f]{64}\z") || DataFiles.Text(file, "sha256") != id || !ids.Add(id)) throw new InvalidOperationException("MEDIA_FILE_HASH_INVALID_OR_DUPLICATE");
                if (name.Length > 160 || name.IndexOfAny(new char[] { '\r', '\n', '\0' }) >= 0) throw new InvalidOperationException("MEDIA_DISPLAY_NAME_INVALID");
                DataFiles.Extension(type);
                long length = DataFiles.Number(file, "length");
                long duration = DataFiles.Number(file, "durationSeconds");
                if (length <= 0 || length > 134217728 || duration < 0 || duration > 3600 || (type != "video/mp4" && duration < 1)) throw new InvalidOperationException("MEDIA_FILE_LIMIT_INVALID");
                sum = checked(sum + length);
            }
            if (sum != totalBytes || totalBytes < 0 || totalBytes > 268435456) throw new InvalidOperationException("MEDIA_TOTAL_LENGTH_INVALID");
        }
        private static void ReceiveFile(Stream input, string staging, Dictionary<string, object> metadata)
        {
            string id = DataFiles.Text(metadata, "id");
            string path = Path.Combine(staging, id + DataFiles.Extension(DataFiles.Text(metadata, "type")));
            long remaining = DataFiles.Number(metadata, "length");
            using (SHA256 hash = SHA256.Create())
            using (FileStream output = new FileStream(path, FileMode.CreateNew, FileAccess.Write, FileShare.None))
            {
                byte[] buffer = new byte[65536];
                while (remaining > 0)
                {
                    int read = input.Read(buffer, 0, (int)Math.Min(buffer.Length, remaining));
                    if (read == 0) throw new EndOfStreamException("MEDIA_UPLOAD_INCOMPLETE");
                    output.Write(buffer, 0, read);
                    hash.TransformBlock(buffer, 0, read, null, 0);
                    remaining -= read;
                }
                hash.TransformFinalBlock(new byte[0], 0, 0);
                if (DataFiles.Hex(hash.Hash) != id) throw new InvalidOperationException("MEDIA_CONTENT_HASH_MISMATCH");
            }
            byte[] magic = new byte[12];
            using (FileStream file = File.OpenRead(path)) file.Read(magic, 0, magic.Length);
            string type = DataFiles.Text(metadata, "type");
            bool valid = type == "image/jpeg" ? magic[0] == 255 && magic[1] == 216 && magic[2] == 255 : type == "image/png" ?
                magic[0] == 137 && magic[1] == 80 && magic[2] == 78 && magic[3] == 71 && magic[4] == 13 && magic[5] == 10 && magic[6] == 26 && magic[7] == 10 :
                magic[4] == 102 && magic[5] == 116 && magic[6] == 121 && magic[7] == 112;
            if (!valid) throw new InvalidOperationException("MEDIA_FILE_FORMAT_INVALID");
        }
        private static void RemoveStaging(string path)
        {
            if (!Regex.IsMatch(Path.GetFileName(path), @"\A\.staging-[0-9a-f]{32}\z")) throw new InvalidOperationException("UNSAFE_STAGING_CLEANUP");
            DataFiles.RejectReparse(path);
            if (Directory.GetDirectories(path).Length != 0) throw new InvalidOperationException("UNSAFE_STAGING_CHILD");
            foreach (string file in Directory.GetFiles(path))
            {
                if (!Regex.IsMatch(Path.GetFileName(file), @"\A(?:[0-9a-f]{64}\.(?:jpg|png|mp4)|playlist\.json)\z")) throw new InvalidOperationException("UNSAFE_STAGING_FILE");
                DataFiles.RejectReparse(file);
                File.Delete(file);
            }
            Directory.Delete(path, false);
        }
        private static string PreviousVersion(string mediaRoot)
        {
            try
            {
                string pointer = Path.Combine(mediaRoot, "current.json");
                DataFiles.RejectReparse(pointer);
                string previous = DataFiles.Text(DataFiles.Object(File.ReadAllText(pointer)), "previousVersion");
                return Regex.IsMatch(previous, @"\A[0-9a-f]{32}\z") ? previous : "none";
            }
            catch { return "none"; }
        }
        private void PruneVersions(string mediaRoot)
        {
            string current = DataFiles.CurrentVersion(root);
            string previous = PreviousVersion(mediaRoot);
            foreach (string directory in Directory.GetDirectories(mediaRoot))
            {
                string version = Path.GetFileName(directory);
                if (!Regex.IsMatch(version, @"\A[0-9a-f]{32}\z") || version == current || version == previous) continue;
                try
                {
                    DataFiles.RejectReparse(directory);
                    if (Directory.GetDirectories(directory).Length != 0) continue;
                    string[] files = Directory.GetFiles(directory);
                    bool valid = true;
                    foreach (string file in files)
                    {
                        if (!Regex.IsMatch(Path.GetFileName(file), @"\A(?:[0-9a-f]{64}\.(?:jpg|png|mp4)|playlist\.json)\z") ||
                            (File.GetAttributes(file) & FileAttributes.ReparsePoint) != 0) { valid = false; break; }
                    }
                    if (!valid) continue;
                    foreach (string file in files) File.Delete(file);
                    Directory.Delete(directory, false);
                }
                catch { /* A video still open by a player is retried on a later upload. */ }
            }
        }
        private string Sign(string canonical) { using (HMACSHA256 hmac = new HMACSHA256(key)) return Convert.ToBase64String(hmac.ComputeHash(Encoding.UTF8.GetBytes(canonical))); }
        private bool Verify(string canonical, string signature)
        {
            byte[] left;
            try { left = Convert.FromBase64String(signature); } catch { return false; }
            byte[] right = Convert.FromBase64String(Sign(canonical));
            if (left.Length != right.Length) return false;
            int difference = 0;
            for (int index = 0; index < left.Length; index++) difference |= left[index] ^ right[index];
            return difference == 0;
        }
        private void WriteMediaResult(Stream output, string nonce, string version, string status, string message)
        {
            string signature = Sign("media-result\n" + nonce + "\n" + deviceId + "\n" + version + "\n" + status + "\n" + message);
            byte[] bytes = Encoding.UTF8.GetBytes(DataFiles.Json.Serialize(new { nonce = nonce, deviceId = deviceId, version = version, status = status, message = message, signature = signature }) + "\n");
            output.Write(bytes, 0, bytes.Length);
            output.Flush();
        }
        private static string SafeMessage(string value) { return value.Length > 240 ? value.Substring(0, 240) : value; }
        private static string ReadLine(Stream stream, int maximum)
        {
            using (MemoryStream line = new MemoryStream())
            {
                while (line.Length <= maximum)
                {
                    int value = stream.ReadByte();
                    if (value < 0) throw new EndOfStreamException();
                    if (value == 10) return new UTF8Encoding(false, true).GetString(line.ToArray()).TrimEnd('\r');
                    line.WriteByte((byte)value);
                }
                throw new InvalidOperationException("PROTOCOL_HEADER_TOO_LARGE");
            }
        }
        public void Dispose()
        {
            disposed = true;
            timer.Dispose();
            listener.Stop();
            if (waitingPipe != null) waitingPipe.Dispose();
            lock (gate) { foreach (PlayerConnection player in players) player.Pipe.Dispose(); players.Clear(); }
            certificate.Dispose();
            Array.Clear(key, 0, key.Length);
        }
        private sealed class PlayerConnection
        {
            internal NamedPipeServerStream Pipe;
            internal StreamWriter Writer;
            internal uint SessionId;
            internal string Generation = "";
            internal bool ReportedLocked;
            internal bool Unavailable = true;
            internal DateTime LastAckUtc = DateTime.MinValue;
            internal int WriterBusy;
        }
        private static string ProcessImage(int processId)
        {
            IntPtr process = OpenProcess(0x1000, false, processId);
            if (process == IntPtr.Zero) throw new InvalidOperationException("PLAYER_PROCESS_UNAVAILABLE");
            try
            {
                StringBuilder path = new StringBuilder(32768);
                int length = path.Capacity;
                if (!QueryFullProcessImageName(process, 0, path, ref length)) throw new InvalidOperationException("PLAYER_PATH_UNAVAILABLE");
                return Path.GetFullPath(path.ToString());
            }
            finally { CloseHandle(process); }
        }
        [DllImport("kernel32.dll", SetLastError = true)] private static extern bool GetNamedPipeClientProcessId(IntPtr pipe, out uint processId);
        [DllImport("kernel32.dll")] private static extern uint WTSGetActiveConsoleSessionId();
        [DllImport("wtsapi32.dll", SetLastError = true)] private static extern bool WTSQueryUserToken(uint sessionId, out IntPtr token);
        [DllImport("userenv.dll", SetLastError = true)] private static extern bool CreateEnvironmentBlock(out IntPtr environment, IntPtr token, bool inherit);
        [DllImport("userenv.dll")] private static extern bool DestroyEnvironmentBlock(IntPtr environment);
        [DllImport("advapi32.dll", CharSet = CharSet.Unicode, SetLastError = true)] private static extern bool CreateProcessAsUser(IntPtr token, string applicationName, StringBuilder commandLine, IntPtr processAttributes, IntPtr threadAttributes, bool inheritHandles, int creationFlags, IntPtr environment, string currentDirectory, ref STARTUPINFO startupInfo, out PROCESS_INFORMATION processInformation);
        [DllImport("kernel32.dll", SetLastError = true)] private static extern IntPtr OpenProcess(int access, bool inheritHandle, int processId);
        [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)] private static extern bool QueryFullProcessImageName(IntPtr process, int flags, StringBuilder path, ref int length);
        [DllImport("kernel32.dll")] private static extern bool CloseHandle(IntPtr handle);
        [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)] private struct STARTUPINFO
        {
            internal int cb; internal string lpReserved; internal string lpDesktop; internal string lpTitle; internal int dwX; internal int dwY;
            internal int dwXSize; internal int dwYSize; internal int dwXCountChars; internal int dwYCountChars; internal int dwFillAttribute;
            internal int dwFlags; internal short wShowWindow; internal short cbReserved2; internal IntPtr lpReserved2; internal IntPtr hStdInput; internal IntPtr hStdOutput; internal IntPtr hStdError;
        }
        [StructLayout(LayoutKind.Sequential)] private struct PROCESS_INFORMATION { internal IntPtr hProcess; internal IntPtr hThread; internal int dwProcessId; internal int dwThreadId; }
    }
}
