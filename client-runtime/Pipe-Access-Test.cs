using System;
using System.IO;
using System.IO.Pipes;
using System.Runtime.InteropServices;
using System.Security.AccessControl;
using System.Security.Principal;
using RoomTN20.Runtime;

internal static class PipeAccessTest
{
    [StructLayout(LayoutKind.Sequential)] private struct Sid { public IntPtr Pointer; public uint Attributes; }
    [DllImport("advapi32.dll", SetLastError=true)] private static extern bool OpenProcessToken(IntPtr process, uint access, out IntPtr token);
    [DllImport("kernel32.dll")] private static extern IntPtr GetCurrentProcess();
    [DllImport("advapi32.dll", SetLastError=true)] private static extern bool CreateRestrictedToken(IntPtr existing, uint flags, uint disabledCount, ref Sid disabled, uint privilegeCount, IntPtr privileges, uint restrictedCount, IntPtr restricted, out IntPtr token);
    [DllImport("kernel32.dll")] private static extern bool CloseHandle(IntPtr handle);
    [DllImport("advapi32.dll", SetLastError=true)] private static extern bool DuplicateToken(IntPtr existing, int level, out IntPtr token);
    [DllImport("kernel32.dll", SetLastError=true)] private static extern IntPtr OpenProcess(uint access, bool inherit, int pid);
    [DllImport("kernel32.dll", CharSet=CharSet.Unicode, SetLastError=true)] private static extern bool QueryFullProcessImageName(IntPtr process, int flags, System.Text.StringBuilder value, ref int length);
    private static int Main(string[] args)
    {
        IntPtr original=IntPtr.Zero, restricted=IntPtr.Zero, sidMemory=IntPtr.Zero;
        try {
            if(!OpenProcessToken(GetCurrentProcess(), 0x000A, out original)) throw new Exception("Open token: "+Marshal.GetLastWin32Error());
            SecurityIdentifier admins=new SecurityIdentifier(WellKnownSidType.BuiltinAdministratorsSid,null);
            byte[] sid=new byte[admins.BinaryLength]; admins.GetBinaryForm(sid,0); sidMemory=Marshal.AllocHGlobal(sid.Length); Marshal.Copy(sid,0,sidMemory,sid.Length);
            Sid disabled=new Sid { Pointer=sidMemory, Attributes=0 };
            if(new WindowsPrincipal(WindowsIdentity.GetCurrent()).IsInRole(WindowsBuiltInRole.Administrator)) {
                if(!CreateRestrictedToken(original,1,1,ref disabled,0,IntPtr.Zero,0,IntPtr.Zero,out restricted)) throw new Exception("Restrict token: "+Marshal.GetLastWin32Error());
            } else if(!DuplicateToken(original,2,out restricted)) throw new Exception("Duplicate token: "+Marshal.GetLastWin32Error());
            PipeSecurity acl=new PipeSecurity(); acl.SetAccessRuleProtection(true,false);
            acl.AddAccessRule(new PipeAccessRule(admins,PipeAccessRights.FullControl,AccessControlType.Allow));
            DataFiles.GrantDisplayAccess(acl);
            string name="RoomTN20.PipeRegression."+Guid.NewGuid().ToString("N");
            using(NamedPipeServerStream server=new NamedPipeServerStream(name,PipeDirection.InOut,1,PipeTransmissionMode.Byte,PipeOptions.Asynchronous,4096,4096,acl)) {
                IAsyncResult wait=server.BeginWaitForConnection(null,null);
                using(WindowsIdentity identity=new WindowsIdentity(restricted))
                using(WindowsImpersonationContext context=identity.Impersonate()) {
                    if(new WindowsPrincipal(WindowsIdentity.GetCurrent()).IsInRole(WindowsBuiltInRole.Administrator)) throw new Exception("Test token is still administrator");
                    if(args.Length==2 && args[0]=="--system-process") {
                        DataFiles.ValidateDisplayProcess(Int32.Parse(args[1]));
                        Console.WriteLine("STANDARD_USER_SYSTEM_IDENTITY=PASS");
                    }
                    bool denied=false;
                    try { using(NamedPipeClientStream old=new NamedPipeClientStream(".",name,PipeDirection.InOut)) old.Connect(2000); }
                    catch(UnauthorizedAccessException) { denied=true; }
                    if(!denied) throw new Exception("Old generic-access constructor did not reproduce the failure");
                    using(NamedPipeClientStream client=DataFiles.DisplayPipe(name)) {
                        client.Connect(3000); server.EndWaitForConnection(wait);
                        client.GetAccessControl().GetOwner(typeof(SecurityIdentifier));
                        client.WriteByte(42); if(server.ReadByte()!=42) throw new Exception("Client data mismatch");
                        server.WriteByte(24); if(client.ReadByte()!=24) throw new Exception("Server data mismatch");
                    }
                }
            }
            Console.WriteLine("STANDARD_USER_PIPE=PASS (old access denied, production client connects and exchanges data under non-admin token; no accounts/GUI/locks installed)"); return 0;
        } catch(Exception error) { Console.Error.WriteLine(error); return 1; }
        finally { if(restricted!=IntPtr.Zero)CloseHandle(restricted); if(original!=IntPtr.Zero)CloseHandle(original); if(sidMemory!=IntPtr.Zero)Marshal.FreeHGlobal(sidMemory); }
    }
}
