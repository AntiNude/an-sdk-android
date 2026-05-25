# AntiNude SDK · Android

On-device nudity detection for Android. Image bytes never leave the
device — only the verdict and per-class detection scores are reported to
the AntiNude backend for dashboard analytics.

**Current version: 0.9.0** · uses NudeNet 320n bundled as an SDK asset ·
ONNX Runtime 1.24.2.

## Requirements

- minSdk 24 (Android 7)
- compileSdk / targetSdk 34+
- Kotlin 1.9+
- Android Gradle Plugin 8.2+
- JDK 17 toolchain

## Install via JitPack

Until we publish to Maven Central (1.0), the SDK ships through JitPack.

In your project's `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

In the module's `build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.github.AntiNude:an-sdk-android:0.9.0")
}
```

> Replace `AntiNude` with your GitHub username/org if you're consuming a
> fork. The version matches the git tag.

## Usage

```kotlin
import com.an.sdk.ANClient
import com.an.sdk.ANException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

val client = ANClient.create(context, apiKey = "ak_live_…") // or ak_test_…

suspend fun scan(bytes: ByteArray) {
    try {
        val result = withContext(Dispatchers.Default) {
            client.scanImage(bytes)
        }
        Log.d("AN", "${result.verdict} ${result.topCategory} ${result.topScore}")
        // result.detections: List<Detection>?  // class + score + normalized bbox
    } catch (e: ANException) {
        Log.e("AN", "failed: ${e.code.raw} status=${e.statusCode} retry=${e.isRetryable}")
    }
}
```

`scanImage` is the only entry point. It accepts encoded image bytes
(`ByteArray`) — JPEG / PNG / WebP are decoded by `BitmapFactory`. The
detector runs on the bundled `nudenet-320n-v3.4` model, pinned per SDK
release.

### What you get back

```kotlin
data class ScanResult(
    val verdict: String,                // "safe" / "unsafe"
    val topCategory: String?,           // e.g. "FEMALE_BREAST_EXPOSED"
    val topScore: Double?,              // 0.0 – 1.0
    val latencyMs: Int,
    val modelVersion: String,           // "nudenet-320n-v3.4"
    val requestId: String?,             // null if telemetry skipped / failed
    val detections: List<Detection>? = null,
)

data class Detection(
    val category: String,   // one of 18 NudeNet classes
    val score: Double,      // 0.0 – 1.0
    val bbox: List<Double>? = null,   // [x, y, w, h] normalized to [0, 1]
)
```

The default verdict rule is hardcoded in v0.9: **unsafe** if any of the
five "exposed" classes (`FEMALE_BREAST_EXPOSED`, `FEMALE_GENITALIA_EXPOSED`,
`MALE_GENITALIA_EXPOSED`, `BUTTOCKS_EXPOSED`, `ANUS_EXPOSED`) scores above
`0.50`. Need a different rule? Inspect `result.detections` and apply your
own logic — see
[antinude.io/docs](https://antinude.io/docs) (Custom verdict rules).

### Disable telemetry

```kotlin
val client = ANClient.create(
    context = ctx,
    apiKey = "...",
    reportToServer = false,
)
```

Scans then run fully on-device with no network call. `result.requestId`
will be `null` and the scan won't appear in your dashboard.

### Custom backend

```kotlin
val client = ANClient.create(
    context = ctx,
    apiKey = "...",
    baseUrl = "https://staging.example.com",
)
```

### Threading

`scanImage` is a `suspend` function — call it from a coroutine on
`Dispatchers.Default` (CPU) or `Dispatchers.IO`. The underlying detector
is **not** safe to call concurrently from multiple threads; keep one
`ANClient` per process and let coroutines serialise calls.

## API keys & bundle binding

Issue a key at <https://antinude.io/keys>. Format: `ak_live_<48 hex>` for
production, `ak_test_<48 hex>` for sandbox.

Production keys with a non-null `restriction` field (set in the dashboard)
are **bundle-bound** server-side. When you construct the client via
`ANClient.create(context, ...)`, the SDK automatically sends
`context.packageName` in the `X-AntiNude-Bundle` header on every
telemetry call. If the package doesn't match the restriction, the backend
returns `401 unauthorized`.

Sandbox keys (`ak_test_*`) and unrestricted production keys are not
bundle-checked.

## ProGuard / R8

The SDK ships its own `consumer-rules.pro` — typically no extra rules
needed in your app. If R8 warns about ORT internals, add:

```
-dontwarn ai.onnxruntime.**
-keep class com.an.sdk.** { *; }
```

## Errors

All thrown errors are `ANException` with a stable `ANErrorCode` enum and an
`isRetryable: Boolean`. Retryable codes today: `RATE_LIMITED`,
`SERVICE_UNAVAILABLE`, `NETWORK`. See
[antinude.io/docs](https://antinude.io/docs) for the full taxonomy.

## License

MIT. The bundled NudeNet 320n model is © Bedapudi Praneeth and licensed
under MIT — see <https://github.com/notAI-tech/NudeNet>.
