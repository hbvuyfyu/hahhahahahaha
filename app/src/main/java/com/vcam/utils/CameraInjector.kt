package com.vcam.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * CameraInjector — system-wide virtual camera injection.
 *
 * Primary strategy: VcplaxEngine (ShadowHook-based inline hooking via vcplax binary)
 *   - Works on Android 10–14 with Camera2 API
 *   - Injects into cameraserver process at HAL level
 *   - No v4l2loopback kernel module required
 *
 * Fallback strategy: legacy LD_PRELOAD + v4l2loopback
 *   - For older devices / kernels that support it
 */
class CameraInjector(
    private val context: Context,
    private val mediaPath: String,
    private val isVideo: Boolean,
    private val targetPackage: String?,
    var rotation: Int = 0,
    var mirror: Boolean = false,
    /** Digital zoom: 1.0 = no zoom, 2.0 = 2× (crops to 50% of image center) */
    @Volatile var zoomFactor: Float = 1f,
    /** Frame fill scale: 1.0 = fill full output frame, 0.5 = image occupies 50% with black bars */
    @Volatile var frameFillScale: Float = 1f,
    /** Horizontal pan offset in source pixels (positive = shift crop window right) */
    @Volatile var panX: Int = 0,
    /** Vertical pan offset in source pixels (positive = shift crop window down) */
    @Volatile var panY: Int = 0
) {
    companion object {
        private const val TAG      = "CameraInjector"
        private const val VCAM_DIR = "/data/local/tmp/vcam"
        private const val INJECT_LIB = "/data/local/tmp/libvcam_inject.so"
        private const val FRAME_FILE = "$VCAM_DIR/frame.yuyv"
        private const val META_FILE  = "$VCAM_DIR/frame_info"

        const val TARGET_W = 1280
        const val TARGET_H = 720

        init {
            try { System.loadLibrary("vcam_native") }
            catch (e: UnsatisfiedLinkError) { Log.w(TAG, "vcam_native: ${e.message}") }
        }

        @JvmStatic external fun nativeStartFrameLoop(width: Int, height: Int, videoDevice: String): Boolean
        @JvmStatic external fun nativeUpdateYUYVFrame(yuyvData: ByteArray, width: Int, height: Int)
        @JvmStatic external fun nativeStopInjection()
        @JvmStatic external fun nativeCheckDevice(videoDevice: String): Boolean
        @JvmStatic external fun nativeInjectImage(imagePath: String, videoDevice: String): Int
        @JvmStatic external fun nativeInjectVideo(videoPath: String, videoDevice: String): Int
    }

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    @Volatile private var running = false
    private var injectionJob: Job? = null
    @Volatile private var usingVcplax = false

    // ── Public API ──────────────────────────────────────────────────────────

    fun start() {
        running = true
        injectionJob = scope.launch { performInjection() }
    }

    fun stop() {
        running = false
        injectionJob?.cancel()

        if (usingVcplax) {
            // Stop via VcplaxEngine
            VcplaxEngine.stopInjection()
            usingVcplax = false
        } else {
            // Legacy cleanup
            cleanupAllWrapProps()
            try { nativeStopInjection() } catch (_: Exception) {}
            RootManager.runCommands(
                "pkill -f ffmpeg 2>/dev/null || true",
                "pkill -f v4l2  2>/dev/null || true"
            )
            RootManager.runCommand("setprop ctl.restart cameraserver")
        }
        Log.d(TAG, "VCam stopped")
    }

    // ── Core injection pipeline ─────────────────────────────────────────────

    private suspend fun performInjection() {
        Log.d(TAG, "performInjection: isVideo=$isVideo target=$targetPackage")

        // ── PRIMARY: VcplaxEngine (ShadowHook-based, works on Android 10-14) ──
        try {
            val engineReady = VcplaxEngine.setup(context)
            if (engineReady) {
                val started = VcplaxEngine.startInjection(mediaPath, loop = isVideo)
                if (started) {
                    usingVcplax = true
                    Log.d(TAG, "VcplaxEngine injection active ✓")

                    // Apply rotation/mirror settings
                    if (rotation != 0) VcplaxEngine.setRotation(rotation)
                    if (mirror)        VcplaxEngine.setMirror(true)

                    // Stay alive and propagate rotation/mirror changes
                    while (running) {
                        delay(500)
                        // Check if vcplax is still running
                        if (!VcplaxEngine.isRunning) {
                            Log.w(TAG, "vcplax stopped unexpectedly — restarting injection")
                            VcplaxEngine.startInjection(mediaPath, loop = isVideo)
                        }
                    }
                    return
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "VcplaxEngine failed: ${e.message}")
        }

        // ── FALLBACK: Legacy HAL1 + v4l2loopback ──────────────────────────────
        Log.w(TAG, "VcplaxEngine unavailable — falling back to legacy injection")
        legacyInject()
    }

    // ── Legacy injection (kept as fallback) ────────────────────────────────

    private suspend fun legacyInject() {
        setupInjectLib()
        tryLoadV4L2Module()
        val devices = RootManager.getVideoDevices()
        setupLdPreload()

        if (devices.isNotEmpty()) {
            val device = devices.last()
            val started = tryStartV4L2(device)
            if (started) { streamFramesToV4L2(device); return }
        }
        streamFramesToSharedFile()
    }

    private fun setupInjectLib() {
        val nativeDir = context.applicationInfo.nativeLibraryDir
        val srcLib    = File(nativeDir, "libvcam_inject.so")

        RootManager.runCommands("mkdir -p $VCAM_DIR", "chmod 777 $VCAM_DIR")

        if (srcLib.exists()) {
            RootManager.runCommands(
                "cp '${srcLib.absolutePath}' $INJECT_LIB",
                "chmod 755 $INJECT_LIB",
                "chown root:root $INJECT_LIB 2>/dev/null || true"
            )
        }
    }

    private fun setupLdPreload() {
        val propVal = "LD_PRELOAD=$INJECT_LIB"
        RootManager.runCommand("setenforce 0 2>/dev/null || true")

        listOf(
            "wrap.cameraserver",
            "wrap.android.hardware.camera.provider@2.4-service",
            "wrap.android.hardware.camera.provider@2.5-service",
            "wrap.android.hardware.camera.provider@2.6-service",
            "vendor.camera.hal.vendor"
        ).forEach { prop ->
            RootManager.runCommand("setprop '$prop' '$propVal'")
            RootManager.runCommand("resetprop '$prop' '$propVal' 2>/dev/null || true")
        }

        if (!targetPackage.isNullOrBlank()) {
            val wrapProp = "wrap.$targetPackage"
            RootManager.runCommand("setprop '$wrapProp' '$propVal'")
            RootManager.runCommand("resetprop '$wrapProp' '$propVal' 2>/dev/null || true")
        }

        RootManager.runCommands("setprop ctl.restart cameraserver", "sleep 1")
        if (!targetPackage.isNullOrBlank()) {
            RootManager.runCommand("am force-stop '$targetPackage'")
        }
    }

    private fun cleanupAllWrapProps() {
        listOf(
            "wrap.cameraserver",
            "wrap.android.hardware.camera.provider@2.4-service",
            "wrap.android.hardware.camera.provider@2.5-service",
            "wrap.android.hardware.camera.provider@2.6-service",
            "vendor.camera.hal.vendor"
        ).forEach { prop ->
            RootManager.runCommand("setprop '$prop' '' 2>/dev/null || true")
            RootManager.runCommand("resetprop --delete '$prop' 2>/dev/null || true")
        }
        if (!targetPackage.isNullOrBlank()) {
            RootManager.runCommand("setprop 'wrap.$targetPackage' '' 2>/dev/null || true")
            RootManager.runCommand("resetprop --delete 'wrap.$targetPackage' 2>/dev/null || true")
        }
    }

    private fun tryLoadV4L2Module() {
        RootManager.runCommands(
            "modprobe v4l2loopback devices=1 video_nr=10 card_label=VCam exclusive_caps=1 2>/dev/null || true",
            "insmod /vendor/lib/modules/v4l2loopback.ko   devices=1 2>/dev/null || true",
            "insmod /system/lib/modules/v4l2loopback.ko   devices=1 2>/dev/null || true",
            "insmod /system/lib64/modules/v4l2loopback.ko devices=1 2>/dev/null || true"
        )
    }

    private fun tryStartV4L2(device: String): Boolean {
        return try { nativeStartFrameLoop(TARGET_W, TARGET_H, device) }
        catch (e: Exception) { Log.e(TAG, "v4l2 start: ${e.message}"); false }
    }

    private suspend fun streamFramesToV4L2(device: String) {
        if (isVideo) streamVideo(pushToV4L2 = true) else streamImage(pushToV4L2 = true)
    }

    private suspend fun streamFramesToSharedFile() {
        if (isVideo) streamVideo(pushToV4L2 = false) else streamImage(pushToV4L2 = false)
    }

    private suspend fun streamImage(pushToV4L2: Boolean) = withContext(Dispatchers.IO) {
        val bitmap = loadAndTransformBitmap(mediaPath) ?: run {
            Log.e(TAG, "Cannot load image: $mediaPath"); return@withContext
        }
        val yuyv = bitmapToYUYV(bitmap, TARGET_W, TARGET_H)
        bitmap.recycle()
        nativeUpdateYUYVFrame(yuyv, TARGET_W, TARGET_H)
        while (running) delay(500)
    }

    private suspend fun streamVideo(pushToV4L2: Boolean) = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(mediaPath)
            val durationMs = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLongOrNull() ?: 5000L

            var posMs = 0L
            val frameIntervalMs = 33L

            while (running) {
                val frameBitmap = retriever.getFrameAtTime(
                    posMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                )
                if (frameBitmap != null) {
                    val transformed = applyTransforms(frameBitmap)
                    val yuyv = bitmapToYUYV(transformed, TARGET_W, TARGET_H)
                    if (transformed !== frameBitmap) transformed.recycle()
                    frameBitmap.recycle()
                    nativeUpdateYUYVFrame(yuyv, TARGET_W, TARGET_H)
                }
                posMs += frameIntervalMs
                if (posMs >= durationMs) posMs = 0L
                delay(frameIntervalMs)
            }
        } finally { retriever.release() }
    }

    // ── Bitmap helpers ──────────────────────────────────────────────────────

    private fun loadAndTransformBitmap(path: String): Bitmap? {
        val raw = try { BitmapFactory.decodeFile(path) ?: return null }
                  catch (e: Exception) { return null }
        return applyTransforms(raw)
    }

    /**
     * Apply all transforms in order:
     *   1. Rotation + mirror (existing)
     *   2. Digital zoom  — crops into the image center by zoomFactor
     *   3. Pan           — shifts the crop window by (panX, panY) source pixels
     *   4. Frame scale   — letterboxes/pillarboxes the result into the output frame
     *
     * Zoom and pan are applied by computing a crop rectangle on the
     * (possibly rotated/mirrored) bitmap, then scaling the crop to TARGET_W×TARGET_H.
     * Frame scale further shrinks that result and composites it onto a black canvas.
     */
    private fun applyTransforms(src: Bitmap): Bitmap {
        // 1. Rotation + mirror
        val base: Bitmap = if (rotation != 0 || mirror) {
            val m = Matrix()
            if (rotation != 0) m.postRotate(rotation.toFloat())
            if (mirror) m.postScale(-1f, 1f, src.width / 2f, src.height / 2f)
            Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        } else src

        // 2 & 3. Zoom + pan crop
        val zoom = zoomFactor.coerceIn(1f, 5f)
        val px   = panX
        val py   = panY

        val zoomed: Bitmap = if (zoom != 1f || px != 0 || py != 0) {
            val cropW = (base.width  / zoom).toInt().coerceAtLeast(1)
            val cropH = (base.height / zoom).toInt().coerceAtLeast(1)

            // Center of crop, offset by pan
            val centerX = base.width  / 2 + px
            val centerY = base.height / 2 + py
            val left = (centerX - cropW / 2).coerceIn(0, (base.width  - cropW).coerceAtLeast(0))
            val top  = (centerY - cropH / 2).coerceIn(0, (base.height - cropH).coerceAtLeast(0))

            val cropped = Bitmap.createBitmap(base, left, top, cropW, cropH)
            if (base !== src) base.recycle()
            cropped
        } else base

        // 4. Frame scale (letterbox / pillarbox)
        val scale = frameFillScale.coerceIn(0.1f, 2f)
        return if (scale != 1f) {
            val scaledW = (TARGET_W * scale).toInt().coerceAtLeast(1)
            val scaledH = (TARGET_H * scale).toInt().coerceAtLeast(1)
            val scaled  = Bitmap.createScaledBitmap(zoomed, scaledW, scaledH, true)
            if (zoomed !== src && zoomed !== base) zoomed.recycle()

            val canvas = Bitmap.createBitmap(TARGET_W, TARGET_H, Bitmap.Config.ARGB_8888)
            val c = Canvas(canvas)
            c.drawARGB(255, 0, 0, 0)  // black background
            val dx = (TARGET_W - scaledW) / 2f
            val dy = (TARGET_H - scaledH) / 2f
            c.drawBitmap(scaled, dx, dy, null)
            scaled.recycle()
            canvas
        } else {
            zoomed
        }
    }

    private fun bitmapToYUYV(src: Bitmap, outW: Int, outH: Int): ByteArray {
        val bmp    = if (src.width != outW || src.height != outH)
                         Bitmap.createScaledBitmap(src, outW, outH, true) else src
        val pixels = IntArray(outW * outH)
        bmp.getPixels(pixels, 0, outW, 0, 0, outW, outH)
        if (bmp !== src) bmp.recycle()

        val yuyv = ByteArray(outW * outH * 2)
        var idx = 0; var pi = 0
        while (pi < pixels.size - 1) {
            val p0 = pixels[pi]; val p1 = pixels[pi + 1]
            val r0 = (p0 shr 16) and 0xff; val g0 = (p0 shr 8) and 0xff; val b0 = p0 and 0xff
            val r1 = (p1 shr 16) and 0xff; val g1 = (p1 shr 8) and 0xff; val b1 = p1 and 0xff
            val y0 = ((66*r0+129*g0+25*b0+128) shr 8)+16
            val y1 = ((66*r1+129*g1+25*b1+128) shr 8)+16
            val u  = ((-38*r0-74*g0+112*b0+128) shr 8)+128
            val v  = ((112*r0-94*g0-18*b0+128) shr 8)+128
            yuyv[idx++] = y0.coerceIn(16,235).toByte()
            yuyv[idx++] = u.coerceIn(16,240).toByte()
            yuyv[idx++] = y1.coerceIn(16,235).toByte()
            yuyv[idx++] = v.coerceIn(16,240).toByte()
            pi += 2
        }
        return yuyv
    }
}
