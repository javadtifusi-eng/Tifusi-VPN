namespace TifusiVpn;

/// <summary>English and Persian texts, matching the Android app's strings.xml where both have them.</summary>
public static class Strings
{
    public static string Lang { get; set; } = "en";

    public static bool IsPersian => Lang == "fa";

    private static readonly Dictionary<string, (string En, string Fa)> Map = new()
    {
        ["nav_home"] = ("Home", "خانه"),
        ["nav_servers"] = ("Servers", "سرورها"),
        ["nav_profile"] = ("Profile", "پروفایل"),
        ["nav_about"] = ("About", "درباره"),

        ["status_connected"] = ("Connected", "متصل"),
        ["status_connecting"] = ("Connecting…", "در حال اتصال…"),
        ["status_disconnected"] = ("Not Connected", "قطع"),
        ["action_connect"] = ("Connect", "اتصال"),
        ["action_disconnect"] = ("Disconnect", "قطع اتصال"),
        ["no_server"] = ("No server selected", "سروری انتخاب نشده"),

        ["sub_title"] = ("Subscription code or link", "شناسه یا لینک اشتراک"),
        ["sub_add"] = ("Get servers", "دریافت سرورها"),
        ["sub_refresh"] = ("Refresh", "به‌روزرسانی"),
        ["sub_loading"] = ("Getting servers…", "در حال دریافت سرورها…"),
        ["sub_imported"] = ("{0} servers added", "{0} سرور اضافه شد"),
        ["sub_not_link"] = ("That is not a subscription link or code.", "این لینک یا شناسه‌ی اشتراک نیست."),
        ["sub_not_found"] = ("Subscription not found. Check the code.", "اشتراک پیدا نشد. شناسه را بررسی کنید."),
        ["sub_device_limit"] = ("Device limit reached for this account.", "سقف دستگاه‌های این اکانت پر شده است."),
        ["sub_outdated"] = ("The panel is too old for app import.", "نسخه‌ی پنل برای دریافت در اپ قدیمی است."),
        ["sub_no_servers"] = ("This subscription has no IKEv2 or L2TP servers.", "این اشتراک سرور IKEv2 یا L2TP ندارد."),
        ["sub_network"] = ("Couldn't reach the panel: {0}", "به پنل وصل نشد: {0}"),
        ["no_servers"] = ("No servers yet. Enter your subscription code above.", "هنوز سروری نیست. شناسه‌ی اشتراک را بالا وارد کنید."),

        ["quota_days"] = ("{0} days left", "{0} روز مانده"),
        ["quota_no_expiry"] = ("No expiry", "بدون انقضا"),
        ["quota_expired"] = ("Expired", "منقضی شده"),
        ["quota_data"] = ("{0} left", "{0} مانده"),
        ["quota_unlimited"] = ("Unlimited data", "حجم نامحدود"),
        ["internet_ok"] = ("✓ {0} ms", "✓ {0} ms"),
        ["internet_fail"] = ("✗ No internet", "✗ بدون اینترنت"),

        ["language"] = ("Language", "زبان"),
        ["contact_us"] = ("Contact us", "ارتباط با ما"),
        ["telegram"] = ("Telegram @{0}", "تلگرام @{0}"),

        ["version"] = ("Version {0}", "نسخه {0}"),
        ["windows_version"] = ("Windows {0}", "ویندوز {0}"),
        ["update_check"] = ("Check for updates", "بررسی به‌روزرسانی"),
        ["update_checking"] = ("Checking for updates…", "در حال بررسی به‌روزرسانی…"),
        ["update_latest"] = ("You have the latest version", "آخرین نسخه را دارید"),
        ["update_available"] = ("Version {0} is available", "نسخه‌ی {0} آماده است"),
        ["update_download"] = ("Download update", "دانلود به‌روزرسانی"),
        ["update_failed"] = ("Could not check for updates", "بررسی به‌روزرسانی ممکن نشد"),

        ["err_psk"] = (
            "This server uses a shared key (PSK) for IKEv2, which the Windows app does not support yet.",
            "این سرور برای IKEv2 از کلید مشترک (PSK) استفاده می‌کند که نسخه‌ی ویندوز هنوز پشتیبانی نمی‌کند."),
        ["err_provision"] = ("Could not create the Windows VPN connection: {0}", "ساخت اتصال VPN ویندوز ممکن نشد: {0}"),
        ["err_connect"] = ("Windows could not connect (error {0}). {1}", "ویندوز وصل نشد (خطای {0}). {1}"),
        ["hint_691"] = ("The username or password was rejected.", "نام کاربری یا رمز پذیرفته نشد."),
        ["hint_809"] = (
            "The network blocked the VPN (UDP ports 500/4500).",
            "شبکه جلوی VPN را گرفت (پورت‌های UDP ۵۰۰ و ۴۵۰۰)."),
        ["hint_13801"] = (
            "The server certificate was not accepted.",
            "گواهی سرور پذیرفته نشد."),
        ["hint_other"] = ("", ""),
    };

    public static string S(string key) =>
        Map.TryGetValue(key, out var value) ? (IsPersian ? value.Fa : value.En) : key;

    public static string F(string key, params object[] args) => string.Format(S(key), args);
}
