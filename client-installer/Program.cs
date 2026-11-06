using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.Management.Automation;
using System.Management.Automation.Runspaces;
using System.Management.Automation.Language;
using System.Net;
using System.Reflection;
using System.Runtime.InteropServices;
using System.Runtime.Versioning;
using System.Security.AccessControl;
using System.Security.Cryptography;
using System.Security.Principal;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading.Tasks;
using System.Windows.Forms;

[assembly: AssemblyTitle("Room TN20 Client Setup")]
[assembly: AssemblyDescription("TN20 - Trình cài client quản lý máy tính trong mạng LAN")]
[assembly: AssemblyCompany("Công ty TNHH TN20")]
[assembly: AssemblyProduct("Room TN20")]
[assembly: AssemblyVersion("1.2.0.0")]
[assembly: AssemblyFileVersion("1.2.0.0")]
[assembly: TargetFramework(".NETFramework,Version=v4.7.2", FrameworkDisplayName = ".NET Framework 4.7.2")]

namespace LanPower.ClientSetup
{
    internal static class Program
    {
        [DllImport("kernel32.dll", SetLastError = true)]
        private static extern bool AttachConsole(uint processId);

        [STAThread]
        private static int Main(string[] args)
        {
            if (args.Length > 0 && args[0] != "--ui-test" && args[0] != "--preview")
            {
                AttachConsole(0xffffffff);
                try
                {
                    if (args.Length == 1 && (args[0] == "--verify-payload" || args[0] == "--self-test"))
                    {
                        InstallerEngine.VerifyPayload(true);
                        return 0;
                    }
                    if (args.Length == 1 && args[0] == "--verify-ui")
                    {
                        SetupForm.VerifyUiContract();
                        Console.WriteLine("UI_CONTRACT=PASS");
                        return 0;
                    }
                    if (args[0] == "--ci-install") return RunCiInstall(args);
                    throw new ArgumentException("Unknown command. Use --verify-payload, --verify-ui, --ci-install or --preview.");
                }
                catch (Exception error) { Console.Error.WriteLine("SETUP_ERROR=" + error.Message); return 1; }
            }
            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);
            Application.Run(new SetupForm(args.Length == 1 && (args[0] == "--ui-test" || args[0] == "--preview")));
            return 0;
        }

        private static int RunCiInstall(string[] args)
        {
            Dictionary<string, string> options = new Dictionary<string, string>(StringComparer.Ordinal);
            for (int index = 1; index < args.Length; index += 2)
            {
                if (index + 1 >= args.Length || (args[index] != "--install-path" && args[index] != "--task-name" && args[index] != "--port" && args[index] != "--vpn-networks") || options.ContainsKey(args[index]))
                    throw new ArgumentException("Invalid CI install options.");
                options.Add(args[index], args[index + 1]);
            }
            int port;
            if (!options.ContainsKey("--install-path") || !options.ContainsKey("--task-name") || !options.ContainsKey("--port") || !Int32.TryParse(options["--port"], out port) || port < 1024 || port > 65535)
                throw new ArgumentException("CI requires --install-path, --task-name and --port (1024..65535).");
            string roomKey = Console.ReadLine();
            InstallerEngine.ValidateRoomKey(roomKey);
            try
            {
                InstallerEngine.Install(roomKey, options["--install-path"], options["--task-name"], port, options.ContainsKey("--vpn-networks") ? options["--vpn-networks"] : null, false, null);
                Console.WriteLine("CI_INSTALL=PASS");
                return 0;
            }
            catch (Exception error) { throw new InvalidOperationException(InstallerEngine.HideKey(error.Message, roomKey)); }
        }
    }

    internal sealed class InstallResult
    {
        internal string Message;
        internal bool UwfWarning;
    }

    internal sealed class SetupUpdate
    {
        internal string Version;
        internal string Url;
        internal string Sha256;
    }

    internal static class InstallerEngine
    {
        internal static void ValidateRoomKey(string roomKey)
        {
            if (roomKey == null || !Regex.IsMatch(roomKey, @"\A[\x21-\x7E]{8}\z"))
                throw new ArgumentException("Khóa phòng phải có đúng 8 ký tự ASCII, không chứa khoảng trắng và có phân biệt chữ hoa/thường.");
        }

        internal static string HideKey(string message, string key)
        {
            return String.IsNullOrEmpty(key) ? message : message.Replace(key, "[hidden]");
        }

        private static byte[] Resource(string name)
        {
            using (Stream input = Assembly.GetExecutingAssembly().GetManifestResourceStream("LanPower.Payload." + name))
            {
                if (input == null) throw new InvalidOperationException("Missing embedded payload: " + name);
                using (MemoryStream output = new MemoryStream()) { input.CopyTo(output); return output.ToArray(); }
            }
        }

        private static string Sha256(byte[] value)
        {
            using (SHA256 sha = SHA256.Create()) { return BitConverter.ToString(sha.ComputeHash(value)).Replace("-", ""); }
        }

        private static Runspace OpenCore()
        {
            bool supported = false;
            foreach (ConstructorInfo constructor in typeof(Rfc2898DeriveBytes).GetConstructors())
            {
                ParameterInfo[] parameters = constructor.GetParameters();
                if (parameters.Length == 4 && parameters[3].ParameterType.FullName == "System.Security.Cryptography.HashAlgorithmName") supported = true;
            }
            if (!supported) throw new InvalidOperationException("Cần cài .NET Framework 4.7.2 trở lên trước khi dùng trình cài đặt này.");
            string trustedModules = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.Windows), @"System32\WindowsPowerShell\v1.0\Modules");
            if (!Directory.Exists(trustedModules)) throw new InvalidOperationException("Windows PowerShell system modules were not found.");
            Environment.SetEnvironmentVariable("PSModulePath", trustedModules, EnvironmentVariableTarget.Process);
            Runspace runspace = RunspaceFactory.CreateRunspace();
            try
            {
                runspace.ApartmentState = System.Threading.ApartmentState.STA;
                runspace.Open();
                using (PowerShell powershell = PowerShell.Create())
                {
                    powershell.Runspace = runspace;
                    powershell.AddScript("$ErrorActionPreference = 'Stop';\n" + Encoding.UTF8.GetString(Resource("Install-Core.ps1")), false);
                    powershell.Invoke();
                    ThrowErrors(powershell);
                }
                return runspace;
            }
            catch { runspace.Dispose(); throw; }
        }

        private static void ThrowErrors(PowerShell powershell)
        {
            if (powershell.Streams.Error.Count > 0)
                throw new InvalidOperationException(powershell.Streams.Error[0].ToString());
            if (powershell.InvocationStateInfo.State == PSInvocationState.Failed)
                throw new InvalidOperationException(powershell.InvocationStateInfo.Reason != null ? powershell.InvocationStateInfo.Reason.Message : "PowerShell operation failed.");
        }

        private static byte[] DownloadBytes(string url, int maximumBytes)
        {
            ServicePointManager.SecurityProtocol = SecurityProtocolType.Tls12;
            HttpWebRequest request = (HttpWebRequest)WebRequest.Create(url);
            request.AllowAutoRedirect = false;
            request.Timeout = 30000;
            using (HttpWebResponse response = (HttpWebResponse)request.GetResponse())
            {
                if (response.StatusCode != HttpStatusCode.OK || response.ContentLength > maximumBytes) throw new InvalidOperationException("Update download size or status is invalid.");
                using (Stream input = response.GetResponseStream())
                using (MemoryStream output = new MemoryStream())
                {
                    byte[] buffer = new byte[16384];
                    int read;
                    while ((read = input.Read(buffer, 0, buffer.Length)) > 0)
                    {
                        if (output.Length + read > maximumBytes) throw new InvalidOperationException("Update download exceeds the size limit.");
                        output.Write(buffer, 0, read);
                    }
                    return output.ToArray();
                }
            }
        }

        internal static SetupUpdate CheckSetupUpdate()
        {
            VerifyPayload(false);
            string envelope = Encoding.UTF8.GetString(DownloadBytes("https://raw.githubusercontent.com/namnamthainguyen-ops/apklan/main/updates/stable.json", 262144));
            using (Runspace runspace = OpenCore())
            {
                Token[] tokens;
                ParseError[] errors;
                ScriptBlockAst ast = Parser.ParseInput(Encoding.UTF8.GetString(Resource("Update-Agent.ps1")), out tokens, out errors);
                if (errors.Length != 0) throw new InvalidOperationException("Embedded update verifier failed to parse.");
                StringBuilder functions = new StringBuilder();
                int count = 0;
                foreach (Ast node in ast.FindAll(delegate(Ast candidate)
                {
                    FunctionDefinitionAst function = candidate as FunctionDefinitionAst;
                    return function != null && (function.Name == "Read-RoomTN20Manifest" || function.Name == "Test-RoomTN20DownloadUrl");
                }, true)) { functions.AppendLine(node.Extent.Text); count++; }
                if (count != 2) throw new InvalidOperationException("Embedded update verifier functions are missing.");
                using (PowerShell powershell = PowerShell.Create())
                {
                    powershell.Runspace = runspace;
                    powershell.AddScript(functions.ToString(), false).Invoke();
                    ThrowErrors(powershell);
                }
                PSObject windows;
                using (PowerShell powershell = PowerShell.Create())
                {
                    powershell.Runspace = runspace;
                    powershell.AddCommand("Read-RoomTN20Manifest").AddParameter("EnvelopeJson", envelope)
                        .AddParameter("PublicKeyXml", Encoding.UTF8.GetString(Resource("Update-PublicKey.xml")));
                    System.Collections.ObjectModel.Collection<PSObject> values = powershell.Invoke();
                    ThrowErrors(powershell);
                    if (values.Count != 1) throw new InvalidOperationException("Invalid update manifest.");
                    windows = PSObject.AsPSObject(values[0].Properties["windows"].Value);
                }
                if (Convert.ToInt32(windows.Properties["versionCode"].Value) <= 5 || windows.Properties["setup"] == null) return null;
                PSObject setup = PSObject.AsPSObject(windows.Properties["setup"].Value);
                string url = Convert.ToString(setup.Properties["url"].Value);
                string hash = Convert.ToString(setup.Properties["sha256"].Value);
                if (!Regex.IsMatch(hash, @"\A[0-9A-Fa-f]{64}\z")) throw new InvalidOperationException("Signed installer checksum is invalid.");
                using (PowerShell powershell = PowerShell.Create())
                {
                    powershell.Runspace = runspace;
                    powershell.AddCommand("Test-RoomTN20DownloadUrl").AddParameter("Url", url);
                    System.Collections.ObjectModel.Collection<PSObject> values = powershell.Invoke();
                    ThrowErrors(powershell);
                    if (values.Count != 1 || !Convert.ToBoolean(values[0].BaseObject)) throw new InvalidOperationException("Signed installer URL is outside the allowed repository.");
                }
                return new SetupUpdate { Version = Convert.ToString(windows.Properties["version"].Value), Url = url, Sha256 = hash };
            }
        }

        internal static string DownloadSetup(SetupUpdate update)
        {
            byte[] bytes = DownloadBytes(update.Url, 20971520);
            if (!String.Equals(Sha256(bytes), update.Sha256, StringComparison.OrdinalIgnoreCase)) throw new InvalidOperationException("Installer update checksum mismatch.");
            string directory = CreatePayloadDirectory();
            try
            {
                string path = Path.Combine(directory, "RoomTN20-ClientSetup.exe");
                File.WriteAllBytes(path, bytes);
                return path;
            }
            catch { RemovePayloadDirectory(directory); throw; }
        }

        internal static void VerifyPayload(bool writeOutput)
        {
            string agentHash = Sha256(Resource("StandaloneAgent.ps1"));
            string coreHash = Sha256(Resource("Install-Core.ps1"));
            string updaterHash = Sha256(Resource("Update-Agent.ps1"));
            string publicKeyHash = Sha256(Resource("Update-PublicKey.xml"));
            if (agentHash != PayloadInfo.AgentSha256 || coreHash != PayloadInfo.CoreSha256 || updaterHash != PayloadInfo.UpdaterSha256 || publicKeyHash != PayloadInfo.PublicKeySha256)
                throw new InvalidOperationException("Embedded payload checksum mismatch.");
            using (Runspace runspace = OpenCore())
            {
                using (PowerShell powershell = PowerShell.Create())
                {
                    powershell.Runspace = runspace;
                    powershell.AddCommand("ConvertTo-LanPowerManagementKey").AddParameter("RoomKey", "Ab12!@#$");
                    System.Collections.ObjectModel.Collection<PSObject> values = powershell.Invoke();
                    ThrowErrors(powershell);
                    if (values.Count != 1 || values[0].ToString() != "cyzk0ZNRJdMHxC4GnzJbLzNOi8QHwfrM55eKrEVMuu8=")
                        throw new InvalidOperationException("Room key derivation self-test failed.");
                }
                using (PowerShell powershell = PowerShell.Create())
                {
                    powershell.Runspace = runspace;
                    string missingPath = Path.Combine(Path.GetTempPath(), "RoomTN20-VerifyMissing-" + Guid.NewGuid().ToString("N"));
                    powershell.AddCommand("Get-Item").AddParameter("LiteralPath", missingPath).AddParameter("ErrorAction", ActionPreference.SilentlyContinue);
                    powershell.Invoke();
                    // PowerShell 5.1 records HadErrors even for intentionally ignored
                    // missing-task/file lookups. Such a completed invocation is valid.
                    ThrowErrors(powershell);
                    if (powershell.Streams.Error.Count != 0 || powershell.InvocationStateInfo.State != PSInvocationState.Completed)
                        throw new InvalidOperationException("Hosted PowerShell ignored-error self-test failed.");
                }
                using (PowerShell powershell = PowerShell.Create())
                {
                    powershell.Runspace = runspace;
                    powershell.AddCommand("Write-Error").AddParameter("Message", "Expected installer self-test failure.").AddParameter("ErrorAction", ActionPreference.Continue);
                    powershell.Invoke();
                    bool rejected = false;
                    try { ThrowErrors(powershell); }
                    catch (InvalidOperationException) { rejected = true; }
                    if (!rejected) throw new InvalidOperationException("Hosted PowerShell real-error self-test failed.");
                }
            }
            if (writeOutput)
            {
                Console.WriteLine("AGENT_SHA256=" + agentHash);
                Console.WriteLine("CORE_SHA256=" + coreHash);
                Console.WriteLine("UPDATER_SHA256=" + updaterHash);
                Console.WriteLine("PUBLIC_KEY_SHA256=" + publicKeyHash);
                Console.WriteLine("ROOM_KEY_KDF=PASS");
                Console.WriteLine("HOSTED_ERROR_POLICY=PASS");
                Console.WriteLine("PAYLOAD_VERIFY=PASS");
            }
        }

        private static string CreatePayloadDirectory()
        {
            string parent = Environment.GetFolderPath(Environment.SpecialFolder.CommonApplicationData);
            RejectReparseAncestors(parent);
            string path = Path.Combine(parent, "LanPowerInstaller-" + Guid.NewGuid().ToString("N"));
            DirectorySecurity security = new DirectorySecurity();
            SecurityIdentifier administrators = new SecurityIdentifier(WellKnownSidType.BuiltinAdministratorsSid, null);
            security.SetOwner(administrators);
            security.SetAccessRuleProtection(true, false);
            foreach (SecurityIdentifier sid in new SecurityIdentifier[] { administrators, new SecurityIdentifier(WellKnownSidType.LocalSystemSid, null) })
                security.AddAccessRule(new FileSystemAccessRule(sid, FileSystemRights.FullControl,
                    InheritanceFlags.ContainerInherit | InheritanceFlags.ObjectInherit, PropagationFlags.None, AccessControlType.Allow));
            Directory.CreateDirectory(path, security);
            return path;
        }

        private static void RejectReparseAncestors(string path)
        {
            for (DirectoryInfo item = new DirectoryInfo(path); item != null; item = item.Parent)
                if (item.Exists && (item.Attributes & FileAttributes.ReparsePoint) != 0)
                    throw new InvalidOperationException("Installer path must not contain a reparse point.");
        }

        private static void RemovePayloadDirectory(string path)
        {
            string parent = Environment.GetFolderPath(Environment.SpecialFolder.CommonApplicationData);
            string full = Path.GetFullPath(path);
            if (!String.Equals(Path.GetDirectoryName(full), parent.TrimEnd(Path.DirectorySeparatorChar), StringComparison.OrdinalIgnoreCase) ||
                !Regex.IsMatch(Path.GetFileName(full), @"\ALanPowerInstaller-[0-9a-f]{32}\z"))
                throw new InvalidOperationException("Unsafe installer cleanup path.");
            if (!Directory.Exists(full)) return;
            RejectReparseAncestors(full);
            foreach (string file in Directory.GetFiles(full))
            {
                string name = Path.GetFileName(file);
                if ((name != "StandaloneAgent.ps1" && name != "Install-Core.ps1" && name != "Update-Agent.ps1" && name != "Update-PublicKey.xml" && name != "RoomTN20-ClientSetup.exe") || (File.GetAttributes(file) & FileAttributes.ReparsePoint) != 0)
                    throw new InvalidOperationException("Unexpected file in installer extraction directory.");
                File.Delete(file);
            }
            Directory.Delete(full, false);
        }

        internal static InstallResult Install(string roomKey, string installPath, string taskName, int port, string vpnNetworks, bool prepareUwf, Action<string> progress)
        {
            ValidateRoomKey(roomKey);
            using (WindowsIdentity identity = WindowsIdentity.GetCurrent())
                if (!new WindowsPrincipal(identity).IsInRole(WindowsBuiltInRole.Administrator))
                    throw new InvalidOperationException("Hãy chạy trình cài đặt bằng quyền Administrator.");
            if (progress != null) progress("Đang kiểm tra gói cài đặt…");
            VerifyPayload(false);
            string payloadDirectory = CreatePayloadDirectory();
            try
            {
                string agentPath = Path.Combine(payloadDirectory, "StandaloneAgent.ps1");
                foreach (string name in new string[] { "StandaloneAgent.ps1", "Install-Core.ps1", "Update-Agent.ps1", "Update-PublicKey.xml" })
                    File.WriteAllBytes(Path.Combine(payloadDirectory, name), Resource(name));
                using (Runspace runspace = OpenCore())
                {
                    if (progress != null) progress("Đang cài dịch vụ quản lý máy trong LAN…");
                    using (PowerShell powershell = PowerShell.Create())
                    {
                        powershell.Runspace = runspace;
                        powershell.AddCommand("Invoke-LanPowerInstall").AddParameter("SourceAgent", agentPath).AddParameter("RoomKey", roomKey)
                            .AddParameter("InstallPath", installPath).AddParameter("TaskName", taskName).AddParameter("Port", port);
                        powershell.AddParameter("SkipUwfPreparation", true);
                        if (!String.IsNullOrWhiteSpace(vpnNetworks)) powershell.AddParameter("VpnNetworks", new string[] { vpnNetworks });
                        powershell.Invoke();
                        ThrowErrors(powershell);
                    }
                    InstallResult result = new InstallResult();
                    result.Message = "Đã cài agent quản lý LAN/VPN và tác vụ tự cập nhật có xác minh chữ ký mỗi 6 giờ. Mở app Android, nhập cùng khóa phòng rồi quét LAN hoặc thêm địa chỉ VPN.";
                    if (prepareUwf)
                    {
                        if (progress != null) progress("Agent đã cài. Đang chuẩn bị tính năng đóng băng C:…");
                        try
                        {
                            using (PowerShell powershell = PowerShell.Create())
                            {
                                powershell.Runspace = runspace;
                                powershell.AddCommand("Initialize-LanPowerUwfFeature");
                                powershell.Invoke();
                                ThrowErrors(powershell);
                            }
                            using (PowerShell powershell = PowerShell.Create())
                            {
                                powershell.Runspace = runspace;
                                powershell.AddCommand("Get-WindowsOptionalFeature").AddParameter("Online").AddParameter("FeatureName", "Client-UnifiedWriteFilter");
                                System.Collections.ObjectModel.Collection<PSObject> features = powershell.Invoke();
                                ThrowErrors(powershell);
                                bool enabled = features.Count > 0 && features[0].Properties["State"] != null && features[0].Properties["State"].Value.ToString() == "Enabled";
                                result.Message += enabled ? "\n\nUWF đã được chuẩn bị để dùng đóng băng C: từ app." : "\n\nCần khởi động lại máy để hoàn tất chuẩn bị UWF. Trình cài đặt không tự khởi động lại.";
                            }
                        }
                        catch (Exception error)
                        {
                            result.UwfWarning = true;
                            result.Message += "\n\nAgent bật/tắt đã cài thành công. UWF chưa được chuẩn bị; bạn có thể kiểm tra sau.\n" + HideKey(error.Message, roomKey);
                        }
                    }
                    return result;
                }
            }
            finally { RemovePayloadDirectory(payloadDirectory); }
        }
    }

    internal sealed class SetupForm : Form
    {
        private readonly TextBox keyInput = new TextBox();
        private readonly TextBox vpnInput = new TextBox();
        private readonly Button installButton = new Button();
        private readonly Button closeButton = new Button();
        private readonly CheckBox toggleKey = new CheckBox();
        private readonly Label status = new Label();
        private readonly ProgressBar progress = new ProgressBar();
        private readonly LinkLabel updateLink = new LinkLabel();
        private SetupUpdate setupUpdate;
        private readonly bool uiTest;
        private bool installing;

        internal SetupForm(bool testMode)
        {
            uiTest = testMode;
            Text = "Room TN20 - Client Setup " + PayloadInfo.Version;
            Name = "LanPowerSetupForm";
            ClientSize = new Size(560, 492);
            Font = new Font("Segoe UI", 10F);
            FormBorderStyle = FormBorderStyle.FixedDialog;
            MaximizeBox = false;
            StartPosition = FormStartPosition.CenterScreen;
            AutoScaleMode = AutoScaleMode.Dpi;
            Label title = new Label { Name = "BrandTitleLabel", Text = "Room TN20", AutoSize = false, Location = new Point(24, 17), Size = new Size(510, 40), Font = new Font("Segoe UI", 20F, FontStyle.Bold), ForeColor = Color.FromArgb(16, 42, 67) };
            Label caption = new Label { Text = "Cài client quản lý LAN · dùng cùng khóa phòng trên app Android.", AutoSize = false, Location = new Point(26, 62), Size = new Size(505, 35) };
            Label keyLabel = new Label { Text = "Khóa phòng (đúng 8 ký tự, phân biệt hoa/thường)", AutoSize = false, Location = new Point(26, 106), Size = new Size(500, 26) };
            keyInput.Name = "RoomKeyInput";
            keyInput.AccessibleName = "Khóa phòng";
            keyInput.Location = new Point(28, 137);
            keyInput.Size = new Size(325, 30);
            keyInput.MaxLength = 256;
            keyInput.UseSystemPasswordChar = true;
            keyInput.TextChanged += delegate { UpdateInstallButton(); };
            toggleKey.Name = "ToggleKeyCheckBox";
            toggleKey.Text = "Hiện khóa";
            toggleKey.AutoSize = true;
            toggleKey.Location = new Point(373, 139);
            toggleKey.CheckedChanged += delegate { keyInput.UseSystemPasswordChar = !toggleKey.Checked; };
            Label vpnLabel = new Label { Text = "Mạng VPN/IP quản trị (tùy chọn)", Location = new Point(28, 184), Size = new Size(500, 24) };
            vpnInput.Name = "VpnNetworksInput";
            vpnInput.Location = new Point(28, 211);
            vpnInput.Size = new Size(504, 30);
            vpnInput.MaxLength = 1024;
            Label vpnHint = new Label { Text = "Ví dụ: 10.8.0.0/24, 100.64.0.0/10 · chỉ cho phép mạng riêng.", Location = new Point(28, 247), Size = new Size(504, 28), Font = new Font("Segoe UI", 9F), ForeColor = Color.DimGray };
            progress.Location = new Point(28, 285);
            progress.Size = new Size(504, 10);
            progress.Visible = false;
            progress.Style = ProgressBarStyle.Marquee;
            status.Name = "StatusLabel";
            status.Text = uiTest ? "Chế độ xem thử – không cài đặt." : "Cần quyền Administrator. Trình cài đặt không tự khởi động lại máy.";
            status.Location = new Point(28, 305);
            status.Size = new Size(504, 78);
            installButton.Name = "InstallButton";
            installButton.Text = "Cài đặt";
            installButton.Location = new Point(302, 401);
            installButton.Size = new Size(112, 34);
            installButton.Enabled = false;
            installButton.Click += InstallClicked;
            closeButton.Name = "CloseButton";
            closeButton.Text = "Đóng";
            closeButton.Location = new Point(426, 401);
            closeButton.Size = new Size(106, 34);
            closeButton.Click += delegate { Close(); };
            updateLink.Name = "SetupUpdateLink";
            updateLink.Text = "Phiên bản 1.2.0 · tự cập nhật client";
            updateLink.LinkArea = new LinkArea(0, 0);
            updateLink.Location = new Point(28, 402);
            updateLink.Size = new Size(260, 34);
            updateLink.Font = new Font("Segoe UI", 9F);
            updateLink.LinkClicked += DownloadUpdateClicked;
            Panel footerLine = new Panel { Location = new Point(28, 447), Size = new Size(504, 1), BackColor = Color.FromArgb(215, 222, 230) };
            Label company = new Label { Name = "CompanyLabel", Text = "Công ty TNHH TN20", Location = new Point(28, 458), Size = new Size(340, 22), Font = new Font("Segoe UI", 9F), ForeColor = Color.DimGray };
            LinkLabel website = new LinkLabel { Name = "WebsiteLinkLabel", Text = "20.com.vn", Location = new Point(434, 458), Size = new Size(98, 22), Font = new Font("Segoe UI", 9F), LinkColor = Color.FromArgb(23, 105, 170), LinkBehavior = LinkBehavior.HoverUnderline };
            website.AccessibleName = "Website TN20";
            website.LinkClicked += delegate
            {
                try { Process.Start(new ProcessStartInfo("https://20.com.vn") { UseShellExecute = true }); }
                catch (Exception error) { MessageBox.Show(this, error.Message, "Room TN20 - Mở website", MessageBoxButtons.OK, MessageBoxIcon.Information); }
            };
            Controls.AddRange(new Control[] { title, caption, keyLabel, keyInput, toggleKey, vpnLabel, vpnInput, vpnHint, progress, status, installButton, closeButton, updateLink, footerLine, company, website });
            AcceptButton = installButton;
            CancelButton = closeButton;
            FormClosing += delegate(object sender, FormClosingEventArgs eventArgs) { if (installing) eventArgs.Cancel = true; };
            Shown += async delegate
            {
                if (uiTest) return;
                try
                {
                    setupUpdate = await Task.Run(delegate { return InstallerEngine.CheckSetupUpdate(); });
                    if (IsDisposed) return;
                    if (setupUpdate != null)
                    {
                        updateLink.Text = "Tải bộ cài mới " + setupUpdate.Version;
                        updateLink.LinkArea = new LinkArea(0, updateLink.Text.Length);
                    }
                }
                catch { if (!IsDisposed) updateLink.Text = "Chưa kiểm tra được bản mới"; }
            };
        }

        private async void DownloadUpdateClicked(object sender, LinkLabelLinkClickedEventArgs eventArgs)
        {
            if (uiTest || installing || setupUpdate == null) return;
            updateLink.Enabled = false;
            try
            {
                string path = await Task.Run(delegate { return InstallerEngine.DownloadSetup(setupUpdate); });
                if (MessageBox.Show(this, "Đã xác minh chữ ký phát hành và SHA-256 của bộ cài mới. Mở bộ cài ngay?", "Room TN20 - Bản cập nhật " + setupUpdate.Version,
                    MessageBoxButtons.YesNo, MessageBoxIcon.Information) == DialogResult.Yes)
                    Process.Start(new ProcessStartInfo(path) { UseShellExecute = true });
            }
            catch (Exception error) { MessageBox.Show(this, error.Message, "Không tải được bản cập nhật", MessageBoxButtons.OK, MessageBoxIcon.Warning); }
            finally { updateLink.Enabled = true; }
        }

        private void UpdateInstallButton()
        {
            bool valid = Regex.IsMatch(keyInput.Text, @"\A[\x21-\x7E]{8}\z");
            installButton.Enabled = !installing && valid;
            if (!installing && keyInput.Text.Length > 0)
                status.Text = (uiTest ? "Chế độ xem thử – không cài đặt. " : "") +
                    (valid ? "Khóa phòng hợp lệ. Chọn Cài đặt để tiếp tục." : "Khóa phòng cần đúng 8 ký tự ASCII, không chứa khoảng trắng.");
        }

        internal static void VerifyUiContract()
        {
            using (SetupForm form = new SetupForm(true))
            {
                if (form.Text != "Room TN20 - Client Setup 1.2.0" || form.Controls["BrandTitleLabel"].Text != "Room TN20" ||
                    form.Controls["CompanyLabel"].Text != "Công ty TNHH TN20" || form.Controls["WebsiteLinkLabel"].Text != "20.com.vn")
                    throw new InvalidOperationException("Installer branding contract failed.");
                if (form.keyInput.Name != "RoomKeyInput" || form.keyInput.MaxLength != 256 || !form.keyInput.UseSystemPasswordChar ||
                    form.installButton.Name != "InstallButton" || form.installButton.Enabled || form.closeButton.Name != "CloseButton" ||
                    form.toggleKey.Name != "ToggleKeyCheckBox" || form.status.Name != "StatusLabel" || !form.uiTest)
                    throw new InvalidOperationException("Installer UI contract failed.");
                form.keyInput.Text = "Ab12!@#$";
                if (!form.installButton.Enabled) throw new InvalidOperationException("Valid room key was not accepted by preview UI.");
                form.keyInput.Text = "bad key!";
                if (form.installButton.Enabled) throw new InvalidOperationException("Invalid room key was accepted by preview UI.");
                form.keyInput.Text = "123456789";
                if (form.installButton.Enabled || form.keyInput.Text.Length != 9) throw new InvalidOperationException("Long room key was truncated or accepted by preview UI.");
                form.toggleKey.Checked = true;
                if (form.keyInput.UseSystemPasswordChar) throw new InvalidOperationException("Password visibility toggle failed.");
            }
        }

        private async void InstallClicked(object sender, EventArgs eventArgs)
        {
            string roomKey = keyInput.Text;
            string vpnNetworks = vpnInput.Text;
            try { InstallerEngine.ValidateRoomKey(roomKey); }
            catch (Exception error) { MessageBox.Show(this, error.Message, "Khóa phòng chưa hợp lệ", MessageBoxButtons.OK, MessageBoxIcon.Warning); return; }
            if (uiTest)
            {
                status.Text = "Khóa đúng định dạng. Chế độ xem thử không thay đổi máy tính.";
                MessageBox.Show(this, "Khóa phòng có đúng định dạng. Đây là chế độ xem thử; chưa cài đặt hoặc thay đổi hệ thống.", "Kiểm tra khóa thành công", MessageBoxButtons.OK, MessageBoxIcon.Information);
                return;
            }
            installing = true;
            keyInput.Enabled = vpnInput.Enabled = toggleKey.Enabled = closeButton.Enabled = false;
            installButton.Enabled = false;
            progress.Visible = true;
            try
            {
                string installPath = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.CommonApplicationData), "LanPowerStandalone");
                InstallResult result = await Task.Run(delegate
                {
                    return InstallerEngine.Install(roomKey, installPath, "LanPowerStandaloneAgent", 17840, vpnNetworks, true,
                        delegate(string message) { BeginInvoke(new Action(delegate { status.Text = message; })); });
                });
                status.Text = "Đã cài agent thành công.";
                MessageBox.Show(this, result.Message, result.UwfWarning ? "Đã cài agent — cần kiểm tra UWF" : "Cài đặt thành công", MessageBoxButtons.OK,
                    result.UwfWarning ? MessageBoxIcon.Warning : MessageBoxIcon.Information);
                keyInput.Clear();
            }
            catch (Exception error)
            {
                status.Text = "Chưa cài hoàn tất. Xem thông báo lỗi để xử lý rồi thử lại.";
                MessageBox.Show(this, InstallerEngine.HideKey(error.Message, roomKey), "Không cài được", MessageBoxButtons.OK, MessageBoxIcon.Error);
            }
            finally
            {
                installing = false;
                keyInput.Enabled = vpnInput.Enabled = toggleKey.Enabled = closeButton.Enabled = true;
                progress.Visible = false;
                UpdateInstallButton();
            }
        }
    }
}
