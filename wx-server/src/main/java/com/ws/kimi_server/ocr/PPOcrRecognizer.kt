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
        synchronized(lock) {
            if (!ensureInited()) return null
            val native = engine ?: return null
            return try {
                val results = native.Detect(bitmap, false) ?: return null
                val text = results
                    .filterNotNull()
                    .sortedWith(compareBy<PaddleOCRNcnn.Obj>({ minOf(it.y0, it.y1, it.y2, it.y3) }, { minOf(it.x0, it.x1, it.x2, it.x3) }))
                    .mapNotNull { it.label?.trim()?.takeIf { s -> s.isNotEmpty() } }
                    .joinToString("\n")
                    .trim()
                text.takeIf { it.isNotEmpty() }
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
            "pdocrv2.0_det-op.param",
            "pdocrv2.0_det-op.bin",
            "pdocrv2.0_rec-op.param",
            "pdocrv2.0_rec-op.bin",
            "paddleocr_keys.txt",
        )
    }
}
