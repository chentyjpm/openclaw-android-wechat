package com.ws.wx_server.ocr

import android.content.Context
import android.graphics.Bitmap
import com.tencent.paddleocrncnn.PaddleOCRNcnn
import com.ws.wx_server.util.Logger

class PPOcrRecognizer(private val context: Context) {
    private val lock = Any()
    private var inited = false
    private var engine: PaddleOCRNcnn? = null

    fun recognize(bitmap: Bitmap): String? {
        return recognizeDetailed(bitmap)?.text
    }

    fun recognizeDetailed(bitmap: Bitmap): OcrResult? {
        synchronized(lock) {
            if (!ensureInited()) return null
            val native = engine ?: return null
            return try {
                val results = native.Detect(bitmap, false) ?: return null
                val lines = results
                    .filterNotNull()
                    .sortedWith(compareBy<PaddleOCRNcnn.Obj>({ minOf(it.y0, it.y1, it.y2, it.y3) }, { minOf(it.x0, it.x1, it.x2, it.x3) }))
                    .mapNotNull { obj ->
                        val label = obj.label?.trim()?.takeIf { s -> s.isNotEmpty() } ?: return@mapNotNull null
                        val quad = listOf(
                            Point(obj.x0, obj.y0),
                            Point(obj.x1, obj.y1),
                            Point(obj.x2, obj.y2),
                            Point(obj.x3, obj.y3),
                        )
                        val xs = quad.map { it.x }
                        val ys = quad.map { it.y }
                        OcrLine(
                            text = label,
                            prob = obj.prob,
                            quad = quad,
                            left = xs.minOrNull() ?: 0f,
                            top = ys.minOrNull() ?: 0f,
                            right = xs.maxOrNull() ?: 0f,
                            bottom = ys.maxOrNull() ?: 0f,
                        )
                    }
                val text = lines
                    .map { it.text }
                    .joinToString("\n")
                    .trim()
                if (lines.isEmpty()) null else OcrResult(text = text, lines = lines)
            } catch (t: Throwable) {
                Logger.w("NCNN OCR detect failed: ${t.message}")
                null
            }
        }
    }

    fun warmUp(): Boolean {
        synchronized(lock) {
            return ensureInited()
        }
    }

    private fun ensureInited(): Boolean {
        if (inited) return true
        if (!hasAllRequiredAssets()) {
            Logger.w("NCNN OCR assets missing, warmup skipped")
            return false
        }
        val ocr = try {
            PaddleOCRNcnn()
        } catch (t: Throwable) {
            Logger.w("NCNN OCR create engine failed: ${t.message}")
            return false
        }
        val ok = try {
            ocr.Init(context.assets)
        } catch (t: Throwable) {
            Logger.w("NCNN OCR init exception: ${t.message}")
            false
        }
        if (!ok) {
            Logger.w("NCNN OCR init failed")
            return false
        }
        engine = ocr
        inited = true
        Logger.i("NCNN OCR init success")
        return true
    }

    private fun hasAllRequiredAssets(): Boolean {
        return REQUIRED_ASSETS.all { name ->
            try {
                context.assets.open(name).use { input ->
                    input.read() >= 0
                }
            } catch (_: Throwable) {
                false
            }
        }
    }

    companion object {
        private val REQUIRED_ASSETS = listOf(
            "PP_OCRv5_mobile_det.ncnn.param",
            "PP_OCRv5_mobile_det.ncnn.bin",
            "PP_OCRv5_mobile_rec.ncnn.param",
            "PP_OCRv5_mobile_rec.ncnn.bin",
        )
    }

    data class OcrResult(
        val text: String,
        val lines: List<OcrLine>,
    )

    data class OcrLine(
        val text: String,
        val prob: Float,
        val quad: List<Point>,
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
    )

    data class Point(
        val x: Float,
        val y: Float,
    )
}
