using System.IO;

namespace ClipSync.App.Security;

internal static class LocalDeviceIdentity
{
    public static string GetOrCreate(string dataDirectory)
    {
        var path = Path.Combine(dataDirectory, "device-id");
        if (TryRead(path, out var existing))
        {
            return existing;
        }

        var created = Guid.NewGuid().ToString("D");
        try
        {
            using var stream = new FileStream(path, FileMode.CreateNew, FileAccess.Write, FileShare.Read);
            using var writer = new StreamWriter(stream);
            writer.Write(created);
            return created;
        }
        catch (IOException) when (TryRead(path, out existing))
        {
            return existing;
        }
    }

    private static bool TryRead(string path, out string deviceId)
    {
        deviceId = string.Empty;
        if (!File.Exists(path))
        {
            return false;
        }

        var value = File.ReadAllText(path).Trim();
        if (!Guid.TryParseExact(value, "D", out var parsed) || parsed == Guid.Empty)
        {
            throw new InvalidDataException("The local device identity is invalid.");
        }

        deviceId = parsed.ToString("D");
        return true;
    }
}
