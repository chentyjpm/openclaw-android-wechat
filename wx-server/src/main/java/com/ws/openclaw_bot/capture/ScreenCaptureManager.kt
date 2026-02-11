package com.ws.wx_server.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import com.ws.wx_server.util.Logger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object ScreenCaptureManager {
    private val lock = Any()
    private val thread = HandlerThread("LanBotScreenCapture").apply { start() }
    private val handler = Handler(thread.looper)

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var width = 0
    private var height = 0
    private var densityDpi = 0

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            synchronized(lock) {
                releaseLocked()
            }
            Logger.w("MediaProjection stopped", tag = "LanBotOCR")
        }
    }

    fun captureBitmap(context: Context, timeoutMs: Long = 600L): Bitmap? {
        synchronized(lock) {
            if (!ensureSessionLocked(context.applicationContext)) return null
            val reader = imageReader ?: return null

            // Drop stale frame first.
            try {
                reader.acquireLatestImage()?.close()
            } catch (_: Throwable) {
            }

            val latch = CountDownLatch(1)
            var out: Bitmap? = null
            val listener = ImageReader.OnImageAvailableListener { ir ->
                val image = try {
                    ir.acquireLatestImage()
                } catch (_: Throwable) {
                    null
                }
                if (image != null) {
                    try {
                        out = imageToBitmap(image)
                    } finally {
                        try {
                            image.close()
                        } catch (_: Throwable) {
                        }
                    }
                    latch.countDown()
                }
            }

            try {
                reader.setOnImageAvailableListener(listener, handler)
                val ok = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
                if (!ok && out == null) {
                    try {
                        reader.acquireLatestImage()?.let { image ->
                            try {
                                out = imageToBitmap(image)
                            } finally {
                                image.close()
                            }
                        }
                    } catch (_: Throwable) {
                    }
                }
                if (out == null) {
                    Logger.w("MediaProjection capture timeout", tag = "LanBotOCR")
                }
                return out
            } catch (t: Throwable) {
                Logger.w("MediaProjection capture exception: ${t.message}", tag = "LanBotOCR")
                return null
            } finally {
                try {
                    reader.setOnImageAvailableListener(null, null)
                } catch (_: Throwable) {
                }
            }
        }
    }

    fun release() {
        synchronized(lock) {
            releaseLocked()
        }
    }

    private fun ensureSessionLocked(context: Context): Boolean {
        if (mediaProjection != null && virtualDisplay != null && imageReader != null) return true
        val permission = ScreenCapturePermissionStore.load(context)
        if (permission == null) {
            Logger.i("MediaProjection permission missing", tag = "LanBotOCR")
            return false
        }
        val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
        if (manager == null) {
            Logger.w("MediaProjectionManager unavailable", tag = "LanBotOCR")
            return false
        }
        val projection = try {
            manager.getMediaProjection(permission.resultCode, permission.dataIntent)
        } catch (t: Throwable) {
            Logger.w("MediaProjection getMediaProjection failed: ${t.message}", tag = "LanBotOCR")
            null
        } ?: run {
            ScreenCapturePermissionStore.clear(context)
            return false
        }

        val dm = context.resources.displayMetrics
        width = dm.widthPixels
        height = dm.heightPixels
        densityDpi = dm.densityDpi

        if (width <= 0 || height <= 0 || densityDpi <= 0) {
            Logger.w("MediaProjection invalid display metrics", tag = "LanBotOCR")
            try {
                projection.stop()
            } catch (_: Throwable) {
            }
            return false
        }

        val reader = try {
            ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        } catch (t: Throwable) {
            Logger.w("MediaProjection create ImageReader failed: ${t.message}", tag = "LanBotOCR")
            try {
                projection.stop()
            } catch (_: Throwable) {
            }
            return false
        }

        val vd = try {
            projection.createVirtualDisplay(
                "LanBotScreenCapture",
                width,
                height,
                densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                handler,
            )
        } catch (t: Throwable) {
            Logger.w("MediaProjection createVirtualDisplay failed: ${t.message}", tag = "LanBotOCR")
            try {
                reader.close()
            } catch (_: Throwable) {
            }
            try {
                projection.stop()
            } catch (_: Throwable) {
            }
            return false
        }

        try {
            projection.registerCallback(projectionCallback, handler)
        } catch (_: Throwable) {
        }
        mediaProjection = projection
        imageReader = reader
        virtualDisplay = vd
        Logger.i("MediaProjection session ready: ${width}x${height}", tag = "LanBotOCR")
        return true
    }

    private fun releaseLocked() {
        try {
            virtualDisplay?.release()
        } catch (_: Throwable) {
        }
        virtualDisplay = null
        try {
            imageReader?.close()
        } catch (_: Throwable) {
        }
        imageReader = null
        try {
            mediaProjection?.unregisterCallback(projectionCallback)
        } catch (_: Throwable) {
        }
        try {
            mediaProjection?.stop()
        } catch (_: Throwable) {
        }
        mediaProjection = null
    }

    private fun imageToBitmap(image: android.media.Image): Bitmap? {
        val plane = image.planes.firstOrNull() ?: return null
        val buffer = plane.buffer ?: return null
        val width = image.width
        val height = image.height
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        if (width <= 0 || height <= 0 || pixelStride <= 0 || rowStride <= 0) return null
        val rowPadding = rowStride - pixelStride * width
        val bitmapWidth = width + rowPadding / pixelStride
        if (bitmapWidth <= 0) return null
        val raw = Bitmap.createBitmap(bitmapWidth, height, Bitmap.Config.ARGB_8888)
        raw.copyPixelsFromBuffer(buffer)
        if (bitmapWidth == width) return raw
        val cropped = Bitmap.createBitmap(raw, 0, 0, width, height)
        raw.recycle()
        return cropped
    }
}
