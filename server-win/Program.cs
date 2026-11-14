using System;
using System.Collections.Generic;
using System.Drawing;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Text;
using System.Threading.Tasks;
using System.Web.Script.Serialization;
using System.Windows.Forms;

[assembly: System.Reflection.AssemblyTitle("Room TN20 Server")]
[assembly: System.Reflection.AssemblyCompany("TN20")]
[assembly: System.Reflection.AssemblyVersion("1.0.0.0")]

static class Program
{
    [STAThread] static void Main(string[] args)
    {
        Application.EnableVisualStyles();
        Application.SetCompatibleTextRenderingDefault(false);
        using (var instance = new System.Threading.Mutex(false, @"Local\RoomTN20Server-" + System.Security.Principal.WindowsIdentity.GetCurrent().User.Value))
        {
            bool acquired;
            try { acquired = instance.WaitOne(0); }
            catch (System.Threading.AbandonedMutexException) { acquired = true; }
            if (!acquired) { MessageBox.Show("Room TN20 Server đang chạy.", "Room TN20"); return; }
            try
            {
                if (args.Contains("--installed")) Application.Run(new ConsoleWindow());
                else Install();
            }
            finally { instance.ReleaseMutex(); }
        }
    }

    static void Install()
    {
        try
        {
            string root = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "RoomTN20");
            string executable = Path.Combine(root, "RoomTN20-Server.exe");
            if (MessageBox.Show("Cài hoặc cập nhật Room TN20 Server cho tài khoản Windows hiện tại?", "Room TN20 Server", MessageBoxButtons.OKCancel) != DialogResult.OK) return;
            Directory.CreateDirectory(root);
            File.Copy(Application.ExecutablePath, executable + ".new", true);
            if (File.Exists(executable)) File.Replace(executable + ".new", executable, null);
            else File.Move(executable + ".new", executable);
            string shortcut = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.Programs), "Room TN20 Server.lnk");
            dynamic shell = Activator.CreateInstance(Type.GetTypeFromProgID("WScript.Shell"));
            dynamic link = shell.CreateShortcut(shortcut);
            link.TargetPath = executable;
            link.Arguments = "--installed";
            link.WorkingDirectory = root;
            link.Save();
            Application.Run(new ConsoleWindow());
        }
        catch (Exception error) { MessageBox.Show(error.Message, "Không cài được Room TN20 Server"); }
    }
}
public sealed class Room
{
    public string Name { get; set; }
    public string Secret { get; set; }
    public string Networks { get; set; }
    public List<Machine> Machines { get; set; }
    public List<Media> Playlist { get; set; }
    public string MediaVersion { get; set; }
    public Room() { Machines = new List<Machine>(); Playlist = new List<Media>(); }
    public byte[] Key() { return ProtectedData.Unprotect(Convert.FromBase64String(Secret), null, DataProtectionScope.CurrentUser); }
}
public sealed class Media
{
    public string Id { get; set; }
    public string Name { get; set; }
    public string Type { get; set; }
    public long Length { get; set; }
}
public sealed class Machine
{
    public string Id { get; set; }
    public string Name { get; set; }
    public string Ip { get; set; }
    public string Mac { get; set; }
    public DateTime Seen { get; set; }
    [ScriptIgnore] public int Misses = 2;
    [ScriptIgnore] public string Version = "", Uwf = "", Caps = "", Update = "", Certificate = "", MediaVersion = "", Result = "";
    [ScriptIgnore] public string BootBefore = "";
    [ScriptIgnore] public DateTime RestartDeadline;
    public string BootToken() { return Caps.Split(',').FirstOrDefault(x=>System.Text.RegularExpressions.Regex.IsMatch(x,"^boot-[0-9]{10,20}$")) ?? ""; }
    public string Identity() { return Id + "|" + Mac; }
    public string Presence() { return Misses == 0 ? "Online" : Misses == 1 ? "Chưa phản hồi" : "Offline"; }
    public bool Supports(string value) { return Caps.Split(',').Contains(value); }
}
static class Lan
{
    internal static readonly JavaScriptSerializer Json = new JavaScriptSerializer { MaxJsonLength = 4 * 1024 * 1024 };
    internal static string Get(Dictionary<string,object> data, string key)
    { object value; return data.TryGetValue(key, out value) ? Convert.ToString(value) : ""; }
    internal static string Hash(byte[] value) { using (var sha = SHA256.Create()) return Hex(sha.ComputeHash(value)); }
    internal static string Hex(byte[] value) { return BitConverter.ToString(value).Replace("-", "").ToLowerInvariant(); }
    internal static string Sign(byte[] key, params string[] fields)
    { using (var mac = new HMACSHA256(key)) return Convert.ToBase64String(mac.ComputeHash(Encoding.UTF8.GetBytes(string.Join("\n",fields)))); }
    internal static bool Verify(string signature, byte[] key, params string[] fields)
    {
        string expected = Sign(key, fields);
        if (signature.Length != expected.Length) return false;
        int difference = 0;
        for (int i = 0; i < expected.Length; i++) difference |= expected[i] ^ signature[i];
        return difference == 0;
    }
    internal static string Nonce() { return Guid.NewGuid().ToString("N"); }
    internal static long Timestamp() { return DateTimeOffset.UtcNow.ToUnixTimeSeconds(); }
    static uint Number(IPAddress ip) { var b = ip.GetAddressBytes(); return ((uint)b[0]<<24)|((uint)b[1]<<16)|((uint)b[2]<<8)|b[3]; }
    static IPAddress Address(uint ip) { return new IPAddress(new byte[] {(byte)(ip>>24),(byte)(ip>>16),(byte)(ip>>8),(byte)ip}); }
    internal static IEnumerable<IPAddress> Broadcasts()
    {
        yield return IPAddress.Broadcast;
        foreach (var nic in NetworkInterface.GetAllNetworkInterfaces().Where(x => x.OperationalStatus == OperationalStatus.Up))
            foreach (var u in nic.GetIPProperties().UnicastAddresses)
                if (u.Address.AddressFamily == AddressFamily.InterNetwork && !IPAddress.IsLoopback(u.Address))
                    yield return Address(Number(u.Address) | ~Number(u.IPv4Mask));
    }
    internal static bool Private(IPAddress ip)
    {
        if (ip.AddressFamily != AddressFamily.InterNetwork) return false;
        var b = ip.GetAddressBytes();
        return b[0] == 10 || b[0] == 192 && b[1] == 168 || b[0] == 172 && b[1] >= 16 && b[1] <= 31 || b[0] == 100 && b[1] >= 64 && b[1] <= 127;
    }
    internal static HashSet<IPAddress> Targets(string input)
    {
        var result = new HashSet<IPAddress>();
        foreach (var token in (input ?? "").Split(new[]{ ' ', ',', ';', '\n', '\r' }, StringSplitOptions.RemoveEmptyEntries))
        {
            var parts = token.Split('/');
            IPAddress ip;
            int bits = 32;
            if (parts.Length > 2 || !IPAddress.TryParse(parts[0],out ip) || !Private(ip) ||
                (parts.Length == 2 && (!int.TryParse(parts[1],out bits) || bits < 22 || bits > 32)))
                throw new FormatException("Dùng IPv4 riêng hoặc CIDR /22 đến /32.");
            uint count = 1u << (32 - bits), start = Number(ip) & ~(count-1);
            for (uint i=0; i<count; i++)
            {
                var target = Address(start+i);
                if (!Private(target)) throw new FormatException("Dải IP phải nằm trong mạng riêng.");
                if (count <= 2 || i != 0 && i != count-1) result.Add(target);
                if (result.Count > 1024) throw new FormatException("Tối đa 1024 IP cấu hình mỗi phòng.");
            }
        }
        return result;
    }
    internal static List<Machine> Discover(Room room, bool full)
    {
        var key = room.Key(); string nonce = Nonce();
        byte[] packet = Encoding.UTF8.GetBytes(Json.Serialize(new { type="discover", nonce, signature=Sign(key,"discover",nonce) }));
        var known = new HashSet<IPAddress>(room.Machines.Select(m=>IPAddress.Parse(m.Ip)));
        var targets = new HashSet<IPAddress>(known);
        if (full) { targets.UnionWith(Targets(room.Networks)); targets.UnionWith(Broadcasts()); }
        var found = new Dictionary<string,Machine>();
        using (var socket = new UdpClient(0))
        {
            socket.EnableBroadcast = true;
            socket.Client.ReceiveBufferSize = 1024 * 1024;
            socket.Client.ReceiveTimeout = 100;
            var clock = System.Diagnostics.Stopwatch.StartNew();
            var pending = new HashSet<IPAddress>(known);
            int sent = 0;
            foreach (var ip in targets)
                try { socket.Send(packet,packet.Length,new IPEndPoint(ip,17840)); sent++; }
                catch (SocketException) { }
            if (targets.Count > 0 && sent == 0) throw new IOException("Không gửi được gói dò LAN/VPN.");
            while (clock.ElapsedMilliseconds < (full ? 3000 : 1400) && (full || pending.Count > 0))
            {
                IPEndPoint source = null;
                try
                {
                    var bytes = socket.Receive(ref source);
                    if (bytes.Length > 4096 || source.Port != 17840 || !Private(source.Address)) continue;
                    var d = Json.Deserialize<Dictionary<string,object>>(Encoding.UTF8.GetString(bytes));
                    string id=Get(d,"deviceId"), name=Get(d,"hostname"), mac=Get(d,"mac");
                    Guid parsed;
                    if (Get(d,"type")!="discovery" || Get(d,"requestNonce")!=nonce || !Guid.TryParse(id,out parsed) ||
                        !System.Text.RegularExpressions.Regex.IsMatch(mac, @"^(?:[0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}$") ||
                        !Verify(Get(d,"signature"),key,"discovery",nonce,id,name,mac)) continue;
                    var machine = new Machine { Id=id, Name=name, Mac=mac.ToUpperInvariant(), Ip=source.Address.ToString(), Seen=DateTime.UtcNow, Misses=0 };
                    string version=Get(d,"agentVersion"), uwf=Get(d,"uwf"), caps=Get(d,"capabilities");
                    if (Verify(Get(d,"stateSignature"),key,"state",nonce,id,version,uwf,caps))
                    { machine.Version=version; machine.Uwf=uwf; machine.Caps=caps; }
                    string code=Get(d,"clientVersionCode"), latest=Get(d,"latestVersion"), status=Get(d,"updateStatus"), message=Get(d,"updateMessage");
                    if (Verify(Get(d,"updateSignature"),key,"update",nonce,id,code,latest,status,message))
                        machine.Update = latest + " • " + status + " • " + message;
                    string cert=Get(d,"mediaCertSha256"), media=Get(d,"mediaVersion"), port=Get(d,"mediaPort");
                    if (port=="17842" && Verify(Get(d,"lockSignature"),key,"lockstate",nonce,id,Get(d,"lockState"),media,port,cert))
                    { machine.Certificate=cert; machine.MediaVersion=media; }
                    found[machine.Identity()]=machine;
                    pending.Remove(source.Address);
                }
                catch (SocketException e) { if(e.SocketErrorCode!=SocketError.TimedOut && e.SocketErrorCode!=SocketError.ConnectionReset) throw; }
                catch (ArgumentException) { }
                catch (InvalidOperationException) { }
            }
        }
        return found.Values.ToList();
    }
    internal static string Command(Room room, Machine machine, string action, string targetMac = null)
    {
        if (machine.Misses != 0) return "Chưa phản hồi";
        if ((action=="freeze" || action=="unfreeze") && !machine.Supports("uwf")) return "UWF chưa sẵn sàng";
        if ((action=="lock" || action=="unlock") && !machine.Supports("sessionlock")) return "Client chưa hỗ trợ khóa";
        string requestId=Nonce(), nonce=Nonce();
        long timestamp=Timestamp();
        var fields=new List<string>{"command",requestId,machine.Id,action,timestamp.ToString(),nonce};
        if(targetMac!=null) fields.Add(targetMac);
        var key=room.Key();
        var request=new Dictionary<string,object>{{"type","command"},{"requestId",requestId},{"deviceId",machine.Id},{"action",action},{"timestamp",timestamp},{"nonce",nonce},{"signature",Sign(key,fields.ToArray())}};
        if(targetMac!=null) request["targetMac"]=targetMac;
        var bytes=Encoding.UTF8.GetBytes(Json.Serialize(request));
        using(var socket=new UdpClient())
        {
            socket.Connect(machine.Ip,17840);
            socket.Client.ReceiveTimeout=250;
            socket.Send(bytes,bytes.Length);
            var clock=System.Diagnostics.Stopwatch.StartNew();
            while(clock.ElapsedMilliseconds < (action=="freeze" || action=="unfreeze" ? 30000 : action=="lock" || action=="unlock" ? 15000 : 4000))
            {
                IPEndPoint source=null;
                try
                {
                    var response=socket.Receive(ref source);
                    if(response.Length>4096) continue;
                    var d=Json.Deserialize<Dictionary<string,object>>(Encoding.UTF8.GetString(response));
                    string status=Get(d,"status"), message=Get(d,"message");
                    if(Get(d,"type")!="ack" || Get(d,"requestId")!=requestId || !Verify(Get(d,"signature"),key,"ack",requestId,status)) continue;
                    bool verified=Verify(Get(d,"detailSignature"),key,"ack-detail",requestId,status,message);
                    if(status!="accepted") return "Bị từ chối" + (verified ? " • "+message : "");
                    return (verified && action!="restart" && action!="shutdown" && action!="wake" ? "Đã xác nhận" : "Đã gửi") + (verified ? " • "+message : "");
                }
                catch(SocketException e) { if(e.SocketErrorCode!=SocketError.TimedOut) break; }
                catch(ArgumentException) { }
                catch(InvalidOperationException) { }
            }
        }
        return "Chưa xác nhận kết quả";
    }
    internal static string Wake(Room room,Machine machine)
    {
        var relay=room.Machines.FirstOrDefault(m=>m.Misses==0 && m.Supports("wolrelay") && m.Identity()!=machine.Identity()
            && Targets(room.Networks).Contains(IPAddress.Parse(m.Ip)));
        if(relay!=null) return Command(room,relay,"wake",machine.Mac);
        var mac=machine.Mac.Split(':').Select(x=>Convert.ToByte(x,16)).ToArray();
        var packet=Enumerable.Repeat((byte)255,6).Concat(Enumerable.Range(0,16).SelectMany(_=>mac)).ToArray();
        using(var socket=new UdpClient { EnableBroadcast=true })
        {
            int sent=0;
            foreach(var ip in Broadcasts().Distinct())
                try { socket.Send(packet,packet.Length,new IPEndPoint(ip,9)); sent++; } catch(SocketException) { }
            if(sent==0) throw new IOException("Không gửi được Magic Packet.");
        }
        return "Đã gửi Magic Packet LAN • Chờ máy phản hồi";
    }
}
