using System;
using System.Collections.Generic;
using System.IO;
using System.Security.Cryptography;
using System.Text;
using System.Text.RegularExpressions;
using System.Web.Script.Serialization;

namespace RoomTN20.Runtime
{
    internal static class DataFiles
    {
        internal static JavaScriptSerializer Json { get { return new JavaScriptSerializer { MaxJsonLength = 262144, RecursionLimit = 16 }; } }
        internal static string CanonicalRoot(string value)
        {
            string full = Path.GetFullPath(value).TrimEnd(Path.DirectorySeparatorChar);
            if (!Path.IsPathRooted(value) || full.Length <= 3 || full.StartsWith(@"\\", StringComparison.Ordinal)) throw new InvalidOperationException("RUNTIME_DATA_PATH_INVALID");
            RejectReparse(full);
            return full;
        }
        internal static void RejectReparse(string path)
        {
            for (DirectoryInfo directory = new DirectoryInfo(path); directory != null; directory = directory.Parent)
                if (directory.Exists && (directory.Attributes & FileAttributes.ReparsePoint) != 0) throw new InvalidOperationException("RUNTIME_REPARSE_PATH_REJECTED");
            if (File.Exists(path) && (File.GetAttributes(path) & FileAttributes.ReparsePoint) != 0) throw new InvalidOperationException("RUNTIME_REPARSE_FILE_REJECTED");
        }
        internal static void AtomicText(string path, string value)
        {
            RejectReparse(path);
            string temporary = path + ".new-" + Guid.NewGuid().ToString("N");
            try
            {
                File.WriteAllText(temporary, value, new UTF8Encoding(false));
                if (File.Exists(path)) File.Replace(temporary, path, null);
                else File.Move(temporary, path);
            }
            finally { if (File.Exists(temporary)) File.Delete(temporary); }
        }
        internal static string Hash(byte[] bytes)
        {
            using (SHA256 sha = SHA256.Create()) return Hex(sha.ComputeHash(bytes));
        }
        internal static string Hex(byte[] bytes) { return BitConverter.ToString(bytes).Replace("-", "").ToLowerInvariant(); }
        internal static string PipeName(string root) { return "RoomTN20.Display." + Hash(Encoding.UTF8.GetBytes(CanonicalRoot(root).ToUpperInvariant())).Substring(0, 24); }
        internal static string Extension(string type)
        {
            if (type == "image/jpeg") return ".jpg";
            if (type == "image/png") return ".png";
            if (type == "video/mp4") return ".mp4";
            throw new InvalidOperationException("MEDIA_TYPE_UNSUPPORTED");
        }
        internal static Dictionary<string, object> Object(string text)
        {
            object value = Json.DeserializeObject(text);
            Dictionary<string, object> result = value as Dictionary<string, object>;
            if (result == null) throw new InvalidOperationException("JSON_OBJECT_REQUIRED");
            return result;
        }
        internal static string Text(Dictionary<string, object> value, string name)
        {
            object result;
            if (!value.TryGetValue(name, out result) || !(result is string)) throw new InvalidOperationException("FIELD_INVALID_" + name);
            return (string)result;
        }
        internal static long Number(Dictionary<string, object> value, string name)
        {
            object result;
            if (!value.TryGetValue(name, out result) || !(result is int || result is long || result is decimal)) throw new InvalidOperationException("FIELD_INVALID_" + name);
            decimal number = Convert.ToDecimal(result);
            if (Decimal.Truncate(number) != number || number > Int64.MaxValue || number < Int64.MinValue) throw new InvalidOperationException("FIELD_INVALID_" + name);
            return Convert.ToInt64(number);
        }
        internal static object[] Items(Dictionary<string, object> value, string name)
        {
            object result;
            if (!value.TryGetValue(name, out result) || !(result is object[])) throw new InvalidOperationException("FIELD_INVALID_" + name);
            return (object[])result;
        }
        internal static string CurrentVersion(string root)
        {
            try
            {
                string path = Path.Combine(root, "Media", "current.json");
                RejectReparse(path);
                string version = Text(Object(File.ReadAllText(path)), "version");
                return Regex.IsMatch(version, @"\A[0-9a-f]{32}\z") ? version : "none";
            }
            catch { return "none"; }
        }
    }
}
