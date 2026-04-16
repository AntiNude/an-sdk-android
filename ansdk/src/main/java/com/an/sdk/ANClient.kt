package com.an.sdk

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class ScanResult(
    val verdict: String,
    val topCategory: String?,
    val topScore: Double?,
    val requestId: String,
    val latencyMs: Int,
)

class ANClient(
    private val apiKey: String,
    private val baseUrl: String = "https://antinude.site",
) {

    suspend fun scanImage(bytes: ByteArray, mime: String = "image/jpeg"): ScanResult =
        withContext(Dispatchers.IO) {
            request(
                path = "/api/v1/scan",
                contentType = mime,
                body = bytes,
            )
        }

    suspend fun scanImageUrl(url: String): ScanResult =
        withContext(Dispatchers.IO) {
            val payload = JSONObject().put("image_url", url).toString().toByteArray()
            request(
                path = "/api/v1/scan",
                contentType = "application/json",
                body = payload,
            )
        }

    private fun request(path: String, contentType: String, body: ByteArray): ScanResult {
        val conn = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", contentType)
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "an-sdk-android/0.2.0 (Android)")
        }
        conn.outputStream.use { it.write(body) }

        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val raw = stream?.bufferedReader()?.use { it.readText() } ?: ""
        conn.disconnect()

        if (code !in 200..299) {
            throw ANException(code, raw.ifBlank { "HTTP $code" })
        }

        val json = JSONObject(raw)
        return ScanResult(
            verdict = json.optString("verdict"),
            topCategory = json.optString("top_category").takeIf { it.isNotEmpty() },
            topScore = if (json.has("top_score") && !json.isNull("top_score")) json.optDouble("top_score") else null,
            requestId = json.optString("request_id"),
            latencyMs = json.optInt("latency_ms"),
        )
    }
}

class ANException(val statusCode: Int, message: String) : IOException("AN SDK error $statusCode: $message")
