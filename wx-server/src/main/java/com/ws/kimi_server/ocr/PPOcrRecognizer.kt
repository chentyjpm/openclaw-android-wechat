package com.ws.wx_server.ocr

import android.content.Context
import android.graphics.Bitmap
import com.baidu.paddle.fastdeploy.LitePowerMode
import com.equationl.fastdeployocr.ModelVersion
import com.equationl.fastdeployocr.OCR
import com.equationl.fastdeployocr.OcrConfig
import com.equationl.fastdeployocr.RunType
import com.ws.wx_server.util.Logger
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

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
        if (!prepareModelFiles(modelDir)) {
            Logger.w("PPOCR model prepare failed: ${modelDir.absolutePath}")
            return false
        }

        val label = File(modelDir, LABEL_FILE)
        val engine = OCR(context)
        val cfg = OcrConfig(
            modelPath = modelDir.absolutePath,
            labelPath = label.absolutePath,
            cpuThreadNum = 4,
            cpuPowerMode = LitePowerMode.LITE_POWER_HIGH,
            scoreThreshold = 0.45f,
            detModelFileName = DET_MODEL_BASENAME,
            recModelFileName = REC_MODEL_BASENAME,
            clsModelFileName = CLS_MODEL_BASENAME,
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

    private fun prepareModelFiles(modelDir: File): Boolean {
        if (hasAllModelFiles(modelDir)) return true
        synchronized(downloadLock) {
            if (hasAllModelFiles(modelDir)) return true
            if (!modelDir.exists() && !modelDir.mkdirs()) {
                Logger.w("PPOCR create model dir failed: ${modelDir.absolutePath}")
                return false
            }
            Logger.i("PPOCR downloading models to ${modelDir.absolutePath}")
            val ok = try {
                downloadAndExtractModel(url = DET_MODEL_TAR_URL, targetBaseName = DET_MODEL_BASENAME, targetDir = modelDir) &&
                    downloadAndExtractModel(url = REC_MODEL_TAR_URL, targetBaseName = REC_MODEL_BASENAME, targetDir = modelDir) &&
                    downloadAndExtractModel(url = CLS_MODEL_TAR_URL, targetBaseName = CLS_MODEL_BASENAME, targetDir = modelDir) &&
                    downloadFile(url = KEYS_URL, dest = File(modelDir, LABEL_FILE))
            } catch (t: Throwable) {
                Logger.w("PPOCR download failed: ${t.message}")
                false
            }
            if (ok) Logger.i("PPOCR model download success")
            return ok && hasAllModelFiles(modelDir)
        }
    }

    private fun hasAllModelFiles(dir: File): Boolean {
        return File(dir, "$DET_MODEL_BASENAME.pdmodel").exists() &&
            File(dir, "$DET_MODEL_BASENAME.pdiparams").exists() &&
            File(dir, "$REC_MODEL_BASENAME.pdmodel").exists() &&
            File(dir, "$REC_MODEL_BASENAME.pdiparams").exists() &&
            File(dir, "$CLS_MODEL_BASENAME.pdmodel").exists() &&
            File(dir, "$CLS_MODEL_BASENAME.pdiparams").exists() &&
            File(dir, LABEL_FILE).exists()
    }

    private fun downloadAndExtractModel(url: String, targetBaseName: String, targetDir: File): Boolean {
        val workDir = File(targetDir, "_tmp_${targetBaseName}_${System.currentTimeMillis()}")
        if (!workDir.mkdirs()) return false
        val tarFile = File(workDir, "$targetBaseName.tar")
        try {
            if (!downloadFile(url, tarFile)) return false
            val extractedDir = File(workDir, "extract")
            if (!extractedDir.mkdirs()) return false
            if (!extractTar(tarFile, extractedDir)) return false
            val pdmodel = extractedDir.walkTopDown().firstOrNull { it.isFile && it.name.endsWith(".pdmodel") } ?: return false
            val pdiparams = extractedDir.walkTopDown().firstOrNull { it.isFile && it.name.endsWith(".pdiparams") } ?: return false
            pdmodel.copyTo(File(targetDir, "$targetBaseName.pdmodel"), overwrite = true)
            pdiparams.copyTo(File(targetDir, "$targetBaseName.pdiparams"), overwrite = true)
            return true
        } finally {
            workDir.deleteRecursively()
        }
    }

    private fun downloadFile(url: String, dest: File): Boolean {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            requestMethod = "GET"
        }
        return try {
            conn.connect()
            if (conn.responseCode !in 200..299) {
                Logger.w("PPOCR download HTTP ${conn.responseCode}: $url")
                false
            } else {
                val tmp = File(dest.parentFile, "${dest.name}.part")
                BufferedInputStream(conn.inputStream).use { input ->
                    BufferedOutputStream(FileOutputStream(tmp)).use { output ->
                        val buf = ByteArray(8192)
                        while (true) {
                            val len = input.read(buf)
                            if (len <= 0) break
                            output.write(buf, 0, len)
                        }
                    }
                }
                if (dest.exists()) dest.delete()
                tmp.renameTo(dest)
                true
            }
        } catch (t: Throwable) {
            Logger.w("PPOCR download error: ${t.message}")
            false
        } finally {
            conn.disconnect()
        }
    }

    private fun extractTar(tarFile: File, outputDir: File): Boolean {
        BufferedInputStream(FileInputStream(tarFile)).use { input ->
            val header = ByteArray(512)
            while (true) {
                val read = input.read(header)
                if (read < 512) break
                if (header.all { it.toInt() == 0 }) break

                val name = readTarString(header, 0, 100)
                if (name.isBlank()) break
                val size = readTarOctal(header, 124, 12)
                val typeFlag = header[156].toInt().toChar()
                val outFile = File(outputDir, name)

                if (typeFlag == '5') {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { output ->
                        var remaining = size
                        val buf = ByteArray(8192)
                        while (remaining > 0) {
                            val toRead = minOf(buf.size.toLong(), remaining).toInt()
                            val n = input.read(buf, 0, toRead)
                            if (n <= 0) break
                            output.write(buf, 0, n)
                            remaining -= n
                        }
                    }
                    val skipPadding = (512 - (size % 512)) % 512
                    if (skipPadding > 0) input.skip(skipPadding)
                }
            }
        }
        return true
    }

    private fun readTarString(buffer: ByteArray, offset: Int, length: Int): String {
        val end = (offset until offset + length).firstOrNull { buffer[it].toInt() == 0 } ?: (offset + length)
        return String(buffer, offset, end - offset).trim()
    }

    private fun readTarOctal(buffer: ByteArray, offset: Int, length: Int): Long {
        val raw = String(buffer, offset, length).trim().trim('\u0000')
        return raw.toLongOrNull(8) ?: 0L
    }

    companion object {
        private val downloadLock = Any()
        const val DET_MODEL_BASENAME = "det"
        const val REC_MODEL_BASENAME = "rec"
        const val CLS_MODEL_BASENAME = "cls"
        const val LABEL_FILE = "ppocr_keys_v1.txt"
        const val DET_MODEL_TAR_URL = "https://paddleocr.bj.bcebos.com/PP-OCRv3/chinese/ch_PP-OCRv3_det_infer.tar"
        const val REC_MODEL_TAR_URL = "https://paddleocr.bj.bcebos.com/PP-OCRv3/chinese/ch_PP-OCRv3_rec_infer.tar"
        const val CLS_MODEL_TAR_URL = "https://paddleocr.bj.bcebos.com/dygraph_v2.0/ch/ch_ppocr_mobile_v2.0_cls_infer.tar"
        const val KEYS_URL = "https://raw.githubusercontent.com/PaddlePaddle/PaddleOCR/release/2.6/ppocr/utils/ppocr_keys_v1.txt"
    }
}
