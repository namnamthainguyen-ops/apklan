using System;
using System.IO;
using System.Security.Cryptography;
using System.Text;
using System.Windows.Forms;

static class ServerSmoke
{
    [STAThread] static void Main()
    {
        byte[] key;
        using(var kdf=new Rfc2898DeriveBytes("ROOM0001",Encoding.UTF8.GetBytes("LanPower/RoomKey/v1"),210000,HashAlgorithmName.SHA256))
            key=kdf.GetBytes(32);
        if(Convert.ToBase64String(key)!="VUnd9gqHWPnL3YsvZF69DuCPHFp4YrHzQbv+TVHKsuc=") throw new Exception("Room key mismatch");
        var room=new Room { Name="Test",Secret=Convert.ToBase64String(ProtectedData.Protect(key,null,DataProtectionScope.CurrentUser)) };
        if(!Lan.Verify(Lan.Sign(key,"discover","example"),room.Key(),"discover","example")) throw new Exception("HMAC mismatch");
        if(Lan.Verify(Lan.Sign(key,"discover","example"),room.Key(),"discover","other")) throw new Exception("Nonce substitution");
        if(Lan.Targets("192.168.5.0/24, 10.1.1.1").Count!=255) throw new Exception("CIDR expansion");
        try { Lan.Targets("8.8.8.8"); throw new Exception("Public target accepted"); } catch(FormatException) {}
        string encoded=Lan.Json.Serialize(room);
        if(encoded.Contains(Convert.ToBase64String(key))) throw new Exception("Stored room key was not protected");
        if(!Lan.Verify(Lan.Sign(key,"test"),Lan.Json.Deserialize<Room>(encoded).Key(),"test")) throw new Exception("Room import failed");
        using(var window=new ConsoleWindow()) { window.Show(); Application.DoEvents(); if(window.Text!="Room TN20 Server") throw new Exception("Window did not initialize"); }
        Console.WriteLine("SERVER_SMOKE=PASS (key derivation, DPAPI, HMAC, LAN/VPN targets)");
    }
}
