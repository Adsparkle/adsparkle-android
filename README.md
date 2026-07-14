# AdSparkle Android SDK

Android client SDK for the **AdSparkle** affiliate tracking platform.
Mobile apps use it to forward attribution events (install, sign-up, purchase, …)
to the tracking backend.

- Pure Kotlin, **no third-party dependencies** (uses only the Android framework
  + `org.json`, both bundled with the platform).
- Non-blocking: all networking runs on a background worker thread.
- Offline-resilient: failed events are persisted and retried automatically.

---

## Installation

### Gradle (Kotlin DSL)

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        maven { url = uri("https://jitpack.io") }  // JitPack
        // or your internal Maven repository hosting the artifact
    }
}
```

```kotlin
// app/build.gradle.kts
dependencies {
    implementation("com.github.Adsparkle:adsparkle-android:0.1.3")
}
```

### Gradle (Groovy)

```groovy
dependencies {
    implementation 'com.github.Adsparkle:adsparkle-android:0.1.3'
}
```

Requirements: **minSdk 21**, **compileSdk 34**, **JDK 17**.
The library declares the `INTERNET` permission for you.

---

## Configuration

Initialize once, as early as possible — typically in `Application.onCreate`:

```kotlin
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AdSparkle.configure(
            context = this,
            companyKey = "co_your_publishable_key",
            baseUrl = "https://api.adsparkle.co", // optional, this is the default
            debug = BuildConfig.DEBUG
        )
    }
}
```

> **The `companyKey` is a publishable `co_` key — it is NOT a secret.**
> It is safe to embed it in your app binary. The SDK never uses, stores, or
> transmits any HMAC signing secret. Signing/verification stays server-side.

---

## Capturing the click id from a deep link

Attribution links carry the click id as a query parameter:
`yourapp://open?click_id=<uuid>` or `https://go.example.com/x?click_id=<uuid>`.

Hand the incoming deep-link `Uri` to the SDK from your launch / deep-link
Activity. It extracts `click_id`, persists it, and adds it to the click chain
(de-duplicated, most recent 10 kept):

```kotlin
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AdSparkle.handleDeepLink(intent?.data)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        AdSparkle.handleDeepLink(intent.data)
    }
}
```

You can also set it manually if you resolve the click id another way
(e.g. Play Install Referrer, a deferred deep-link service):

```kotlin
AdSparkle.setClickId("e1b2c3d4-...")
val current = AdSparkle.getClickId()
```

### App Links (deferred deterministic click)

When an attribution link `https://<slug>.go.adsparkle.co/<uniqueKey>?…` opens your
app via **Android App Links** (the app is already installed), the OS launches the
app directly — the tracking server is **never hit**, so no click is recorded and
the URL carries **no `click_id`**.

`handleDeepLink` handles this automatically: if the incoming `Uri` has no
`click_id` but its host ends with `.go.adsparkle.co`, the SDK asks the backend to
create a **deterministic** click from the `uniqueKey` and captures the resulting
`click_id`. It sends a stable, SDK-generated **device id** (see below) and **no
device fingerprint**. If the request fails, it is persisted and retried on the
next `configure()` / `track()` / deep-link — you don't need to do anything.

No extra code is required beyond the `handleDeepLink(intent?.data)` calls shown
above. Make sure your App Links / intent filters route `*.go.adsparkle.co` links
into an Activity that forwards the `Uri` to `handleDeepLink`.

---

## Auto Backup / device_id (required manifest step)

The SDK generates and persists a stable **device id** (a random UUID in its own
`SharedPreferences` file, `adsparkle_prefs.xml`). It is used as the idempotency
key for deterministic clicks so the backend can de-duplicate the same install /
click. It is **not** `ANDROID_ID` or an advertising id.

**Android Auto Backup is ON by default.** If left unchecked, `adsparkle_prefs.xml`
is backed up to the cloud (or copied during device-to-device transfer) and
restored onto a **different** device — so two devices would report the **same**
device id, corrupting attribution counts. You must exclude the SDK's prefs file
from backup.

The SDK ships two ready-to-use resources for this:

- `@xml/adsparkle_backup_rules` — for `android:fullBackupContent` (API 23–30)
- `@xml/adsparkle_data_extraction_rules` — for `android:dataExtractionRules`
  (Android 12+ / API 31+; covers both cloud-backup and device-transfer)

> The library manifest intentionally does **not** declare these attributes — doing
> so would collide with (or silently override) your app's own backup rules during
> manifest merging. You must wire them up in **your** app's manifest.

**If your app has no backup rules yet**, point your `<application>` at the shipped
resources:

```xml
<application
    android:fullBackupContent="@xml/adsparkle_backup_rules"
    android:dataExtractionRules="@xml/adsparkle_data_extraction_rules"
    ... >
```

**If your app already has backup rules**, keep yours and add the exclusion to
them instead:

```xml
<!-- your existing @xml/…_backup_rules.xml (fullBackupContent) -->
<full-backup-content>
    <!-- your existing rules … -->
    <exclude domain="sharedpref" path="adsparkle_prefs.xml" />
</full-backup-content>
```

```xml
<!-- your existing @xml/…_data_extraction_rules.xml (Android 12+) -->
<data-extraction-rules>
    <cloud-backup>
        <!-- your existing rules … -->
        <exclude domain="sharedpref" path="adsparkle_prefs.xml" />
    </cloud-backup>
    <device-transfer>
        <!-- your existing rules … -->
        <exclude domain="sharedpref" path="adsparkle_prefs.xml" />
    </device-transfer>
</data-extraction-rules>
```

---

## Identifying the user

```kotlin
AdSparkle.setUserId("user-123")
```

A `click_id` **and** a `user_id` are both required before any event is sent.
If either is missing, `track()` quietly skips (logs a warning in `debug` mode,
never throws).

---

## Tracking events

```kotlin
// Simple events
AdSparkle.trackInstall()
AdSparkle.trackSignUp()
AdSparkle.trackLogin()
AdSparkle.trackDownload()

// Revenue events
AdSparkle.trackPurchase(
    AdSparkleEvent(
        transactionId = "txn_abc123",
        amount = 19.99,
        currency = "USD",
        productIds = listOf("sku_pro"),
        customParams = mapOf("source" to "paywall_a")
    )
)

AdSparkle.trackSubscription(
    AdSparkleEvent(transactionId = "sub_1", amount = 9.99, currency = "USD")
)

AdSparkle.trackRefund(
    AdSparkleEvent(transactionId = "txn_abc123")
)

// Generic form
AdSparkle.track("purchase", AdSparkleEvent(amount = 5.0, currency = "EUR"))
```

### Event types

The seven built-in event types below have typed helpers and match the backend
contract:

| Event type     | Helper                  | Typical extra fields                          |
|----------------|-------------------------|-----------------------------------------------|
| `install`      | `trackInstall()`        | —                                             |
| `sign_up`      | `trackSignUp()`         | —                                             |
| `login`        | `trackLogin()`          | —                                             |
| `download`     | `trackDownload()`       | `productIds`                                  |
| `purchase`     | `trackPurchase()`       | `transactionId`, `amount`, `currency`, `productIds` |
| `subscription` | `trackSubscription()`   | `transactionId`, `amount`, `currency`         |
| `refund`       | `trackRefund()`         | `transactionId`                               |

You are **not** limited to these seven. To fire a company **custom event**, pass
its shortId (e.g. `"YE2YFSQ"`) as the `event_type`:

```kotlin
AdSparkle.track("YE2YFSQ", AdSparkleEvent(amount = 5.0, currency = "EUR"))
```

`event_type` is accepted when it matches `^[A-Za-z0-9_]{1,64}$` (mixed case:
uppercase custom-event shortIds and lowercase built-in keys both pass); anything
else is skipped locally. `productIds` and `custom_params` are already supported
for every event type, custom events included.

All `AdSparkleEvent` fields are optional/nullable; pass only what applies.

---

## Request contract

Each event is sent as:

```
POST {baseUrl}/api/tracking/postback
Content-Type: application/json
X-Company-Key: co_your_publishable_key
```

```json
{
  "click_id": "e1b2c3d4-...",
  "click_ids": ["e1b2...", "f9a0..."],
  "event_type": "purchase",
  "user_id": "user-123",
  "transaction_id": "txn_abc123",
  "amount": 19.99,
  "currency": "USD",
  "product_ids": ["sku_pro"],
  "custom_params": { "source": "paywall_a" }
}
```

A `200` means the event was accepted for asynchronous processing. On `5xx` or
network errors the SDK retries up to 3 times with exponential backoff on a
background thread; if all attempts fail, the event is persisted and flushed on
the next `track()` / `configure()`.

---

## Java interop

All public methods are `@JvmStatic`, and `AdSparkleEvent` ships a builder:

```java
AdSparkle.configure(context, "co_your_publishable_key");
AdSparkle.setUserId("user-123");
AdSparkle.handleDeepLink(getIntent().getData());

AdSparkleEvent event = new AdSparkleEvent.Builder()
        .transactionId("txn_abc123")
        .amount(19.99)
        .currency("USD")
        .build();
AdSparkle.trackPurchase(event);
```

---

## ProGuard / R8

The SDK ships **consumer ProGuard rules** (`consumer-rules.pro`) that keep the
public API surface, so no extra configuration is required in your app even with
minification enabled. If you shrink aggressively and access the SDK only via
reflection, ensure `co.adsparkle.sdk.AdSparkle` and
`co.adsparkle.sdk.AdSparkleEvent` are kept.

---

## License

Proprietary — © AdSparkle.
