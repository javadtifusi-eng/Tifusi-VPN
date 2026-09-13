using System.Net;
using System.Net.Http;
using System.Text.Json;
using System.Text.RegularExpressions;
using Microsoft.Win32;

namespace TifusiVpn;

public sealed class SubscriptionException : Exception
{
    public SubscriptionException(string key, string? detail = null) : base(key)
    {
        Key = key;
        Detail = detail;
    }

    /// <summary>A <see cref="Strings"/> key describing the failure.</summary>
    public string Key { get; }
    public string? Detail { get; }
}

/// <summary>
/// Imports a Tifusi Panel subscription from its link (<c>&lt;panel&gt;/sub/&lt;secret&gt;</c>) or app code
/// (e.g. <c>javad7KQ4MP9X</c>, or <c>CODE@panel.example.com</c> for another panel), the same way the
/// Android app's SubscriptionClient does, via the panel's <c>app.json</c> endpoint.
/// </summary>
public static class SubscriptionClient
{
    /// <summary>A bare app code resolves against this panel, like the Android build's default.</summary>
    public const string DefaultPanelUrl = "https://ge.koledemb.ir";

    private static readonly Regex Link = new(@"^(https?://\S+?)/sub/([A-Za-z0-9-]{16,})/?(?:[?#]\S*)?$", RegexOptions.IgnoreCase);

    private static readonly HttpClient Http = new() { Timeout = TimeSpan.FromSeconds(15) };

    public static string? Normalize(string input)
    {
        var value = input.Trim();
        var match = Link.Match(value);
        if (match.Success) return $"{match.Groups[1].Value}/sub/{match.Groups[2].Value}";
        if (value.Contains("://") || value.Any(c => char.IsWhiteSpace(c) || c == '/')) return null;

        var at = value.LastIndexOf('@');
        var code = at >= 0 ? value[..at] : value;
        var panel = at >= 0 ? (value.Length > at + 1 ? "https://" + value[(at + 1)..] : null) : DefaultPanelUrl;
        if (panel == null || code.Length is < 9 or > 128) return null;
        return $"{panel}/code/{Uri.EscapeDataString(code)}";
    }

    public static async Task<(List<ServerProfile> Servers, SubscriptionInfo Info)> FetchAsync(string link)
    {
        var normalized = Normalize(link) ?? throw new SubscriptionException("sub_not_link");
        var url = $"{normalized}/app.json?hwid={Uri.EscapeDataString(MachineId())}";

        HttpResponseMessage response;
        string body;
        try
        {
            using var request = new HttpRequestMessage(HttpMethod.Get, url);
            request.Headers.UserAgent.ParseAdd("TifusiVPN-Windows");
            response = await Http.SendAsync(request);
            body = await response.Content.ReadAsStringAsync();
        }
        catch (Exception e)
        {
            throw new SubscriptionException("sub_network", e.Message);
        }

        switch (response.StatusCode)
        {
            case HttpStatusCode.OK:
                break;
            case HttpStatusCode.Forbidden:
                throw new SubscriptionException("sub_device_limit");
            case HttpStatusCode.NotFound:
                // The panel's own lookup answers "Not found"; an unknown route answers "Not Found".
                throw new SubscriptionException(body.Contains("Not found") ? "sub_not_found" : "sub_outdated");
            default:
                throw new SubscriptionException("sub_network", $"HTTP {(int)response.StatusCode}");
        }

        try
        {
            using var doc = JsonDocument.Parse(body);
            var root = doc.RootElement;
            var servers = new List<ServerProfile>();

            foreach (var cfg in Array(root, "ikev2"))
            {
                var server = Str(cfg, "server");
                if (server == null) continue;
                servers.Add(new ServerProfile
                {
                    Id = $"sub:ikev2:{server}",
                    Name = Str(cfg, "remark") ?? server,
                    Protocol = "IKEV2",
                    Server = server,
                    RemoteId = Str(cfg, "remote_id"),
                    Username = Str(cfg, "username"),
                    Password = Str(cfg, "password"),
                    Psk = Str(cfg, "psk"),
                });
            }

            foreach (var cfg in Array(root, "l2tp"))
            {
                var server = Str(cfg, "server");
                if (server == null) continue;
                servers.Add(new ServerProfile
                {
                    Id = $"sub:l2tp:{server}",
                    Name = Str(cfg, "remark") ?? server,
                    Protocol = "L2TP",
                    Server = server,
                    Username = Str(cfg, "username"),
                    Password = Str(cfg, "password"),
                    Psk = Str(cfg, "psk"),
                });
            }

            if (servers.Count == 0) throw new SubscriptionException("sub_no_servers");

            var info = new SubscriptionInfo
            {
                Expire = Long(root, "expire"),
                Used = Long(root, "used_traffic") ?? 0,
                Limit = Long(root, "data_limit"),
            };
            return (servers, info);
        }
        catch (SubscriptionException)
        {
            throw;
        }
        catch (Exception e)
        {
            throw new SubscriptionException("sub_network", e.Message);
        }
    }

    private static IEnumerable<JsonElement> Array(JsonElement root, string name) =>
        root.TryGetProperty(name, out var value) && value.ValueKind == JsonValueKind.Array
            ? value.EnumerateArray().ToList()
            : Enumerable.Empty<JsonElement>();

    private static string? Str(JsonElement obj, string name) =>
        obj.TryGetProperty(name, out var value) && value.ValueKind == JsonValueKind.String && !string.IsNullOrWhiteSpace(value.GetString())
            ? value.GetString()
            : null;

    private static long? Long(JsonElement obj, string name) =>
        obj.TryGetProperty(name, out var value) && value.ValueKind == JsonValueKind.Number && value.TryGetInt64(out var n) ? n : null;

    /// <summary>Stable per-install id, so the panel's device limit counts this PC once.</summary>
    private static string MachineId()
    {
        try
        {
            using var key = Registry.LocalMachine.OpenSubKey(@"SOFTWARE\Microsoft\Cryptography");
            return key?.GetValue("MachineGuid") as string ?? Environment.MachineName;
        }
        catch (Exception)
        {
            return Environment.MachineName;
        }
    }
}
