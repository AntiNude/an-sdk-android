package com.an.sdk

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.random.Random
import kotlin.system.measureTimeMillis

data class ScanResult(
    val verdict: String,
    val topCategory: String?,
    val topScore: Double?,
    val latencyMs: Int,
    val modelVersion: String,
    val requestId: String?,
)

/**
 * AntiNude SDK client.
 *
 * Privacy model: the NSFW classifier runs **fully on-device**. No image bytes
 * ever leave the device. After local inference, the SDK reports only the
 * resulting verdict (and minimal metadata) to the AntiNude backend so the
 * developer can see usage in the dashboard.
 *
 * v0.2.0 ships a mock on-device model — verdicts are randomized. A real
 * TFLite model will replace [runLocalModel] in a future version without
 * changing the public API.
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

    private val modelVersion = "mock-v0.2.0"

    private fun runLocalModel(bytes: ByteArray): ScanResult {
        // Pretend the model takes 30–80 ms to run.
        Thread.sleep(30L + Random.nextLong(50))
        val unsafe = Random.nextDouble() < 0.15
        val category = if (unsafe) {
            arrayOf("nudity", "suggestive", "sexual_violence", "gore").random()
        } else {
            arrayOf("nudity", "suggestive").random()
        }
        val score = if (unsafe) 0.70 + Random.nextDouble() * 0.30 else Random.nextDouble() * 0.30
        // Touch [bytes] so the parameter is not unused warning-wise.
        if (bytes.isEmpty()) throw IllegalArgumentException("empty image")
        return ScanResult(
            verdict = if (unsafe) "unsafe" else "safe",
            topCategory = category,
            topScore = (score * 10000).toInt() / 10000.0,
            latencyMs = 0,
            modelVersion = modelVersion,
            requestId = null,
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
        }.toString().toByteArray()

        val conn = (URL(baseUrl + "/api/v1/scan").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "an-sdk-android/0.2.0 (Android)")
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
