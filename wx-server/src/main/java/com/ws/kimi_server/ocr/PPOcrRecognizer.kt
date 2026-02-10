package com.ws.wx_server.ocr

import android.content.Context
import android.graphics.Bitmap
import com.baidu.paddle.fastdeploy.LitePowerMode
import com.equationl.fastdeployocr.ModelVersion
import com.equationl.fastdeployocr.OCR
import com.equationl.fastdeployocr.OcrConfig
import com.equationl.fastdeployocr.RunType
import com.ws.wx_server.util.Logger
import java.io.File

class PPOcrRecognizer(private val context: Context) {
    private val lock = Any()
    private var inited = false
    private var ocr: OCR? = null

    fun recognize(bitmap: Bitmap): String? {
        synchronized(lock) {
            if (!ensureInited()) return null
            val engine = ocr ?: return null
            return try {
                val result = engine.runSync(bitmap)
                result.getOrNull()?.simpleText?.trim()?.takeIf { it.isNotEmpty() }
            } catch (t: Throwable) {
                Logger.w("PPOCR run failed: ${t.message}")
                null
            }
        }
    }

    private fun ensureInited(): Boolean {
        if (inited) return true
        val modelDir = File(context.filesDir, "ppocr")
        val det = File(modelDir, DET_MODEL)
        val rec = File(modelDir, REC_MODEL)
        val cls = File(modelDir, CLS_MODEL)
        val label = File(modelDir, LABEL_FILE)
        if (!det.exists() || !rec.exists() || !cls.exists() || !label.exists()) {
            Logger.w("PPOCR model files missing in ${modelDir.absolutePath}")
            return false
        }

        val engine = OCR(context)
        val cfg = OcrConfig(
            modelPath = modelDir.absolutePath,
            labelPath = label.absolutePath,
            cpuThreadNum = 4,
            cpuPowerMode = LitePowerMode.LITE_POWER_HIGH,
            scoreThreshold = 0.45f,
            detModelFileName = DET_MODEL,
            recModelFileName = REC_MODEL,
            clsModelFileName = CLS_MODEL,
            runType = RunType.All,
            modelVersion = ModelVersion.V3,
        )
        return try {
            val init = engine.initModelSync(cfg)
            if (init.isFailure) {
                Logger.w("PPOCR init failed: ${init.exceptionOrNull()?.message}")
                false
            } else {
                ocr = engine
                inited = true
                Logger.i("PPOCR init success: ${modelDir.absolutePath}")
                true
            }
        } catch (t: Throwable) {
            Logger.w("PPOCR init exception: ${t.message}")
            false
        }
    }

    companion object {
        const val DET_MODEL = "ch_PP-OCRv3_det_infer.onnx"
        const val REC_MODEL = "ch_PP-OCRv3_rec_infer.onnx"
        const val CLS_MODEL = "ch_ppocr_mobile_v2.0_cls_infer.onnx"
        const val LABEL_FILE = "ppocr_keys_v1.txt"
    }
}

