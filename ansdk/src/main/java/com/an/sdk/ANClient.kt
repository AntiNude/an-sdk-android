package com.an.sdk

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.random.Random
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
 * Privacy model: the NSFW classifier runs **fully on-device**. No image bytes
 * ever leave the device. After local inference, the SDK reports only the
 * resulting verdict (and minimal metadata) to the AntiNude backend so the
 * developer can see usage in the dashboard.
 *
 * v0.3.0 ships a mock on-device model — verdicts and detections are
 * randomized. A real TFLite/ONNX model will replace [runLocalModel] in a
 * future version without changing the public API.
 */
class ANClient(
    private val apiKey: String,
    private val baseUrl: String = "https://antinude.site",
    private val reportToServer: Boolean = true,
) {

    /** Scan an image on device and (by default) report the verdict. */
    suspend fun scanImage(bytes: ByteArray): ScanResult {
        val result: ScanResult
        val ms = measureTimeMillis {
            result = runLocalModel(bytes)
        }
        val full = result.copy(latencyMs = ms.toInt())
        if (reportToServer) {
            val rid = runCatching { reportVerdict(full) }.getOrNull()
            return full.copy(requestId = rid)
        }
        return full
    }

    // ---- on-device inference (mock) -------------------------------------------------

    private val modelVersion = "mock-v0.3.0"

    private val mockUnsafeClasses = arrayOf(
        "FEMALE_BREAST_EXPOSED",
        "FEMALE_GENITALIA_EXPOSED",
        "MALE_GENITALIA_EXPOSED",
        "BUTTOCKS_EXPOSED",
    )
    private val mockSafeClasses = arrayOf(
        "FEMALE_BREAST_COVERED",
        "FACE_FEMALE",
        "FACE_MALE",
        "FEET_EXPOSED",
    )

    private fun round4(v: Double): Double = (v * 10000).toInt() / 10000.0

    private fun runLocalModel(bytes: ByteArray): ScanResult {
        if (bytes.isEmpty()) throw ANException(ANErrorCode.EMPTY_IMAGE, message = "empty image")
        // Pretend the model takes 30–80 ms to run.
        Thread.sleep(30L + Random.nextLong(50))
        val unsafe = Random.nextDouble() < 0.15
        val pool = if (unsafe) mockUnsafeClasses else mockSafeClasses

        val count = 1 + Random.nextInt(3)
        val detections = ArrayList<Detection>(count)
        repeat(count) {
            val cat = pool.random()
            val score = if (unsafe) 0.70 + Random.nextDouble() * 0.30 else Random.nextDouble() * 0.30
            val x = Random.nextDouble() * 0.7
            val y = Random.nextDouble() * 0.7
            val w = 0.1 + Random.nextDouble() * (minOf(0.3, 1.0 - x) - 0.1).coerceAtLeast(0.0)
            val h = 0.1 + Random.nextDouble() * (minOf(0.3, 1.0 - y) - 0.1).coerceAtLeast(0.0)
            detections.add(
                Detection(
                    category = cat,
                    score = round4(score),
                    bbox = listOf(round4(x), round4(y), round4(w), round4(h)),
                )
            )
        }
        val top = detections.maxByOrNull { it.score }
        return ScanResult(
            verdict = if (unsafe) "unsafe" else "safe",
            topCategory = top?.category,
            topScore = top?.score,
            latencyMs = 0,
            modelVersion = modelVersion,
            requestId = null,
            detections = detections,
        )
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
                setRequestProperty("User-Agent", "an-sdk-android/0.3.0 (Android)")
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
