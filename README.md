# AdSparkle Android SDK

Client-side conversion-tracking library for the [AdSparkle](https://adsparkle.co) affiliate platform.
Mirrors the behaviour of `adsparkle.js` — click capture, attribution window management, conversion
postbacks, and an offline retry queue — packaged as a standard Android library (AAR).

- **Min SDK**: 21 (Android 5.0 Lollipop)
- **Target / Compile SDK**: 34 (Android 14)
- **Language**: Kotlin
- **Dependencies**: Kotlin stdlib, kotlinx-coroutines-android (no OkHttp, no Retrofit, no heavy deps)

---

## Installation

### Option A — JitPack (easiest, no account required)

1. Add the JitPack repository to your root `settings.gradle.kts` (or `settings.gradle`):

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

2. Add the dependency in your app module's `build.gradle.kts`:

```kotlin
// app/build.gradle.kts
dependencies {
    implementation("com.github.Adsparkle:adsparkle-android:0.1.0")
}
```

### Option B — Maven Central

```kotlin
// app/build.gradle.kts  (maven central is already in the default repo list)
dependencies {
    implementation("co.adsparkle:adsparkle-android:0.1.0")
}
```

---

## Permissions

The SDK declares the following permissions in its own `AndroidManifest.xml` — they are merged
automatically into your app manifest:

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
```

No additional manifest changes are required.

---

## Quick Start

### 1. Initialize

Call `AdSparkle.initialize` once, early in your app lifecycle. `Application.onCreate` is ideal:

```kotlin
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()

        AdSparkle.initialize(
            context    = this,
            companyKey = "YOUR_COMPANY_KEY"
            // endpointBase defaults to "https://api.adsparkle.co"
        )

        // Optional: verbose Logcat output (disable in production builds)
        if (BuildConfig.DEBUG) {
            AdSparkle.enableDebugLogging(true)
        }
    }
}
```

Custom endpoint (self-hosted or staging):

```kotlin
AdSparkle.initialize(
    context      = this,
    companyKey   = "YOUR_COMPANY_KEY",
    endpointBase = "https://staging.adsparkle.co"
)
```

---

### 2. Capture Clicks from Deep Links / Universal Links

Call `handleDeepLink` (or the lower-level `trackClick`) in every Activity that receives deep links.
The SDK parses the `click_id` query parameter, validates it (UUID v4), and adds it to the local
attribution chain (max 50 ids, 7-day TTL).

```kotlin
// MainActivity.kt
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Capture click from the intent that launched the Activity
        AdSparkle.handleDeepLink(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)

        // Capture click when the Activity is already running (singleTask / singleTop)
        AdSparkle.handleDeepLink(intent)
    }
}
```

If you already have the `Uri` directly:

```kotlin
val uri = Uri.parse("myapp://open?click_id=550e8400-e29b-41d4-a716-446655440000&ref=newsletter")
AdSparkle.trackClick(uri)
```

---

### 3. Identify the User (Optional)

If you have a known user id after login, set it so conversions are attributed correctly.
If this is never called, the SDK generates and persists an anonymous id (`anon_…`) automatically.

```kotlin
// After successful login / registration
AdSparkle.setUserId("usr_7f3a2b9c")
```

---

### 4. Track Conversions

#### Purchase with full metadata

```kotlin
AdSparkle.trackConversion(
    type          = "purchase",          // or "order" / "sale" — all map to "purchase"
    transactionId = "txn_abc123",
    amount        = 49.99,
    currency      = "USD",
    productIds    = listOf("sku_pro_monthly", "sku_addon_storage"),
    customParams  = mapOf(
        "coupon"   to "SUMMER20",
        "checkout" to "web"
    ),
    callback = { success ->
        // Called on the SDK's background thread
        Log.d("App", "conversion sent: $success")
    }
)
```

#### Sign-up

```kotlin
// Aliases "signup" and "register" both map to canonical "sign_up"
AdSparkle.trackConversion(type = "signup")
```

#### Login

```kotlin
AdSparkle.trackConversion(type = "login")
```

#### Subscription

```kotlin
AdSparkle.trackConversion(
    type          = "subscription",
    transactionId = "sub_xyz789",
    amount        = 9.99,
    currency      = "EUR"
)
```

#### Refund / Chargeback

```kotlin
AdSparkle.trackConversion(
    type          = "refund",
    transactionId = "txn_abc123",
    amount        = 49.99,
    currency      = "USD"
)
```

#### Coroutine-based (suspending) API

```kotlin
// Inside a coroutine or ViewModel
viewModelScope.launch {
    val result = AdSparkle.trackConversionAsync(
        type   = "purchase",
        amount = 99.0,
        currency = "USD"
    )
    when (result) {
        is ConversionResult.Success         -> { /* postback sent */ }
        is ConversionResult.NoClickId       -> { /* organic user, no click_id in chain */ }
        is ConversionResult.UnknownEventType -> { /* bad event type string */ }
        is ConversionResult.Queued          -> { /* offline, will retry automatically */ }
    }
}
```

---

### 5. Accepted Event Types

| Caller string(s)                     | Canonical value sent to API |
|--------------------------------------|-----------------------------|
| `install`                            | `install`                   |
| `sign_up`, `signup`, `register`      | `sign_up`                   |
| `login`                              | `login`                     |
| `download`                           | `download`                  |
| `purchase`, `order`, `sale`          | `purchase`                  |
| `subscription`, `subscribe`          | `subscription`              |
| `refund`, `chargeback`               | `refund`                    |

Unknown strings are rejected and `ConversionResult.UnknownEventType` is returned.

---

### 6. Offline Queue

Failed postbacks are persisted to SharedPreferences (max 100 items) and replayed automatically
when the network becomes available. You can also trigger a manual flush:

```kotlin
// e.g. in onResume or when you know connectivity is restored
AdSparkle.flushQueue()
```

---

## Postback HTTP Contract

```
POST {endpointBase}/api/tracking/postback
Content-Type: application/json
X-Company-Key: <companyKey>
X-SDK-Platform: android
X-SDK-Version: 0.1.0

{
  "click_id":       "550e8400-e29b-41d4-a716-446655440000",
  "click_ids":      ["550e8400-e29b-41d4-a716-446655440000"],
  "event_type":     "purchase",
  "user_id":        "usr_7f3a2b9c",
  "transaction_id": "txn_abc123",
  "amount":         49.99,
  "currency":       "USD",
  "product_ids":    ["sku_pro_monthly"],
  "custom_params":  { "coupon": "SUMMER20" }
}
```

Fields `transaction_id`, `amount`, `currency`, `product_ids`, and `custom_params` are omitted when
not provided. No HMAC or client-side signing — authentication is via the server-only company key.

---

## ProGuard / R8

The SDK ships a `consumer-rules.pro` that is applied automatically to apps using R8/ProGuard.
No manual rules are required in the consuming app.

---

## Publishing

### JitPack (automatic on tag)

1. Push a Git tag matching the version: `git tag 0.1.0 && git push origin 0.1.0`
2. JitPack builds automatically. The `jitpack.yml` pins OpenJDK 17.
3. Dependency: `com.github.Adsparkle:adsparkle-android:0.1.0`

### Maven Central (Sonatype OSSRH)

1. Set credentials and GPG signing in `~/.gradle/gradle.properties` or as environment variables:
   ```
   OSSRH_USERNAME=...
   OSSRH_PASSWORD=...
   signing.key=<ascii-armored-pgp-private-key>
   signing.password=...
   signing.keyId=<last-8-chars>
   ```
2. Publish to staging:
   ```bash
   ./gradlew :adsparkle:publishReleasePublicationToSonatypeRepository
   ```
3. Log in to [s01.oss.sonatype.org](https://s01.oss.sonatype.org), close and release the staging repo.

### Local Maven (testing)

```bash
./gradlew :adsparkle:publishReleasePublicationToLocalRepository
# Output at: build/maven-local/co/adsparkle/adsparkle-android/0.1.0/
```

---

## Build Requirements

| Tool                  | Minimum version |
|-----------------------|-----------------|
| JDK                   | 17              |
| Android Gradle Plugin | 8.3.2           |
| Gradle                | 8.7             |
| Android SDK           | API 34          |

```bash
./gradlew :adsparkle:assembleRelease
```

---

## License

MIT License. Copyright (c) 2026 Viralif / Adem.
