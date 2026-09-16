<p align="center">
  <img src="docs/brand/logo.png" width="180" alt="لوگوی تیفوسی" />
</p>

<h1 align="center">TIFUSI VPN</h1>

<p align="center">
  <img src="https://img.shields.io/badge/Android-11%2B-3DDC84?style=flat-square&logo=android&logoColor=white" alt="Android 11+" />
  <img src="https://img.shields.io/badge/Kotlin-Compose-7F52FF?style=flat-square&logo=kotlin&logoColor=white" alt="Kotlin Compose" />
  <a href="https://t.me/javadheydeari"><img src="https://img.shields.io/badge/Support-26A5E4?style=flat-square&logo=telegram&logoColor=white" alt="Telegram support" /></a>
  <a href="https://github.com/javadtifusi-eng/Tifusi-VPN/releases/latest"><img src="https://img.shields.io/github/v/release/javadtifusi-eng/Tifusi-VPN?style=flat-square&label=release&color=22C55E" alt="release" /></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-source--available-DC2626?style=flat-square" alt="license" /></a>
</p>

<p align="center">
  <img src="docs/brand/gb.png" height="14" alt="" /> <a href="README.md">English</a> &nbsp;·&nbsp; <img src="docs/brand/ir.png" height="14" alt="" /> <b>فارسی</b>
</p>

<hr>

<div dir="rtl">

اپلیکیشن شخصی اندروید برای وصل و قطع کردن سریع VPN سرورهای [Tifusi Panel](https://github.com/javadtifusi-eng/Tifusi-Panel)، بدون رفتن به تنظیمات گوشی سامسونگ.
رابط کاربری دوزبانه فارسی/انگلیسی، با تم تیره نئونی.

> **وضعیت:** با GitHub Actions کامپایل می‌شود و APK ساخته می‌شود، ولی **هنوز روی گوشی واقعی و در برابر سرور واقعی تست نشده.**

---

## دریافت APK

1. در همین ریپو به تب **Actions** بروید و آخرین اجرای موفق **Build APK** را باز کنید.
2. از بخش **Artifacts** فایل `tifusi-vpn-debug` را دانلود کنید؛ داخلش `app-debug.apk` است.
3. APK را روی گوشی باز کنید و اجازه «نصب از منابع ناشناس» را بدهید.

ساخت با Android Studio هم ممکن است: پوشه را باز کنید (Gradle 8.7، JDK 17) و `Build > Build APK(s)`.

---

## هماهنگی با پنل تیفوسی

این اپ طبق کد خود پنل تنظیم شده (`node_agent/ipsec.py`، `subscription/info_page.py`، `links/generator.py`).

| پروتکل | در پنل تیفوسی | در اپ |
|---|---|---|
| **IKEv2** | ✅ دو حالت: «eap» (پیش‌فرض) و «psk» | ✅ وصل/قطع خودکار (اندروید ۱۱+) |
| **L2TP/IPsec** | ✅ PSK مشترک + یوزر/پسورد | ⚠️ اطلاعات را نشان می‌دهد و تنظیمات VPN گوشی را باز می‌کند |
| **WireGuard** | ❌ از پنل حذف شده | ✅ فقط برای سرور WireGuard خارج از پنل |
| **PPTP** | ❌ پنل ندارد | ⚠️ فقط ورود دستی برای سرور دیگر |

### دریافت سرورها از پنل
در صفحه‌ی سرورها **شناسه** یا **لینک اشتراک** را وارد کنید و «دریافت سرورها» را بزنید. همه‌ی سرورهای IKEv2/L2TP کاربر با یوزر، پسورد، PSK و Remote ID خودکار اضافه می‌شوند و «به‌روزرسانی» تغییرات پنل را دوباره می‌خواند.
- **شناسه:** مثل `javad7KQ4MP9X`، بالای صفحه‌ی اشتراک کاربر نمایش داده می‌شود. برای وقتی است که نمی‌شود لینک را فرستاد و باید آن را خواند. حروف بزرگ/کوچک فرقی ندارند. شناسه‌ی خالی به پنل پیش‌فرض همین بیلد (`tifusi.panelUrl` در `gradle.properties`) فرستاده می‌شود؛ برای هر پنل دیگری بنویسید `شناسه@دامنه‌ی-پنل`، مثلاً `ali7KQ4MP9X@panel.example.com`.
- **لینک اشتراک:** `https://<پنل>/sub/<کد>` برای هر پنلی کار می‌کند.
- **گواهی سرور** خودکار از پنل می‌آید و در کادر «گواهی CA سرور» قرار می‌گیرد (گواهی صادرکننده‌ی گواهی سرور). اگر گواهی سرور تمدید شد و وصل نشد، «به‌روزرسانی» را بزنید.
- **ارتباط با ما:** آیدی تلگرام `tifusi.supportTelegram` در `gradle.properties` در تب پروفایل نمایش داده می‌شود.

### ساخت اپ برای پنل خودتان
در `gradle.properties` مقدار `tifusi.panelUrl` را آدرس پنل خودتان و در صورت تمایل `tifusi.supportTelegram` را آیدی پشتیبانی بگذارید و بیلد بگیرید. بدون این کار هم اپ با لینک اشتراک یا `شناسه@دامنه` با هر پنلی کار می‌کند.
- با بازنشانی کلید دسترسی در پنل، لینک و شناسه‌ی قبلی هر دو باطل می‌شوند.
- ورود دستی سرور برای سرورهای خارج از پنل باقی است.

### نحوه‌ی اتصال IKEv2 (مطابق پنل)
- **حالت eap:** سرور با گواهی خودش احراز هویت می‌شود و هر کاربر با یوزرنیم و پسورد (EAP-MSCHAPv2) وارد می‌شود.
  اگر گواهی سرور self-signed باشد، بارکد پنل گواهی CA را همراه دارد و اپ آن را پین می‌کند. برای گواهی معتبر عمومی (مثل Let's Encrypt) نیازی به CA نیست.
- **حالت psk:** فقط کلید مشترک؛ پنل هیچ محدودیتی روی شناسه‌ی کلاینت ندارد.
- **Remote ID:** API اندروید همیشه آدرس سرور را به‌عنوان Remote ID می‌گذارد. برای همین وقتی Remote ID پنل با آدرس سرور فرق دارد، اپ **به خود Remote ID وصل می‌شود**؛ دقیقاً کاری که پروفایل iOS خود پنل می‌کند (`RemoteAddress = remote_id`).
  ⚠️ اگر آدرس Host در پنل یک سرور واسط (مثلاً Tifusi Tunnel در ایران) باشد و Remote ID دامنه‌ی سرور خارج باشد، اتصال از سرور واسط عبور نمی‌کند.
- **پروپوزال‌ها:** سرور پنل `aes256-sha256-modp2048`، `aes256gcm16-prfsha384-ecp384` و `default` را قبول می‌کند که با پیش‌فرض‌های IKEv2 اندروید هم‌پوشانی دارد.

### قرارداد با پنل
آدرس‌ها در `backend/app/routers/subscription.py` پنل تعریف شده‌اند:

```
GET <پنل>/sub/<کد مخفی>/app.json?hwid=<شناسه‌ی دستگاه>
GET <پنل>/code/<شناسه‌ی اپ>/app.json?hwid=<شناسه‌ی دستگاه>
{"v":1, "username":"…", "status":"…", "expire":…, "used_traffic":…, "data_limit":…,
 "ikev2":[{"remark","server","remote_id","username","password","psk"?,"certificate"?}],
 "l2tp":[{"remark","server","username","password","psk"?}]}
```

- `psk` برای IKEv2 فقط در حالت psk فرستاده می‌شود؛ اپ از وجودش حالت احراز هویت را تشخیص می‌دهد.
- اپ `certificate` را فقط وقتی پین می‌کند که زنجیره self-signed باشد؛ گواهی عمومی (مثل Let's Encrypt) با trust store سیستم بررسی می‌شود.
- `hwid` باعث می‌شود محدودیت تعداد دستگاه پنل، گوشی را با عوض شدن IP موبایل دوباره نشمارد.
- اگر این قرارداد در پنل تغییر کند، `app/src/main/java/com/tifusi/vpn/data/SubscriptionClient.kt` هم باید تغییر کند.

---

## احراز هویت IKEv2 و گواهی‌ها

سه حالت در فرم:

1. **کلید مشترک (PSK)**: حالت psk پنل.
2. **یوزرنیم/رمز (EAP-MSCHAPv2)**: حالت eap پنل. گواهی CA سرور برای گواهی self-signed لازم است.
3. **گواهی کلاینت**: پنل از این حالت استفاده نمی‌کند؛ برای سرورهای دیگر نگه داشته شده.

### فرمت‌های قابل قبول
- **گواهی CA سرور:** فایل `.crt` / `.cer` / `.pem` (PEM یا DER) یا چسباندن متن PEM.
  فیلد گواهی پنل (گواهی سرور + CA پشت سر هم) را هم می‌شود مستقیم چسباند؛ اپ CA را از داخلش پیدا می‌کند.
- **گواهی کلاینت:** فایل `.p12` / `.pfx` با رمز، **یا** گواهی + کلید خصوصی PKCS#8.

### چک‌هایی که قبل از ذخیره انجام می‌شود
- فایل واقعاً گواهی X.509 است
- **تاریخ انقضا** (منقضی شده / هنوز معتبر نشده)
- CA واقعاً CA است؛ اگر فقط گواهی خود سرور انتخاب شود خطا می‌دهد
- رمز `.p12` درست است و فایل کلید خصوصی و گواهی دارد
- کلید خصوصی قابل خواندن است (RSA یا EC)
- اگر CA سرور وارد نشده، هشدار داده می‌شود و ذخیره نیاز به تأیید دوباره دارد

### مشکلات رایج
| پیام | راه‌حل |
|---|---|
| سرور اتصال را رد کرد | در حالت eap، یوزرنیم/پسورد کاربر در پنل و گواهی CA را بررسی کنید. Remote ID باید همان دامنه‌ی داخل گواهی سرور باشد. |
| بعد از ۳۰ ثانیه وصل نشد | پورت‌های UDP 500 و 4500 باز باشند و Remote ID به IP سرور resolve شود. |
| کلید در قالب PKCS#1 است | `openssl pkcs8 -topk8 -nocrypt -in key.pem -out key8.pem` |
| رمز `.p12` اشتباه است (ولی درست است) | دوباره با `openssl pkcs12 -export -legacy …` بسازید. |

در اندروید ۱۳ و بالاتر شکست مذاکره‌ی IKE از خود سیستم خوانده می‌شود؛ در اندروید ۱۱ و ۱۲ فقط بعد از ۳۰ ثانیه خطای timeout نمایش داده می‌شود.

---

## ساختار پروژه

```
app/src/main/java/com/tifusi/vpn/
├── vpn/
│   ├── VpnController.kt        # نقطه ورود واحد وصل/قطع برای همه پروتکل‌ها
│   ├── Ikev2VpnManager.kt      # VpnManager + Ikev2VpnProfile
│   ├── WireGuardVpnManager.kt  # WireGuard GoBackend
│   ├── LegacyVpnLauncher.kt    # L2TP/PPTP → تنظیمات اندروید
│   ├── CertificateStore.kt     # خواندن و بررسی گواهی‌ها
│   ├── VpnProfileValidator.kt  # بررسی قبل از ذخیره/اتصال
│   └── VpnProfile.kt, VpnProtocol.kt
├── qr/QrConfigParser.kt        # بارکد پنل / متن کپی پنل / WireGuard
├── data/VpnProfileRepository.kt
└── ui/                         # Compose: home، servers، profile، services
```

## محدودیت‌های فعلی
- آمار ترافیک فقط برای WireGuard؛ «سرعت» هنوز محاسبه نمی‌شود.
- پروفایل‌ها (شامل رمزها) در حافظه‌ی داخلی اپ ذخیره می‌شوند و رمزنگاری جداگانه ندارند. پشتیبان‌گیری اندروید برای اپ غیرفعال است.
- در اندروید ۱۱ و ۱۲ Remote ID جدا از آدرس قابل تنظیم نیست (بخش هماهنگی با پنل را ببینید).
- ممکن است نسخه‌های جدید One UI گزینه‌ی ساخت L2TP را در تنظیمات نداشته باشند.
- آیکون فعلی نسخه‌ی ساده‌شده‌ی لوگو است.

---

## مجوز استفاده

کد تیفوسی وی‌پی‌ان **عمومی است ولی متن‌باز نیست.** می‌توانید کد را ببینید و نسخه‌های رسمی اپ را نصب و استفاده کنید. کپی کردن هر بخشی از کد، تغییر و انتشار دوباره، تغییر نام و برند یا فروش آن بدون اجازه‌ی کتبی ممنوع است. جزئیات در فایل [LICENSE](LICENSE).

</div>
