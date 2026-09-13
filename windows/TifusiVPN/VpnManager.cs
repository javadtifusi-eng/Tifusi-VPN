using System.Diagnostics;
using System.Net.NetworkInformation;
using System.Text;

namespace TifusiVpn;

public sealed record VpnResult(bool Success, int Code, string Output);

/// <summary>
/// Drives Windows' own VPN client, the same built-in IKEv2/L2TP stack iOS-style profiles use: the
/// connection is (re)created with PowerShell's VpnClient cmdlets for the current user, then dialled
/// and hung up with rasdial. None of this needs administrator rights.
/// </summary>
public static class VpnManager
{
    public const string ConnectionName = "Tifusi VPN";

    /// <summary>Recreates the Windows VPN entry for <paramref name="profile"/>. Blocking.</summary>
    public static VpnResult Provision(ServerProfile profile)
    {
        var name = Quote(ConnectionName);
        var server = Quote(profile.Server);
        var script = new StringBuilder();
        script.AppendLine("$ErrorActionPreference = 'Stop'");
        script.AppendLine($"Remove-VpnConnection -Name {name} -Force -ErrorAction SilentlyContinue");

        if (profile.Protocol == "L2TP")
        {
            script.AppendLine(
                $"Add-VpnConnection -Name {name} -ServerAddress {server} -TunnelType L2tp " +
                $"-L2tpPsk {Quote(profile.Psk ?? "")} -AuthenticationMethod MSChapv2 -EncryptionLevel Required " +
                "-RememberCredential:$false -Force");
        }
        else
        {
            // EAP defaults to EAP-MSCHAPv2 with a username/password prompt, which is what the panel's
            // eap-mode Cores expect. Windows validates the server certificate against its own trust
            // store and fetches intermediates itself, so no CA needs pinning here.
            script.AppendLine(
                $"Add-VpnConnection -Name {name} -ServerAddress {server} -TunnelType Ikev2 " +
                "-AuthenticationMethod Eap -EncryptionLevel Required -RememberCredential:$false -Force");
            // Windows' default IKEv2 proposal set includes weak groups the node does not list first;
            // pin what the node's swanctl proposals and esp_proposals offer (node_agent/ipsec.py).
            script.AppendLine(
                $"Set-VpnConnectionIPsecConfiguration -ConnectionName {name} -AuthenticationTransformConstants SHA256128 " +
                "-CipherTransformConstants AES256 -EncryptionMethod AES256 -IntegrityCheckMethod SHA256 " +
                "-DHGroup Group14 -PfsGroup None -Force");
        }

        return Run("powershell.exe", new[] { "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-Command", script.ToString() });
    }

    /// <summary>Dials the provisioned entry. Blocking; can take up to about half a minute.</summary>
    public static VpnResult Connect(ServerProfile profile) =>
        Run("rasdial.exe", new[] { ConnectionName, profile.Username ?? "", profile.Password ?? "" });

    public static VpnResult Disconnect() => Run("rasdial.exe", new[] { ConnectionName, "/disconnect" });

    /// <summary>The live RAS interface for the connection, or null while it is down.</summary>
    public static NetworkInterface? ActiveInterface()
    {
        try
        {
            return NetworkInterface.GetAllNetworkInterfaces().FirstOrDefault(n =>
                n.Name == ConnectionName && n.OperationalStatus == OperationalStatus.Up);
        }
        catch (NetworkInformationException)
        {
            return null;
        }
    }

    public static bool IsConnected => ActiveInterface() != null;

    /// <summary>Bytes received and sent on the tunnel interface since it came up.</summary>
    public static (long Rx, long Tx)? Counters()
    {
        var nic = ActiveInterface();
        if (nic == null) return null;
        try
        {
            var stats = nic.GetIPStatistics();
            return (stats.BytesReceived, stats.BytesSent);
        }
        catch (NetworkInformationException)
        {
            return null;
        }
    }

    private static string Quote(string value) => "'" + value.Replace("'", "''") + "'";

    private static VpnResult Run(string file, IEnumerable<string> args)
    {
        try
        {
            var info = new ProcessStartInfo(file)
            {
                UseShellExecute = false,
                CreateNoWindow = true,
                RedirectStandardOutput = true,
                RedirectStandardError = true,
                StandardOutputEncoding = Encoding.UTF8,
                StandardErrorEncoding = Encoding.UTF8,
            };
            foreach (var arg in args) info.ArgumentList.Add(arg);

            using var process = Process.Start(info)!;
            var stdout = process.StandardOutput.ReadToEndAsync();
            var stderr = process.StandardError.ReadToEndAsync();
            if (!process.WaitForExit(90_000))
            {
                try { process.Kill(true); } catch (Exception) { }
                return new VpnResult(false, -1, "timeout");
            }
            var output = (stdout.Result + "\n" + stderr.Result).Trim();
            return new VpnResult(process.ExitCode == 0, process.ExitCode, output);
        }
        catch (Exception e)
        {
            return new VpnResult(false, -1, e.Message);
        }
    }
}
