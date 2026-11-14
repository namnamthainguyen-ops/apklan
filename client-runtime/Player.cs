using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.IO.Pipes;
using System.Runtime.InteropServices;
using System.Runtime.Versioning;
using System.Security.Principal;
using System.Security.AccessControl;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Interop;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Threading;
using Screen = System.Windows.Forms.Screen;

[assembly: System.Reflection.AssemblyVersion("1.4.4.0")]
[assembly: System.Reflection.AssemblyFileVersion("1.4.4.0")]
[assembly: System.Reflection.AssemblyCompany("Công ty TNHH TN20")]
[assembly: System.Reflection.AssemblyProduct("Room TN20")]
[assembly: System.Reflection.AssemblyTitle("Room TN20 Display")]
[assembly: TargetFramework(".NETFramework,Version=v4.7.2")]

namespace RoomTN20.Runtime
{
    internal static class PlayerProgram
    {
        [STAThread]
        private static int Main(string[] arguments)
        {
            if (arguments.Length == 1 && arguments[0] == "--self-test")
            {
                string test = DataFiles.Hash(Encoding.ASCII.GetBytes("abc"));
                return test == "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad" && DataFiles.Extension("video/mp4") == ".mp4" ? 0 : 1;
            }
            string root = @"D:\RoomTN20Data";
            bool recovery = false;
            for (int index = 0; index < arguments.Length; index++)
            {
                if (arguments[index] == "--emergency-unlock") recovery = true;
                else if (arguments[index] == "--data-root" && index + 1 < arguments.Length) root = arguments[++index];
                else return 2;
            }
            try
            {
                root = DataFiles.CanonicalRoot(root);
                if (recovery)
                {
                    using (WindowsIdentity identity = WindowsIdentity.GetCurrent())
                        if (!new WindowsPrincipal(identity).IsInRole(WindowsBuiltInRole.Administrator))
                            throw new InvalidOperationException("Chạy lệnh khôi phục bằng quyền Administrator.");
                    string state = Path.Combine(root, "State");
                    DataFiles.RejectReparse(state);
                    if (!Directory.Exists(state)) throw new InvalidOperationException("Chưa có thư mục trạng thái Room TN20.");
                    string audit = DataFiles.Json.Serialize(new { requestedUtc = DateTime.UtcNow.ToString("o"), administrator = WindowsIdentity.GetCurrent().Name });
                    DataFiles.AtomicText(Path.Combine(state, "recovery-unlock.json"), audit);
                    DataFiles.AtomicText(Path.Combine(state, "lock.json"), DataFiles.Json.Serialize(new { locked = false, recovery = true, changedUtc = DateTime.UtcNow.ToString("o") }));
                    return 0;
                }
                if (!Directory.Exists(root)) return 3;
                bool created;
                using (Mutex mutex = new Mutex(false, @"Local\" + DataFiles.PipeName(root) + ".Player." + Process.GetCurrentProcess().SessionId, out created))
                {
                    if (!created) return 0;
                    Application app = new Application { ShutdownMode = ShutdownMode.OnExplicitShutdown };
                    DisplayManager display = new DisplayManager(root, app);
                    app.Startup += delegate { display.Start(); };
                    app.Exit += delegate { display.Dispose(); };
                    app.Run();
                }
                return 0;
            }
            catch (Exception error)
            {
                if (recovery) MessageBox.Show(error.Message, "Room TN20 - Khôi phục quản trị", MessageBoxButton.OK, MessageBoxImage.Warning);
                return 1;
            }
        }
    }

    internal sealed class DisplayManager : IDisposable
    {
        private readonly string root;
        private readonly Application app;
        private readonly List<AdWindow> windows = new List<AdWindow>();
        private volatile bool disposed;
        private NamedPipeClientStream currentPipe;
        private bool locked;
        private string mediaVersion = "none";
        private string currentGeneration = "";
        private readonly DispatcherTimer monitorTimer;

        internal DisplayManager(string dataRoot, Application application)
        {
            root = dataRoot;
            app = application;
            monitorTimer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(2) };
            monitorTimer.Tick += delegate { if (locked && windows.Count != Screen.AllScreens.Length) Apply(true, mediaVersion); };
            monitorTimer.Start();
        }
        internal void Start() { ThreadPool.QueueUserWorkItem(delegate { ConnectLoop(); }); }
        private void ConnectLoop()
        {
            while (!disposed)
            {
                try
                {
                    // Request the same limited rights granted by the controller.
                    // PipeDirection.InOut requests generic write rights, including
                    // rights deliberately withheld from ordinary display users.
                    using (NamedPipeClientStream pipe = DataFiles.DisplayPipe(DataFiles.PipeName(root)))
                    {
                        currentPipe = pipe;
                        pipe.Connect(3000);
                        DataFiles.ValidateDisplayBroker(pipe);
                        using (StreamWriter writer = new StreamWriter(pipe, new UTF8Encoding(false), 4096, true))
                        {
                            while (!disposed && pipe.IsConnected)
                            {
                                Dictionary<string, object> state = DataFiles.Object(ReadLine(pipe, 4096));
                                if (DataFiles.Text(state, "type") == "desktop")
                                {
                                    string result = "";
                                    bool accepted = false;
                                    app.Dispatcher.Invoke(new Action(delegate
                                    {
                                        if (locked || !IsInteractiveDesktopActive()) return;
                                        accepted = true;
                                    }));
                                    if (accepted)
                                    {
                                        try { result = DesktopAction(DataFiles.Text(state, "action"), DataFiles.Text(state, "url")); }
                                        catch { accepted = false; }
                                    }
                                    writer.WriteLine(DataFiles.Json.Serialize(new { desktopRequest = DataFiles.Text(state, "request"), status = accepted ? "accepted" : "rejected", result = result }));
                                    writer.Flush();
                                    continue;
                                }
                                if (DataFiles.Text(state, "type") != "state") continue;
                                string generation = DataFiles.Text(state, "generation");
                                string version = DataFiles.Text(state, "mediaVersion");
                                object wanted;
                                if (!Regex.IsMatch(generation, @"\A[0-9a-f]{32}\z") || !state.TryGetValue("locked", out wanted) || !(wanted is bool) ||
                                    (version != "none" && !Regex.IsMatch(version, @"\A[0-9a-f]{32}\z"))) throw new InvalidOperationException("DISPLAY_STATE_INVALID");
                                bool visible = false;
                                app.Dispatcher.Invoke(new Action(delegate
                                {
                                    Apply((bool)wanted, version);
                                    currentGeneration = generation;
                                }), DispatcherPriority.Normal);
                                // ACK follows a WPF render turn and checks all monitor windows.
                                app.Dispatcher.Invoke(new Action(delegate
                                {
                                    visible = (bool)wanted ? windows.Count == Screen.AllScreens.Length && windows.TrueForAll(delegate(AdWindow window) { return window.IsVisible && new WindowInteropHelper(window).Handle != IntPtr.Zero; }) : windows.TrueForAll(delegate(AdWindow window) { return !window.IsVisible; });
                                }), DispatcherPriority.Render);
                                if (!visible) throw new InvalidOperationException("DISPLAY_STATE_NOT_PRESENTED");
                                writer.WriteLine(DataFiles.Json.Serialize(new { generation = generation, state = IsInteractiveDesktopActive() ? ((bool)wanted ? "locked" : "unlocked") : "windows-locked", mediaVersion = version, monitors = windows.Count }));
                                writer.Flush();
                            }
                        }
                    }
                }
                catch { /* Keep the last confirmed overlay; retry the privileged controller. */ }
                finally { currentPipe = null; }
                if (!disposed) Thread.Sleep(2000);
            }
        }
        private static string DesktopAction(string action, string url)
        {
            if (action == "open-web")
            {
                Uri uri;
                if (!Uri.TryCreate(url, UriKind.Absolute, out uri) || (uri.Scheme != "http" && uri.Scheme != "https") ||
                    String.IsNullOrEmpty(uri.Host) || !String.IsNullOrEmpty(uri.UserInfo))
                    throw new InvalidOperationException("INVALID_WEB_URL");
                var start = new System.Diagnostics.ProcessStartInfo(uri.AbsoluteUri) { UseShellExecute = true };
                using (var browser = System.Diagnostics.Process.Start(start)) { }
                return "Browser launch requested.";
            }
            if (action != "close-apps") throw new InvalidOperationException("INVALID_DESKTOP_ACTION");
            int failed = 0;
            var requested = new List<System.Diagnostics.Process>();
            using (var self = System.Diagnostics.Process.GetCurrentProcess())
            {
                foreach (var process in System.Diagnostics.Process.GetProcesses())
                using (process)
                {
                    try
                    {
                        if (process.Id == self.Id || process.SessionId != self.SessionId ||
                            process.ProcessName.Equals("explorer", StringComparison.OrdinalIgnoreCase) || process.MainWindowHandle == IntPtr.Zero) continue;
                        if (process.CloseMainWindow()) requested.Add(System.Diagnostics.Process.GetProcessById(process.Id)); else failed++;
                    }
                    catch { failed++; }
                }
            }
            if (requested.Count > 0) Thread.Sleep(1200);
            int remaining = 0;
            foreach (var process in requested)
            using (process)
            {
                try { process.Refresh(); if (!process.HasExited && process.MainWindowHandle != IntPtr.Zero) remaining++; }
                catch { failed++; }
            }
            return "Close requests: " + requested.Count + "; still open: " + remaining + "; unavailable: " + failed + ".";
        }
        private void Apply(bool desired, string version)
        {
            bool reload = version != mediaVersion || (desired && !locked);
            locked = desired;
            mediaVersion = version;
            if (desired && windows.Count != Screen.AllScreens.Length)
            {
                foreach (AdWindow old in windows) { old.AllowClose = true; old.Close(); }
                windows.Clear();
                foreach (Screen screen in Screen.AllScreens) windows.Add(new AdWindow(root, screen));
                reload = true;
            }
            foreach (AdWindow window in windows)
            {
                if (desired)
                {
                    if (reload) window.LoadPlaylist(version);
                    window.Show();
                    window.BringToMonitor();
                }
                else { window.StopMedia(); window.Hide(); }
            }
        }
        public void Dispose()
        {
            disposed = true;
            monitorTimer.Stop();
            if (currentPipe != null) currentPipe.Dispose();
            foreach (AdWindow window in windows) { window.AllowClose = true; window.Close(); }
            windows.Clear();
        }
        private static string ReadLine(Stream input, int limit)
        {
            using (MemoryStream output = new MemoryStream())
            {
                while (output.Length <= limit)
                {
                    int next = input.ReadByte();
                    if (next < 0) throw new EndOfStreamException();
                    if (next == 10) return new UTF8Encoding(false, true).GetString(output.ToArray()).TrimEnd('\r');
                    output.WriteByte((byte)next);
                }
                throw new InvalidOperationException("DISPLAY_MESSAGE_TOO_LARGE");
            }
        }
        private static string ProcessImage(int processId)
        {
            IntPtr process = OpenProcess(0x1000, false, processId);
            if (process == IntPtr.Zero) throw new InvalidOperationException("DISPLAY_SERVER_UNAVAILABLE");
            try
            {
                StringBuilder path = new StringBuilder(32768);
                int length = path.Capacity;
                if (!QueryFullProcessImageName(process, 0, path, ref length)) throw new InvalidOperationException("DISPLAY_SERVER_PATH_UNAVAILABLE");
                return Path.GetFullPath(path.ToString());
            }
            finally { CloseHandle(process); }
        }
        private static bool IsInteractiveDesktopActive()
        {
            IntPtr desktop = OpenInputDesktop(0, false, 0x0001);
            if (desktop == IntPtr.Zero) return false;
            try
            {
                StringBuilder name = new StringBuilder(256);
                int required;
                return GetUserObjectInformation(desktop, 2, name, name.Capacity * 2, out required) && String.Equals(name.ToString(), "Default", StringComparison.OrdinalIgnoreCase);
            }
            finally { CloseDesktop(desktop); }
        }
        [DllImport("user32.dll", SetLastError = true)] private static extern IntPtr OpenInputDesktop(int flags, bool inherit, int desiredAccess);
        [DllImport("user32.dll", CharSet = CharSet.Unicode, SetLastError = true)] private static extern bool GetUserObjectInformation(IntPtr handle, int index, StringBuilder information, int length, out int needed);
        [DllImport("user32.dll")] private static extern bool CloseDesktop(IntPtr desktop);
        [DllImport("kernel32.dll", SetLastError = true)] private static extern bool GetNamedPipeServerProcessId(IntPtr pipe, out uint processId);
        [DllImport("kernel32.dll", SetLastError = true)] private static extern IntPtr OpenProcess(int access, bool inheritHandle, int processId);
        [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)] private static extern bool QueryFullProcessImageName(IntPtr process, int flags, StringBuilder path, ref int length);
        [DllImport("kernel32.dll")] private static extern bool CloseHandle(IntPtr handle);
    }

    internal sealed class AdWindow : Window
    {
        internal bool AllowClose;
        private readonly string root;
        private readonly Screen screen;
        private readonly Grid surface = new Grid();
        private readonly DispatcherTimer advance = new DispatcherTimer();
        private readonly List<Dictionary<string, object>> playlist = new List<Dictionary<string, object>>();
        private int current;
        private string directory;
        private MediaElement video;

        internal AdWindow(string dataRoot, Screen monitor)
        {
            root = dataRoot;
            screen = monitor;
            Title = "Room TN20 — Máy đang khóa sử dụng";
            WindowStyle = WindowStyle.None;
            ResizeMode = ResizeMode.NoResize;
            ShowInTaskbar = false;
            Topmost = true;
            Background = Brushes.Black;
            Content = surface;
            Left = monitor.Bounds.Left; Top = monitor.Bounds.Top; Width = monitor.Bounds.Width; Height = monitor.Bounds.Height;
            SourceInitialized += delegate { BringToMonitor(); };
            Closing += delegate(object sender, System.ComponentModel.CancelEventArgs eventArgs) { if (!AllowClose) eventArgs.Cancel = true; };
            advance.Tick += delegate { PlayNext(); };
            ShowDefault();
        }
        internal void BringToMonitor()
        {
            IntPtr handle = new WindowInteropHelper(this).Handle;
            if (handle != IntPtr.Zero) SetWindowPos(handle, new IntPtr(-1), screen.Bounds.X, screen.Bounds.Y, screen.Bounds.Width, screen.Bounds.Height, 0x0040);
        }
        internal void LoadPlaylist(string version)
        {
            StopMedia();
            playlist.Clear();
            current = -1;
            try
            {
                if (!Regex.IsMatch(version, @"\A[0-9a-f]{32}\z")) { ShowDefault(); return; }
                directory = Path.Combine(root, "Media", version);
                DataFiles.RejectReparse(directory);
                string path = Path.Combine(directory, "playlist.json");
                DataFiles.RejectReparse(path);
                if (new FileInfo(path).Length > 24000) throw new InvalidOperationException("PLAYLIST_TOO_LARGE");
                Dictionary<string, object> manifest = DataFiles.Object(File.ReadAllText(path));
                foreach (object item in DataFiles.Items(manifest, "files"))
                {
                    Dictionary<string, object> metadata = item as Dictionary<string, object>;
                    if (metadata == null || !Regex.IsMatch(DataFiles.Text(metadata, "id"), @"\A[0-9a-f]{64}\z")) throw new InvalidOperationException("PLAYLIST_INVALID");
                    DataFiles.Extension(DataFiles.Text(metadata, "type"));
                    playlist.Add(metadata);
                }
                if (playlist.Count > 20) throw new InvalidOperationException("PLAYLIST_TOO_LARGE");
                PlayNext();
            }
            catch { playlist.Clear(); ShowDefault(); }
        }
        private void PlayNext()
        {
            StopMedia();
            if (playlist.Count == 0) { ShowDefault(); return; }
            current = (current + 1) % playlist.Count;
            try
            {
                Dictionary<string, object> item = playlist[current];
                string type = DataFiles.Text(item, "type");
                string path = Path.Combine(directory, DataFiles.Text(item, "id") + DataFiles.Extension(type));
                DataFiles.RejectReparse(path);
                surface.Children.Clear();
                if (type == "video/mp4")
                {
                    video = new MediaElement { LoadedBehavior = MediaState.Manual, UnloadedBehavior = MediaState.Manual, Stretch = Stretch.Uniform, Volume = 0, IsMuted = true };
                    video.MediaEnded += delegate { PlayNext(); };
                    video.MediaFailed += delegate { ShowFallbackAndContinue(); };
                    surface.Children.Add(video);
                    video.Source = new Uri(path);
                    video.Play();
                    long duration = DataFiles.Number(item, "durationSeconds");
                    advance.Interval = TimeSpan.FromSeconds(duration > 0 ? duration : 3600);
                }
                else
                {
                    BitmapImage image = new BitmapImage();
                    image.BeginInit(); image.CacheOption = BitmapCacheOption.OnLoad; image.UriSource = new Uri(path); image.EndInit(); image.Freeze();
                    surface.Children.Add(new Image { Source = image, Stretch = Stretch.Uniform });
                    advance.Interval = TimeSpan.FromSeconds(Math.Max(1, DataFiles.Number(item, "durationSeconds")));
                }
                advance.Start();
            }
            catch { ShowFallbackAndContinue(); }
        }
        private void ShowFallbackAndContinue()
        {
            StopMedia();
            ShowDefault();
            advance.Interval = TimeSpan.FromSeconds(10);
            advance.Start();
        }
        internal void StopMedia()
        {
            advance.Stop();
            if (video != null) { video.Stop(); video.Close(); video.Source = null; video = null; }
        }
        private void ShowDefault()
        {
            surface.Children.Clear();
            StackPanel text = new StackPanel { VerticalAlignment = VerticalAlignment.Center, HorizontalAlignment = HorizontalAlignment.Center };
            text.Children.Add(new TextBlock { Text = "Room TN20", Foreground = Brushes.White, FontSize = 64, FontWeight = FontWeights.Bold, TextAlignment = TextAlignment.Center });
            text.Children.Add(new TextBlock { Text = "Máy đang khóa sử dụng\nVui lòng liên hệ quản trị để mở máy", Foreground = Brushes.LightGray, FontSize = 28, TextAlignment = TextAlignment.Center, Margin = new Thickness(0, 24, 0, 0) });
            text.Children.Add(new TextBlock { Text = "Công ty TNHH TN20 · 20.com.vn", Foreground = Brushes.Gray, FontSize = 18, TextAlignment = TextAlignment.Center, Margin = new Thickness(0, 40, 0, 0) });
            surface.Children.Add(text);
        }
        [DllImport("user32.dll")] private static extern bool SetWindowPos(IntPtr handle, IntPtr insertAfter, int x, int y, int width, int height, int flags);
    }
}
