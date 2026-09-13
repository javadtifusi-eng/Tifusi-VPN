namespace TifusiVpn;

/// <summary>One server from the panel subscription (or entered by hand), as the Android app stores it.</summary>
public sealed class ServerProfile
{
    public string Id { get; set; } = "";
    public string Name { get; set; } = "";
    /// <summary>"IKEV2" or "L2TP".</summary>
    public string Protocol { get; set; } = "IKEV2";
    public string Server { get; set; } = "";
    public string? RemoteId { get; set; }
    public string? Username { get; set; }
    public string? Password { get; set; }
    /// <summary>IKEv2 pre-shared key (PSK-mode Cores) or the L2TP/IPsec key.</summary>
    public string? Psk { get; set; }
}

/// <summary>Days and data left on the account, as last reported by the panel.</summary>
public sealed class SubscriptionInfo
{
    /// <summary>Unix seconds; null means the account never expires.</summary>
    public long? Expire { get; set; }
    public long Used { get; set; }
    /// <summary>Bytes; null means unlimited.</summary>
    public long? Limit { get; set; }
}

public sealed class AppSettings
{
    public string Language { get; set; } = "en";
    public string? SubscriptionLink { get; set; }
    public string? SelectedServerId { get; set; }
    public List<ServerProfile> Servers { get; set; } = new();
    public SubscriptionInfo? Info { get; set; }
}

/// <summary>What the server list shows for one row.</summary>
public sealed class ServerRow
{
    public string Id { get; init; } = "";
    public string Name { get; init; } = "";
    public string Subtitle { get; init; } = "";
    public string BorderColor { get; init; } = "#26305A";
}
