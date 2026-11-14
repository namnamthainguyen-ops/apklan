using System;
using System.Collections.Generic;
using System.Drawing;
using System.IO;
using System.Linq;
using System.Net.Security;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Security.Authentication;
using System.Text;
using System.Threading.Tasks;
using System.Windows.Forms;

static class Content
{
    internal static string Send(Room room, Machine machine, string root)
    {
        if (machine.Misses != 0) return "Chờ gửi khi máy online";
        if (!machine.Supports("mediasync") || !System.Text.RegularExpressions.Regex.IsMatch(machine.Certificate, "^[a-f0-9]{64}$")) return "Máy chưa xác thực bộ nhận quảng cáo";
        var files=room.Playlist;
        var payload=Encoding.UTF8.GetBytes(Lan.Json.Serialize(new {schema=1,version=room.MediaVersion,files=files.Select(f=>new {id=f.Id,name=f.Name,type=f.Type,length=f.Length,sha256=f.Id,durationSeconds=f.Type=="video/mp4"?0:15}).ToArray()}));
        var key=room.Key(); string nonce=Lan.Nonce(), version=room.MediaVersion;
        long time=Lan.Timestamp(), total=files.Sum(f=>f.Length);
        string signature=Lan.Sign(key,"media",machine.Id,time.ToString(),nonce,version,total.ToString(),Lan.Hash(payload),"delta-v1");
        using(var tcp=new TcpClient())
        using(var deadline=new System.Threading.Timer(_=>tcp.Close(),null,TimeSpan.FromMinutes(10),System.Threading.Timeout.InfiniteTimeSpan))
        {
            var connect=tcp.ConnectAsync(machine.Ip,17842);
            if(!connect.Wait(10000)) throw new IOException("Kết nối quảng cáo quá hạn.");
            using(var tls=new SslStream(tcp.GetStream(),false,(sender,cert,chain,errors)=>{
                if(cert==null || Lan.Hash(cert.GetRawCertData())!=machine.Certificate) return false;
                using(var x=new X509Certificate2(cert)) return DateTime.Now>=x.NotBefore && DateTime.Now<=x.NotAfter;
            }))
            {
                tls.ReadTimeout=tls.WriteTimeout=120000;
                tls.AuthenticateAsClient("RoomTN20",null,SslProtocols.Tls12,false);
                var header=Encoding.UTF8.GetBytes(Lan.Json.Serialize(new {deviceId=machine.Id,timestamp=time,nonce,mediaVersion=version,totalBytes=total,payload=Convert.ToBase64String(payload),transferMode="delta-v1",signature})+"\n");
                if(header.Length>32768) throw new IOException("Danh sách phát quá dài.");
                tls.Write(header,0,header.Length);tls.Flush();
                var plan=Read(tls);
                Bind(plan,machine.Id,nonce,version);
                if(Lan.Get(plan,"type")!="media-plan") throw new IOException("Máy chưa chấp nhận lập kế hoạch gửi.");
                byte[] planBytes=Convert.FromBase64String(Lan.Get(plan,"payload"));
                if(!Lan.Verify(Lan.Get(plan,"signature"),key,"media-plan",nonce,machine.Id,version,Lan.Hash(planBytes))) throw new IOException("Kế hoạch gửi chưa xác thực.");
                var missing=new HashSet<string>();
                foreach(object id in (object[])Lan.Json.DeserializeObject(Encoding.UTF8.GetString(planBytes)).AsObject()["missing"])
                    if(!files.Any(f=>f.Id==(string)id) || !missing.Add((string)id)) throw new IOException("Kế hoạch gửi không hợp lệ.");
                foreach(var file in files.Where(f=>missing.Contains(f.Id)))
                    using(var input=File.OpenRead(Path.Combine(root,file.Id)))
                    using(var sha=SHA256.Create())
                    {
                        if(input.Length!=file.Length || Lan.Hex(sha.ComputeHash(input))!=file.Id) throw new IOException("Tệp cục bộ thay đổi: "+file.Name);
                        input.Position=0;input.CopyTo(tls);
                    }
                tls.Flush();
                var result=Read(tls); Bind(result,machine.Id,nonce,version);
                string status=Lan.Get(result,"status"),message=Lan.Get(result,"message");
                if(!Lan.Verify(Lan.Get(result,"signature"),key,"media-result",nonce,machine.Id,version,status,message)) throw new IOException("Kết quả chưa xác thực.");
                if(status!="accepted") return "Bị từ chối • "+message;
                machine.MediaVersion=version;
                return "Đã đồng bộ • Tải "+missing.Count+" • Dùng lại "+(files.Count-missing.Count);
            }
        }
    }
    static Dictionary<string,object> AsObject(this object value) { return (Dictionary<string,object>)value; }
    static void Bind(Dictionary<string,object> data,string id,string nonce,string version)
    {
        if(Lan.Get(data,"deviceId")!=id || Lan.Get(data,"nonce")!=nonce || Lan.Get(data,"version")!=version) throw new IOException("Phản hồi quảng cáo sai phiên.");
    }
    static Dictionary<string,object> Read(Stream input)
    {
        using(var bytes=new MemoryStream())
        for(int value;(value=input.ReadByte())>=0;)
        {
            if(value==10) return Lan.Json.Deserialize<Dictionary<string,object>>(Encoding.UTF8.GetString(bytes.ToArray()));
            if(bytes.Length>=65536) break;
            bytes.WriteByte((byte)value);
        }
        throw new IOException("Phản hồi quảng cáo thiếu hoặc quá dài.");
    }
}

sealed class ConsoleWindow : Form
{
    static readonly Color Navy=Color.FromArgb(12,37,68), Teal=Color.FromArgb(0,109,132);
    readonly string root=Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),"RoomTN20","Server");
    readonly List<Room> rooms=new List<Room>();
    readonly ComboBox roomPicker=new ComboBox{DropDownStyle=ComboBoxStyle.DropDownList,Width=210};
    readonly Label heading=new Label{AutoSize=true,Font=new Font("Segoe UI",24,FontStyle.Bold)};
    readonly Label status=new Label{Dock=DockStyle.Bottom,Height=30};
    readonly Panel body=new Panel{Dock=DockStyle.Fill};
    readonly FlowLayoutPanel tools=new FlowLayoutPanel{Dock=DockStyle.Top,Height=43};
    readonly System.Windows.Forms.Timer timer=new System.Windows.Forms.Timer{Interval=20000};
    readonly HashSet<string> selected=new HashSet<string>();
    readonly Dictionary<Room,DateTime> lastFull=new Dictionary<Room,DateTime>();
    string page="Phòng máy",filter="",focused="";
    bool busy,storageFailed,modal;
    Room Current { get { return roomPicker.SelectedItem as Room; } }
    string MediaRoot { get { return Path.Combine(root,"media"); } }
    string Journal { get { return Path.Combine(root,"journal.jsonl"); } }
    internal ConsoleWindow()
    {
        Text="Room TN20 Server"; Width=1320;Height=800;MinimumSize=new Size(980,660);
        Font=new Font("Segoe UI",10);BackColor=Color.White;ForeColor=Navy;
        var nav=new FlowLayoutPanel{Dock=DockStyle.Left,Width=195,BackColor=Navy,FlowDirection=FlowDirection.TopDown,WrapContents=false,Padding=new Padding(12)};
        nav.Controls.Add(new Label{Text="TN20\nRoom TN20 Server",ForeColor=Color.White,Font=new Font("Segoe UI",17,FontStyle.Bold),Height=82,Width=168});
        foreach(string label in new[]{"Tổng quan","Phòng máy","Quảng cáo","Cập nhật","Nhật ký"})
        { var button=Button(label,()=>{page=label;Render();});button.Width=165;nav.Controls.Add(button); }
        var main=new Panel{Dock=DockStyle.Fill,Padding=new Padding(20)};
        var banner=new Panel{Dock=DockStyle.Top,Height=55};banner.Controls.Add(heading);
        main.Controls.Add(body);main.Controls.Add(tools);main.Controls.Add(banner);main.Controls.Add(status);
        main.Controls.Add(new Label{Dock=DockStyle.Bottom,Height=26,Text="Công ty TNHH TN20 · 20.com.vn"});
        Controls.Add(main);Controls.Add(nav);
        roomPicker.DisplayMember="Name";
        try
        {
            Directory.CreateDirectory(MediaRoot);
            string config=Path.Combine(root,"rooms.json");
            if(File.Exists(config)) rooms.AddRange(Lan.Json.Deserialize<List<Room>>(File.ReadAllText(config)));
        }
        catch(Exception e){storageFailed=true;status.Text="Không đọc được dữ liệu phòng: "+e.Message;}
        foreach(var room in rooms) roomPicker.Items.Add(room);
        roomPicker.SelectedIndexChanged+=(s,e)=>{selected.Clear();focused="";filter="";Render();};
        if(rooms.Count>0) roomPicker.SelectedIndex=0;
        FormClosing+=(s,e)=>{if(busy)e.Cancel=true;};
        timer.Tick+=async(s,e)=>{if(Current!=null && !busy && !modal) await Scan(false);};
        timer.Start();Render();
        Shown+=async(s,e)=>{if(Current!=null) await Scan(false);};
    }
    Button Button(string text,Action action)
    {
        var button=new Button{Text=text,AutoSize=true,Height=34,FlatStyle=FlatStyle.Flat,ForeColor=Color.White,BackColor=Teal,Padding=new Padding(7,0,7,0)};
        button.Click+=(s,e)=>action();return button;
    }
    void Toolbar(string name,Action action){var b=Button(name,action);b.Enabled=!busy;tools.Controls.Add(b);}
    void Save()
    {
        if(storageFailed) throw new IOException("Dữ liệu phòng cần được khôi phục trước khi ghi.");
        string path=Path.Combine(root,"rooms.json"),tmp=path+".new";
        File.WriteAllText(tmp,Lan.Json.Serialize(rooms),Encoding.UTF8);
        if(File.Exists(path)) File.Replace(tmp,path,null);else File.Move(tmp,path);
    }
    void EditRoom(bool edit)
    {
        if(storageFailed) return;
        var current=edit?Current:null;
        using(var dialog=new Form{Text=edit?"Sửa phòng":"Thêm phòng",Width=440,Height=310,StartPosition=FormStartPosition.CenterParent,FormBorderStyle=FormBorderStyle.FixedDialog,MinimizeBox=false,MaximizeBox=false})
        {
            var name=new TextBox{Width=375,Text=current==null?"":current.Name};
            var key=new TextBox{Width=375,UseSystemPasswordChar=true,MaxLength=8};
            var vpn=new TextBox{Width=375,Text=current==null?"":current.Networks};
            var form=new FlowLayoutPanel{Dock=DockStyle.Fill,FlowDirection=FlowDirection.TopDown,WrapContents=false,Padding=new Padding(14)};
            foreach(Control control in new Control[]{new Label{Text="Tên phòng",AutoSize=true},name,new Label{Text="Key phòng (8 ký tự)",AutoSize=true},key,new Label{Text="Đích LAN/VPN (IPv4 hoặc CIDR, cách nhau bằng dấu phẩy)",AutoSize=true},vpn})form.Controls.Add(control);
            form.Controls.Add(Button("Lưu",()=>{
                try
                {
                    if(string.IsNullOrWhiteSpace(name.Text)||name.Text.Trim().Length>80)throw new FormatException("Tên phòng phải dài từ 1 đến 80 ký tự.");
                    if(rooms.Any(r=>r!=current && string.Equals(r.Name,name.Text.Trim(),StringComparison.OrdinalIgnoreCase)))throw new FormatException("Tên phòng đã được dùng.");
                    Lan.Targets(vpn.Text);
                    string secret=current==null?null:current.Secret;
                    if(current==null || key.Text.Length>0)
                    {
                        if(!System.Text.RegularExpressions.Regex.IsMatch(key.Text,@"\A[\x21-\x7e]{8}\z"))throw new FormatException("Key phải có đúng 8 ký tự ASCII in được.");
                        using(var kdf=new Rfc2898DeriveBytes(key.Text,Encoding.UTF8.GetBytes("LanPower/RoomKey/v1"),210000,HashAlgorithmName.SHA256))
                            secret=Convert.ToBase64String(ProtectedData.Protect(kdf.GetBytes(32),null,DataProtectionScope.CurrentUser));
                    }
                    if(current!=null && key.Text.Length>0 && !Current.Key().SequenceEqual(ProtectedData.Unprotect(Convert.FromBase64String(secret),null,DataProtectionScope.CurrentUser)))
                        throw new FormatException("Để quản lý máy bằng key khác, hãy tạo phòng mới.");
                    var room=current??new Room();
                    string oldName=room.Name,oldNetwork=room.Networks;
                    room.Name=name.Text.Trim();room.Networks=vpn.Text;room.Secret=secret;
                    if(current==null)rooms.Add(room);
                    try{Save();}catch{if(current==null)rooms.Remove(room);else{room.Name=oldName;room.Networks=oldNetwork;}throw;}
                    if(current==null)roomPicker.Items.Add(room);else{int index=rooms.IndexOf(room);roomPicker.Items[index]=room;lastFull.Remove(room);}
                    roomPicker.SelectedItem=room;dialog.DialogResult=DialogResult.OK;
                }catch(Exception e){MessageBox.Show(dialog,e.Message,"Room TN20");}
            }));
            dialog.Controls.Add(form);
            modal=true;
            try{dialog.ShowDialog(this);}finally{modal=false;}
            Render();
        }
    }
    async Task Scan(bool force)
    {
        var room=Current;if(room==null||busy)return;
        DateTime last;
        bool full=force||room.Machines.Count==0||!lastFull.TryGetValue(room,out last)||DateTime.UtcNow-last>=TimeSpan.FromMinutes(5);
        await Work(full?"Đang tìm máy mới…":"Đang quét nhanh…",async()=>{
            var found=await Task.Run(()=>Lan.Discover(room,full));
            var old=room.Machines.ToDictionary(m=>m.Identity());
            foreach(var machine in old.Values)machine.Misses=Math.Min(2,machine.Misses+1);
            foreach(var machine in found)
            {
                Machine previous;
                if(old.TryGetValue(machine.Identity(),out previous))
                {
                    machine.Result=previous.Result;
                    if(previous.BootBefore!="" && machine.BootToken()!="" && previous.BootBefore!=machine.BootToken())
                        machine.Result="Đã xác nhận máy khởi động lại và online";
                    else { machine.BootBefore=previous.BootBefore;machine.RestartDeadline=previous.RestartDeadline; }
                }
                old[machine.Identity()]=machine;
            }
            foreach(var machine in old.Values)
                if(machine.BootBefore!="" && DateTime.UtcNow>=machine.RestartDeadline)
                {machine.Result="Chưa xác nhận khởi động lại trong 5 phút";machine.BootBefore="";}
            room.Machines=old.Values.OrderBy(m=>m.Name,StringComparer.CurrentCultureIgnoreCase).ToList();
            if(full)lastFull[room]=DateTime.UtcNow;
            Save();status.Text="Đã nhận "+found.Count+" máy • "+DateTime.Now.ToString("HH:mm:ss");
        });
    }
    async Task Work(string message,Func<Task> run)
    {
        if(busy)return;
        busy=true;status.Text=message;Render();
        try{await run();}catch(Exception e){status.Text=e.GetBaseException().Message;}
        finally{busy=false;Render();}
    }
    void Log(Room room,Machine machine,string action,string result)
    {
        File.AppendAllText(Journal,Lan.Json.Serialize(new {time=DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss"),room=room.Name,machine=machine.Name,action,result})+Environment.NewLine,Encoding.UTF8);
    }
    async Task Perform(string action,string label,bool media=false)
    {
        var room=Current;if(room==null||busy)return;
        var targets=room.Machines.Where(m=>media||selected.Contains(m.Identity())).ToList();
        if(targets.Count==0){status.Text="Chọn ít nhất một máy.";return;}
        if(media&&string.IsNullOrEmpty(room.MediaVersion)){status.Text="Thêm nội dung quảng cáo trước khi gửi.";return;}
        modal=true;
        DialogResult confirm;
        try{confirm=MessageBox.Show(this,label+" cho "+targets.Count+" máy thuộc phòng "+room.Name+"?","Xác nhận",MessageBoxButtons.OKCancel);}
        finally{modal=false;}
        if(confirm!=DialogResult.OK)return;
        bool logged=true;
        await Work(label+"…",async()=>{
            var verified=action=="wake"?null:(await Task.Run(()=>Lan.Discover(room,false))).ToDictionary(m=>m.Identity());
            int done=0;
            foreach(var machine in targets)
            {
                string result;
                try
                {
                    Machine destination=machine;
                    if(verified!=null&&!verified.TryGetValue(machine.Identity(),out destination)) result="Chưa phản hồi • Chưa gửi lệnh";
                    else
                    {
                        result=await Task.Run(()=>media?Content.Send(room,destination,MediaRoot):action=="wake"?Lan.Wake(room,destination):Lan.Command(room,destination,action));
                        if(media)machine.MediaVersion=destination.MediaVersion;
                    }
                    if(action=="restart" && (result.StartsWith("Đã gửi") || result.StartsWith("Chưa xác nhận")) && machine.BootToken()!="")
                    {machine.BootBefore=machine.BootToken();machine.RestartDeadline=DateTime.UtcNow.AddMinutes(5);}
                }
                catch(Exception e){result="Lỗi • "+e.GetBaseException().Message;}
                machine.Result=result;
                try{Log(room,machine,label,result);}catch(IOException){logged=false;}catch(UnauthorizedAccessException){logged=false;}
                status.Text=(++done)+"/"+targets.Count+" • "+machine.Name+" • "+result;
            }
            if(!logged)status.Text+=" • Không ghi được nhật ký";
            MessageBox.Show(this,string.Join(Environment.NewLine,targets.Select(m=>m.Name+" • "+m.Result)),"Kết quả từng máy");
        });
    }
    async Task AddMedia()
    {
        var room=Current;
        using(var picker=new OpenFileDialog{Multiselect=true,Filter="Ảnh JPEG, PNG và video MP4|*.jpg;*.jpeg;*.png;*.mp4"})
        {
            modal=true;
            DialogResult choice;
            try{choice=picker.ShowDialog(this);}finally{modal=false;}
            if(choice!=DialogResult.OK)return;
            await Work("Đang nhập quảng cáo…",async()=>{
                var next=await Task.Run(()=>{
                    var list=new List<Media>(room.Playlist);
                    foreach(string path in picker.FileNames)
                    {
                        using(var input=File.OpenRead(path))
                        using(var sha=SHA256.Create())
                        {
                            if(input.Length<1||input.Length>134217728)throw new IOException("Mỗi tệp tối đa 128 MiB.");
                            string id=Lan.Hex(sha.ComputeHash(input));if(list.Any(m=>m.Id==id))continue;
                            if(list.Count==20||list.Sum(m=>m.Length)+input.Length>268435456)throw new IOException("Tối đa 20 tệp và 256 MiB mỗi phòng.");
                            string ext=Path.GetExtension(path).ToLowerInvariant();
                            if(ext!=".mp4"&&ext!=".png"&&ext!=".jpeg"&&ext!=".jpg")throw new IOException("Định dạng không hỗ trợ.");
                            if(Path.GetFileName(path).Length>120)throw new IOException("Tên tệp tối đa 120 ký tự.");
                            string output=Path.Combine(MediaRoot,id);
                            if(!File.Exists(output))
                            {
                                string tmp=output+".new";
                                try{input.Position=0;using(var f=File.Create(tmp))input.CopyTo(f);File.Move(tmp,output);}
                                finally{if(File.Exists(tmp))File.Delete(tmp);}
                            }
                            list.Add(new Media{Id=id,Name=Path.GetFileName(path),Length=input.Length,Type=ext==".mp4"?"video/mp4":ext==".png"?"image/png":"image/jpeg"});
                        }
                    }
                    return list;
                });
                if(next.Count!=room.Playlist.Count) SavePlaylist(room,next);
                status.Text="Đã lưu "+room.Playlist.Count+" tệp quảng cáo";
            });
        }
    }
    void SavePlaylist(Room room,List<Media> next)
    {
        var previous=room.Playlist;string version=room.MediaVersion;
        room.Playlist=next;room.MediaVersion=Lan.Nonce();
        try{Save();}catch{room.Playlist=previous;room.MediaVersion=version;throw;}
    }
    void Render()
    {
        if(IsDisposed)return;
        foreach(Control control in body.Controls.Cast<Control>().ToArray())control.Dispose();
        foreach(Control control in tools.Controls.Cast<Control>().ToArray())if(control!=roomPicker)control.Dispose();
        tools.Controls.Clear();heading.Text=page;
        if(page=="Nhật ký"){RenderJournal();return;}
        var room=Current;
        roomPicker.Enabled=!busy;tools.Controls.Add(roomPicker);
        Toolbar("Thêm phòng",()=>EditRoom(false));
        if(room==null)return;
        Toolbar("Sửa phòng",()=>EditRoom(true));
        Toolbar("Quét LAN",async()=>await Scan(true));
        if(page=="Quảng cáo"){RenderMedia(room);return;}
        if(page=="Tổng quan")
        {
            var list=new ListBox{Dock=DockStyle.Fill};
            foreach(var r in rooms)list.Items.Add(r.Name+"  •  "+r.Machines.Count+" máy  •  "+r.Machines.Count(m=>m.Misses==0)+" online");
            list.DoubleClick+=(s,e)=>{if(list.SelectedIndex>=0){page="Phòng máy";roomPicker.SelectedIndex=list.SelectedIndex;Render();}};
            body.Controls.Add(list);return;
        }
        if(page=="Phòng máy")
        {
            Toolbar("Bật máy",async()=>await Perform("wake","Bật máy"));
            Toolbar("Tắt máy",async()=>await Perform("shutdown","Tắt máy"));
            Toolbar("Khởi động lại",async()=>await Perform("restart","Khởi động lại"));
            Toolbar("Thao tác khác",()=>{
                var menu=new ContextMenuStrip();
                foreach(var item in new[]{new[]{"freeze","Đóng băng C:"},new[]{"unfreeze","Mở đóng băng C:"},new[]{"lock","Khóa màn hình"},new[]{"unlock","Mở màn hình"}})
                    menu.Items.Add(item[1],null,async(s,e)=>await Perform(item[0],item[1]));
                menu.Closed+=(s,e)=>menu.Dispose();
                menu.Show(Cursor.Position);
            });
        }
        RenderMachines(room);
    }
    void RenderMachines(Room room)
    {
        var counters=new FlowLayoutPanel{Dock=DockStyle.Top,Height=68};
        foreach(var entry in new[]{new[]{"","Máy đã lưu"},new[]{"Online","Online"},new[]{"Offline","Offline"},new[]{"Chưa phản hồi","Chưa phản hồi"}})
        {
            int count=room.Machines.Count(m=>entry[0]==""||m.Presence()==entry[0]);
            var b=Button(count+"  "+entry[1],()=>{filter=entry[0];Render();});
            b.MinimumSize=new Size(155,48);counters.Controls.Add(b);
        }
        var split=new SplitContainer{Dock=DockStyle.Fill,FixedPanel=FixedPanel.Panel2,Width=Math.Max(750,body.Width),SplitterDistance=Math.Max(450,body.Width-310)};
        var grid=new ListView{Dock=DockStyle.Fill,View=View.Details,FullRowSelect=true,CheckBoxes=page=="Phòng máy",HideSelection=false};
        foreach(var column in new[]{new[]{"Tên máy","150"},new[]{"Địa chỉ IP","135"},new[]{"Trạng thái","135"},new[]{"UWF ổ C","145"},new[]{"Phiên bản","85"},new[]{"Cập nhật","380"}})
            grid.Columns.Add(column[0],int.Parse(column[1]));
        var details=new TextBox{Dock=DockStyle.Fill,ReadOnly=true,Multiline=true,BackColor=Color.FromArgb(245,249,253),BorderStyle=BorderStyle.FixedSingle,ScrollBars=ScrollBars.Vertical};
        var shown=room.Machines.Where(m=>filter==""||m.Presence()==filter).ToList();
        foreach(var m in shown)
        {
            var row=new ListViewItem(new[]{m.Name,m.Ip,m.Presence(),m.Uwf,m.Version,m.Update}){Tag=m,Checked=selected.Contains(m.Identity()),Selected=m.Identity()==focused};
            if(m.Misses==0)row.SubItems[2].ForeColor=Color.DarkGreen;
            grid.Items.Add(row);
        }
        Action<Machine> describe=m=>{
            if(m==null){details.Text="Chọn máy để xem chi tiết.";return;}
            details.Text=string.Join("\r\n\r\n",new[]{m.Name+"  •  "+m.Presence(),"IP: "+m.Ip,"MAC: "+m.Mac,
                "Lần cuối thấy: "+m.Seen.ToLocalTime().ToString("dd/MM/yyyy HH:mm:ss"),"UWF hiện tại: "+m.Uwf.Split(' ')[0],
                "Sau khởi động lại: "+(System.Text.RegularExpressions.Regex.IsMatch(m.Uwf,@"^(ON|OFF) \(next (ON|OFF)\)$")?m.Uwf.Split(' ')[2].TrimEnd(')'):"Chưa xác định"),"Client: "+m.Version,
                "Cập nhật: "+m.Update+(m.Supports("pilotupdate")?" • Nhóm thử":""),"Kết quả: "+m.Result,
                room.Machines.Any(other=>other.Misses==0&&other.Id==m.Id&&other.Mac!=m.Mac)?"Cảnh báo: trùng ID client":""});
        };
        describe(shown.FirstOrDefault(m=>m.Identity()==focused));
        grid.ItemChecked+=(s,e)=>{var m=(Machine)e.Item.Tag;if(e.Item.Checked)selected.Add(m.Identity());else selected.Remove(m.Identity());};
        grid.SelectedIndexChanged+=(s,e)=>{if(grid.SelectedItems.Count>0){var m=(Machine)grid.SelectedItems[0].Tag;focused=m.Identity();describe(m);}};
        grid.Enabled=!busy;
        split.Panel1.Controls.Add(grid);split.Panel2.Controls.Add(details);
        body.Controls.Add(split);
        if(page=="Phòng máy")
        {
            var all=new CheckBox{Text="Chọn tất cả máy đang hiển thị",Dock=DockStyle.Top,Height=28,Enabled=!busy,Checked=shown.Count>0&&shown.All(m=>selected.Contains(m.Identity()))};
            all.CheckedChanged+=(s,e)=>{foreach(ListViewItem item in grid.Items)item.Checked=all.Checked;};
            body.Controls.Add(all);
        }
        body.Controls.Add(counters);
    }
    void RenderMedia(Room room)
    {
        Toolbar("Thêm ảnh / MP4",async()=>await AddMedia());
        Toolbar("Gửi cho phòng",async()=>await Perform("media","Đồng bộ quảng cáo",true));
        var list=new ListBox{Dock=DockStyle.Fill};
        foreach(var file in room.Playlist)list.Items.Add(file.Name+" • "+Math.Round(file.Length/1024d)+" KiB");
        Toolbar("Bỏ tệp đã chọn",()=>{
            if(list.SelectedIndex<0)return;
            try{var next=new List<Media>(room.Playlist);next.RemoveAt(list.SelectedIndex);SavePlaylist(room,next);Render();}
            catch(Exception e){status.Text=e.Message;}
        });
        var devices=new ListBox{Dock=DockStyle.Right,Width=335};
        foreach(var m in room.Machines)devices.Items.Add(m.Name+" • "+(m.Misses!=0?"Chờ máy online":string.IsNullOrEmpty(room.MediaVersion)?"Chưa có danh sách":m.MediaVersion==room.MediaVersion?"Đã đồng bộ":"Chưa đồng bộ"));
        body.Controls.Add(list);body.Controls.Add(devices);
    }
    void RenderJournal()
    {
        var box=new TextBox{Dock=DockStyle.Fill,Multiline=true,ReadOnly=true,ScrollBars=ScrollBars.Vertical};
        try
        {
            if(File.Exists(Journal))
            {
                var recent=new Queue<string>();
                foreach(string line in File.ReadLines(Journal)){if(recent.Count==300)recent.Dequeue();recent.Enqueue(line);}
                box.Lines=recent.Reverse().Select(line=>{
                    var item=Lan.Json.Deserialize<Dictionary<string,object>>(line);
                    return string.Join(" • ",new[]{"time","room","machine","action","result"}.Select(k=>Lan.Get(item,k)));
                }).ToArray();
            }
        }catch(Exception e){box.Text=e.Message;}
        body.Controls.Add(box);
    }
}
