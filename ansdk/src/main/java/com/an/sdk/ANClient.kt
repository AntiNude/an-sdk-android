package com.an.sdk

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.system.measureTimeMillis

/**
 * A single object the on-device model detected in the image.
 *
 * [bbox] is normalized to `[0, 1]` in `[x, y, width, height]` form relative to
 * the original image dimensions. It stays on-device — only [category] and
 * [score] are reported to the AntiNude backend.
 */
data class Detection(
    val category: String,
    val score: Double,
    val bbox: List<Double>? = null,
)

data class ScanResult(
    val verdict: String,
    val topCategory: String?,
    val topScore: Double?,
    val latencyMs: Int,
    val modelVersion: String,
    val requestId: String?,
    /**
     * Detailed per-object detections from the on-device model. `null` for
     * classifier-only models; non-empty list for detector models such as
     * NudeNet.
     */
    val detections: List<Detection>? = null,
)

/**
 * AntiNude SDK client.
 *
 * Privacy model: the NSFW detector runs **fully on-device**. No image bytes
 * ever leave the device. After local inference, the SDK reports only the
 * resulting verdict (and minimal metadata) to the AntiNude backend so the
 * developer can see usage in the dashboard.
 *
 * v0.9 ships NudeNet 320n bundled as an asset inside the SDK. Construct
 * with [Context] to load the bundled model and pick up the host app's
 * package id for bundle-binding; pass [modelBytes] to use a hot-updated or
 * alternative model.
 */
class ANClient private constructor(
    private val apiKey: String,
    private val baseUrl: String,
    private val reportToServer: Boolean,
    private val detector: Detector,
    private val packageId: String?,
) {

    private val modelVersion: String = Detector.MODEL_VERSION
    @Suppress("unused") val sdkVersion: String get() = SDK_VERSION

    /** Scan an image on device and (by default) report the verdict. */
    suspend fun scanImage(bytes: ByteArray): ScanResult {
        if (bytes.isEmpty()) {
            throw ANException(ANErrorCode.EMPTY_IMAGE, message = "empty image")
        }

        val detections: List<Detection>
        val ms = measureTimeMillis {
            detections = detector.detect(bytes)
        }
        val (verdict, top) = Detector.computeVerdict(detections)

        val full = ScanResult(
            verdict = verdict,
            topCategory = top?.category,
            topScore = top?.score,
            latencyMs = ms.toInt(),
            modelVersion = modelVersion,
            requestId = null,
            detections = detections,
        )
        if (reportToServer) {
            val rid = runCatching { reportVerdict(full) }.getOrNull()
            return full.copy(requestId = rid)
        }
        return full
    }

    companion object {
        /** Bundled SDK release. Sent in the `User-Agent` of every telemetry call. */
        const val SDK_VERSION: String = "0.9.0"

        /**
         * Construct the client using the SDK's bundled NudeNet 320n model.
         * Reads the model bytes from the SDK asset on first call (~12 MB).
         * The host app's [Context.getPackageName] is captured here so the
         * backend can enforce bundle-bound API keys.
         *
         * @throws ANException on [ANErrorCode.MODEL_LOAD_FAILED] if the
         *   bundled asset is missing or ORT cannot load it.
         */
        @JvmStatic
        @JvmOverloads
        fun create(
            context: Context,
            apiKey: String,
            baseUrl: String = "https://antinude.site",
            reportToServer: Boolean = true,
        ): ANClient {
            val bytes = try {
                context.assets.open("320n.onnx").use { it.readBytes() }
            } catch (e: IOException) {
                throw ANException(
                    ANErrorCode.MODEL_LOAD_FAILED,
                    message = "missing 320n.onnx asset: ${e.message}",
                )
            }
            return create(apiKey, bytes, baseUrl, reportToServer, context.packageName)
        }

        /**
         * Construct the client using a caller-supplied model. Use this when
         * integrating a hot-updated model downloaded from your own CDN.
         *
         * Pass [packageId] (typically `context.packageName`) if you want
         * bundle-bound API keys to work; pass `null` to skip the
         * `X-AntiNude-Bundle` header.
         */
        @JvmStatic
        @JvmOverloads
        fun create(
            apiKey: String,
            modelBytes: ByteArray,
            baseUrl: String = "https://antinude.site",
            reportToServer: Boolean = true,
            packageId: String? = null,
        ): ANClient {
            val detector = Detector(modelBytes)
            return ANClient(apiKey, baseUrl, reportToServer, detector, packageId)
        }
    }

    // ---- telemetry (verdict only, no bytes) ----------------------------------------

    private suspend fun reportVerdict(r: ScanResult): String? = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            put("verdict", r.verdict)
            r.topCategory?.let { put("top_category", it) }
            r.topScore?.let { put("top_score", it) }
            put("latency_ms", r.latencyMs)
            put("model_version", r.modelVersion)
            r.detections?.takeIf { it.isNotEmpty() }?.let { ds ->
                // bbox stays on-device; telemetry only ships category + score.
                val arr = JSONArray()
                ds.forEach { d ->
                    arr.put(JSONObject().apply {
                        put("category", d.category)
                        put("score", d.score)
                    })
                }
                put("detections", arr)
            }
        }.toString().toByteArray()

        val conn: HttpURLConnection
        val httpCode: Int
        val raw: String
        try {
            conn = (URL(baseUrl + "/api/v1/scan").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 10_000
                readTimeout = 15_000
                setRequestProperty("Authorization", "Bearer $apiKey")
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "an-sdk-android/$SDK_VERSION (Android)")
                if (!packageId.isNullOrEmpty()) {
                    // Lets the backend enforce bundle-bound API keys.
                    // Sandbox keys and unrestricted live keys ignore this header.
                    setRequestProperty("X-AntiNude-Bundle", packageId)
                }
            }
            conn.outputStream.use { it.write(payload) }
            httpCode = conn.responseCode
            val stream = if (httpCode in 200..299) conn.inputStream else conn.errorStream
            raw = stream?.bufferedReader()?.use { it.readText() } ?: ""
            conn.disconnect()
        } catch (e: IOException) {
            throw ANException(ANErrorCode.NETWORK, message = e.message ?: "network_error")
        }

        if (httpCode !in 200..299) {
            val json = runCatching { JSONObject(raw) }.getOrNull()
            val code = ANErrorCode.fromRaw(json?.optString("error")?.takeIf { it.isNotEmpty() })
            val msg = json?.optString("message")?.takeIf { it.isNotEmpty() }
                ?: raw.ifBlank { "HTTP $httpCode" }
            throw ANException(code, statusCode = httpCode, message = msg)
        }
        runCatching { JSONObject(raw).optString("request_id").takeIf { it.isNotEmpty() } }.getOrNull()
    }
}

/**
 * Stable error codes raised by the SDK.
 *
 * Server-side codes mirror the `error` field in `/api/v1/scan` responses.
 * Local codes are raised before any network call — e.g. when the supplied
 * image bytes are invalid.
 *
 * Adding a new case is non-breaking; renaming or removing one is breaking.
 * [UNKNOWN] is the fallback for codes a newer server may introduce.
 */
enum class ANErrorCode(val raw: String) {
    // --- Server-reported ---
    UNAUTHORIZED("unauthorized"),
    KEY_REVOKED("key_revoked"),
    FEATURE_NOT_ALLOWED("feature_not_allowed"),
    QUOTA_EXCEEDED("quota_exceeded"),
    RATE_LIMITED("rate_limited"),
    INVALID_BODY("invalid_body"),
    EXPECTED_APPLICATION_JSON("expected_application_json"),
    INVALID_VERDICT("invalid_verdict"),
    INVALID_DETECTIONS("invalid_detections"),
    SERVICE_UNAVAILABLE("service_unavailable"),
    INTERNAL_ERROR("internal_error"),

    // --- Local-only (no network involved) ---
    EMPTY_IMAGE("empty_image"),
    UNSUPPORTED_FORMAT("unsupported_format"),
    IMAGE_TOO_LARGE("image_too_large"),
    MODEL_LOAD_FAILED("model_load_failed"),
    INFERENCE_FAILED("inference_failed"),
    NETWORK("network"),

    UNKNOWN("unknown");

    companion object {
        private val byRaw = values().associateBy { it.raw }
        fun fromRaw(raw: String?): ANErrorCode = byRaw[raw] ?: UNKNOWN
    }
}

class ANException(
    val code: ANErrorCode,
    /** HTTP status code if the error came from the server, `0` for local errors. */
    val statusCode: Int = 0,
    message: String,
) : IOException(
    if (statusCode == 0) "AN SDK error ${code.raw}: $message"
    else "AN SDK error ${code.raw} (HTTP $statusCode): $message",
) {
    /** True if the caller can reasonably retry the same request later. */
    val isRetryable: Boolean
        get() = code == ANErrorCode.RATE_LIMITED ||
                code == ANErrorCode.SERVICE_UNAVAILABLE ||
                code == ANErrorCode.NETWORK
}
