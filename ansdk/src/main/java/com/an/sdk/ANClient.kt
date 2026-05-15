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
        if (bytes.isEmpty()) throw IllegalArgumentException("empty image")
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

        val conn = (URL(baseUrl + "/api/v1/scan").openConnection() as HttpURLConnection).apply {
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

        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val raw = stream?.bufferedReader()?.use { it.readText() } ?: ""
        conn.disconnect()

        if (code !in 200..299) throw ANException(code, raw.ifBlank { "HTTP $code" })
        JSONObject(raw).optString("request_id").takeIf { it.isNotEmpty() }
    }
}

class ANException(val statusCode: Int, message: String) :
    IOException("AN SDK error $statusCode: $message")
