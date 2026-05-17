package com.an.sdk

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min

/**
 * On-device NudeNet detector for Android. Mirrors `Detector.swift` in the
 * iOS SDK and matches the Python reference at `an-model/inference.py`
 * within the golden tolerance defined in `an-model/PREPROCESSING.md`.
 *
 * Reuse a single instance across scans; the underlying [OrtSession] is not
 * thread-safe — call [detect] serially.
 */
internal class Detector(modelBytes: ByteArray) {

    private val env: OrtEnvironment
    private val session: OrtSession
    private val inputName: String

    init {
        try {
            env = OrtEnvironment.getEnvironment()
            session = env.createSession(modelBytes, OrtSession.SessionOptions())
            inputName = session.inputNames.firstOrNull()
                ?: throw ANException(ANErrorCode.MODEL_LOAD_FAILED, message = "model has no inputs")
        } catch (e: ANException) {
            throw e
        } catch (e: OrtException) {
            throw ANException(ANErrorCode.MODEL_LOAD_FAILED, message = e.message ?: "OrtException")
        } catch (e: Throwable) {
            throw ANException(ANErrorCode.MODEL_LOAD_FAILED, message = e.message ?: "unknown")
        }
    }

    fun detect(imageBytes: ByteArray): List<Detection> {
        val raw = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
            ?: throw ANException(ANErrorCode.UNSUPPORTED_FORMAT, message = "could not decode image")
        if (raw.width == 0 || raw.height == 0) {
            raw.recycle()
            throw ANException(ANErrorCode.UNSUPPORTED_FORMAT, message = "image has zero dimensions")
        }
        val origW = raw.width
        val origH = raw.height
        val s = max(origW, origH)

        val tensorBuffer: FloatBuffer
        try {
            tensorBuffer = preprocess(raw)
        } finally {
            raw.recycle()
        }

        val shape = longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong())
        return try {
            OnnxTensor.createTensor(env, tensorBuffer, shape).use { input ->
                session.run(mapOf(inputName to input)).use { result ->
                    @Suppress("UNCHECKED_CAST")
                    val out = result[0].value as Array<Array<FloatArray>> // [1][22][N]
                    val anchors = out[0][0].size
                    postprocess(out[0], anchors, origW, origH, s)
                }
            }
        } catch (e: OrtException) {
            throw ANException(ANErrorCode.INFERENCE_FAILED, message = e.message ?: "OrtException")
        }
    }

    // ----------------------------------------------------------------------
    // Preprocessing
    // ----------------------------------------------------------------------

    /**
     * Letterbox-pad to square (right/bottom black), resize to 320 with bilinear
     * filtering, normalize to [0,1], CHW float32 RGB. Returns a direct
     * FloatBuffer suitable for [OnnxTensor.createTensor].
     */
    private fun preprocess(src: Bitmap): FloatBuffer {
        val size = INPUT_SIZE
        // Single 320×320 ARGB_8888 canvas. Draw black background, then the
        // source rect scaled to fit at top-left — bilinear via Paint.
        val canvasBmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(canvasBmp)
        canvas.drawColor(Color.BLACK)

        val origW = src.width
        val origH = src.height
        val s = max(origW, origH)
        val scale = size.toFloat() / s.toFloat()
        val scaledW = origW * scale
        val scaledH = origH * scale

        val paint = Paint().apply {
            isAntiAlias = false
            isFilterBitmap = true  // bilinear, matches cv2 / iOS .medium
            isDither = false
        }
        canvas.drawBitmap(
            src,
            android.graphics.Rect(0, 0, origW, origH),
            android.graphics.RectF(0f, 0f, scaledW, scaledH),
            paint,
        )

        val plane = size * size
        val pixels = IntArray(plane)
        canvasBmp.getPixels(pixels, 0, size, 0, 0, size, size)
        canvasBmp.recycle()

        // Convert ARGB ints → CHW RGB float32 in [0,1].
        val buf = FloatBuffer.allocate(3 * plane)
        val inv = 1f / 255f
        // R channel
        for (i in 0 until plane) {
            buf.put(i, ((pixels[i] shr 16) and 0xFF) * inv)
        }
        // G channel
        for (i in 0 until plane) {
            buf.put(plane + i, ((pixels[i] shr 8) and 0xFF) * inv)
        }
        // B channel
        for (i in 0 until plane) {
            buf.put(2 * plane + i, (pixels[i] and 0xFF) * inv)
        }
        buf.rewind()
        return buf
    }

    // ----------------------------------------------------------------------
    // Postprocessing
    // ----------------------------------------------------------------------

    /**
     * Decode `out` of shape `[22, N]`, filter, NMS, scale to original image
     * coords, normalize bbox to `[0, 1]`.
     */
    private fun postprocess(
        out: Array<FloatArray>,
        anchors: Int,
        origW: Int,
        origH: Int,
        s: Int,
    ): List<Detection> {
        val scale = s.toFloat() / INPUT_SIZE.toFloat()
        val fW = origW.toFloat()
        val fH = origH.toFloat()

        val boxes = ArrayList<FloatArray>(anchors / 4)
        val scores = ArrayList<Float>(anchors / 4)
        val classIds = ArrayList<Int>(anchors / 4)

        for (i in 0 until anchors) {
            var maxScore = 0f
            var maxId = 0
            for (c in 0 until 18) {
                val v = out[4 + c][i]
                if (v > maxScore) {
                    maxScore = v
                    maxId = c
                }
            }
            if (maxScore < PRE_NMS_CONF) continue
            val cx = out[0][i]
            val cy = out[1][i]
            val w = out[2][i]
            val h = out[3][i]
            var x = (cx - w / 2f) * scale
            var y = (cy - h / 2f) * scale
            var bw = w * scale
            var bh = h * scale
            if (x < 0f) x = 0f
            if (y < 0f) y = 0f
            if (x > fW) x = fW
            if (y > fH) y = fH
            if (bw > fW - x) bw = fW - x
            if (bh > fH - y) bh = fH - y
            boxes.add(floatArrayOf(x, y, bw, bh))
            scores.add(maxScore)
            classIds.add(maxId)
        }

        val kept = nms(boxes, scores, NMS_SCORE_THRESHOLD, NMS_IOU_THRESHOLD)
        val out2 = ArrayList<Detection>(kept.size)
        for (idx in kept) {
            val b = boxes[idx]
            val nx = b[0].toDouble() / origW
            val ny = b[1].toDouble() / origH
            val nw = b[2].toDouble() / origW
            val nh = b[3].toDouble() / origH
            out2.add(
                Detection(
                    category = CLASS_NAMES[classIds[idx]],
                    score = round4(scores[idx].toDouble()),
                    bbox = listOf(round4(nx), round4(ny), round4(nw), round4(nh)),
                )
            )
        }
        out2.sortByDescending { it.score }
        return out2
    }

    // ----------------------------------------------------------------------
    // NMS
    // ----------------------------------------------------------------------

    private fun nms(
        boxes: List<FloatArray>,
        scores: List<Float>,
        scoreThreshold: Float,
        iouThreshold: Float,
    ): List<Int> {
        val eligible = scores.indices.filter { scores[it] >= scoreThreshold }
        val order = eligible.sortedByDescending { scores[it] }
        val kept = ArrayList<Int>()
        val suppressed = HashSet<Int>()
        for (i in order) {
            if (i in suppressed) continue
            kept.add(i)
            for (j in order) {
                if (j == i || j in suppressed) continue
                if (iou(boxes[i], boxes[j]) > iouThreshold) {
                    suppressed.add(j)
                }
            }
        }
        return kept
    }

    private fun iou(a: FloatArray, b: FloatArray): Float {
        val ax2 = a[0] + a[2]; val ay2 = a[1] + a[3]
        val bx2 = b[0] + b[2]; val by2 = b[1] + b[3]
        val ix1 = max(a[0], b[0]); val iy1 = max(a[1], b[1])
        val ix2 = min(ax2, bx2); val iy2 = min(ay2, by2)
        val iw = max(0f, ix2 - ix1); val ih = max(0f, iy2 - iy1)
        val inter = iw * ih
        val union = a[2] * a[3] + b[2] * b[3] - inter
        return if (union > 0f) inter / union else 0f
    }

    private fun round4(v: Double): Double = ((v * 10000).toInt()) / 10000.0

    companion object {
        const val MODEL_VERSION = "nudenet-320n-v3.4"
        const val INPUT_SIZE = 320

        /** Class names in model index order. Do NOT reorder. */
        val CLASS_NAMES: List<String> = listOf(
            "FEMALE_GENITALIA_COVERED",
            "FACE_FEMALE",
            "BUTTOCKS_EXPOSED",
            "FEMALE_BREAST_EXPOSED",
            "FEMALE_GENITALIA_EXPOSED",
            "MALE_BREAST_EXPOSED",
            "ANUS_EXPOSED",
            "FEET_EXPOSED",
            "BELLY_COVERED",
            "FEET_COVERED",
            "ARMPITS_COVERED",
            "ARMPITS_EXPOSED",
            "FACE_MALE",
            "BELLY_EXPOSED",
            "MALE_GENITALIA_EXPOSED",
            "ANUS_COVERED",
            "FEMALE_BREAST_COVERED",
            "BUTTOCKS_COVERED",
        )

        // Thresholds — keep in sync with an-model/inference.py and the iOS SDK.
        const val PRE_NMS_CONF = 0.20f
        const val NMS_SCORE_THRESHOLD = 0.25f
        const val NMS_IOU_THRESHOLD = 0.45f
        const val UNSAFE_SCORE_THRESHOLD = 0.50
        val UNSAFE_CLASSES: Set<String> = setOf(
            "FEMALE_BREAST_EXPOSED",
            "FEMALE_GENITALIA_EXPOSED",
            "MALE_GENITALIA_EXPOSED",
            "BUTTOCKS_EXPOSED",
            "ANUS_EXPOSED",
        )

        /** Mirrors `compute_verdict` in inference.py. */
        fun computeVerdict(detections: List<Detection>): Pair<String, Detection?> {
            val unsafe = detections.filter {
                it.category in UNSAFE_CLASSES && it.score >= UNSAFE_SCORE_THRESHOLD
            }
            val topUnsafe = unsafe.maxByOrNull { it.score }
            return if (topUnsafe != null) "unsafe" to topUnsafe
            else "safe" to detections.maxByOrNull { it.score }
        }
    }
}
