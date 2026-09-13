using System.IO;
using System.Text.Json;

namespace TifusiVpn;

/// <summary>Keeps <see cref="AppSettings"/> in %AppData%\TifusiVPN\settings.json.</summary>
public static class SettingsStore
{
    private static readonly string Dir = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "TifusiVPN");

    private static readonly string FilePath = Path.Combine(Dir, "settings.json");

    private static readonly JsonSerializerOptions Options = new() { WriteIndented = true };

    public static AppSettings Load()
    {
        try
        {
            if (File.Exists(FilePath))
            {
                return JsonSerializer.Deserialize<AppSettings>(File.ReadAllText(FilePath), Options) ?? new AppSettings();
            }
        }
        catch (Exception)
        {
            // A damaged file must not stop the app from opening; start fresh instead.
        }
        return new AppSettings();
    }

    public static void Save(AppSettings settings)
    {
        try
        {
            Directory.CreateDirectory(Dir);
            File.WriteAllText(FilePath, JsonSerializer.Serialize(settings, Options));
        }
        catch (Exception)
        {
            // Best effort, like the Android DataStore writes: the session keeps working in memory.
        }
    }
}
