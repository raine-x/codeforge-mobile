package com.hxy.codeforge.phone

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 手机操控媒体前台服务与控制器：
 * - 屏幕捕获（`MediaProjection`）授权托管与 `.mp4` 录屏（含 API < 30 单帧截图回退）；
 * - 麦克风 `.m4a` 录音（支持开始、暂停、继续、停止）；
 * - 录屏/录音产物自动保存至当前工作区 `phone_artifacts/` 并登记进 [PhoneArtifactStore]。
 */
object PhoneMediaController {

    private val lock = Any()

    @Volatile
    private var projectionConsent: PhonePermissionBridge.ProjectionConsent? = null

    @Volatile
    private var projection: MediaProjection? = null

    @Volatile
    private var screenRecorder: MediaRecorder? = null

    @Volatile
    private var virtualDisplay: VirtualDisplay? = null

    @Volatile
    private var screenRecordFile: File? = null

    @Volatile
    private var screenRecordStartedMs: Long = 0L

    @Volatile
    private var audioRecorder: MediaRecorder? = null

    @Volatile
    private var audioRecordFile: File? = null

    @Volatile
    private var audioStartedMs: Long = 0L

    @Volatile
    private var audioPaused: Boolean = false

    @Volatile
    private var serviceReadyWaiter: CompletableDeferred<PhoneMediaService?>? = null

    @Volatile
    internal var serviceInstance: PhoneMediaService? = null

    fun hasScreenCaptureConsent(): Boolean = projection != null || projectionConsent != null

    fun isScreenRecording(): Boolean = screenRecorder != null

    fun isAudioRecording(): Boolean = audioRecorder != null

    fun isAudioPaused(): Boolean = audioPaused

    internal fun onServiceBound(service: PhoneMediaService) {
        serviceInstance = service
        serviceReadyWaiter?.complete(service)
        serviceReadyWaiter = null
    }

    internal fun onServiceDestroyed() {
        serviceInstance = null
    }

    /** 仅请求并缓存系统屏幕捕获授权（供设置页「屏幕捕获权限」按钮使用）。 */
    suspend fun requestScreenCaptureAuthorization(context: Context): Boolean {
        if (hasScreenCaptureConsent()) return true
        val consent = PhonePermissionBridge.requestScreenCapture(context) ?: return false
        projectionConsent = consent
        return true
    }

    private suspend fun ensureForegroundService(context: Context, mode: String): PhoneMediaService? {
        val intent = Intent(context, PhoneMediaService::class.java)
            .setAction(PhoneMediaService.ACTION_START)
            .putExtra(PhoneMediaService.EXTRA_MODE, mode)
        val existing = serviceInstance
        if (existing != null) {
            existing.promoteForeground(mode)
            return existing
        }
        val waiter = CompletableDeferred<PhoneMediaService?>()
        serviceReadyWaiter = waiter
        ContextCompat.startForegroundService(context, intent)
        return withTimeoutOrNull(4_000L) { waiter.await() }
    }

    private suspend fun ensureMediaProjection(context: Context, includeMic: Boolean): Result<MediaProjection> {
        projection?.let { return Result.success(it) }
        val consent = projectionConsent ?: PhonePermissionBridge.requestScreenCapture(context)?.also {
            projectionConsent = it
        } ?: return Result.failure(IllegalStateException("用户取消或拒绝了屏幕捕获授权"))

        val mode = if (includeMic) PhoneMediaService.MODE_PROJECTION_AND_MIC else PhoneMediaService.MODE_PROJECTION
        ensureForegroundService(context, mode)
            ?: return Result.failure(IllegalStateException("无法启动屏幕录制前台服务"))

        val mgr = context.getSystemService(MediaProjectionManager::class.java)
            ?: return Result.failure(IllegalStateException("设备不支持 MediaProjection"))

        val mp = runCatching {
            mgr.getMediaProjection(consent.resultCode, consent.data)
        }.getOrNull()
            ?: run {
                // 授权令牌可能已失效，重新申请一次
                projectionConsent = null
                val fresh = PhonePermissionBridge.requestScreenCapture(context)
                    ?: return Result.failure(IllegalStateException("重新请求屏幕捕获授权未通过"))
                projectionConsent = fresh
                ensureForegroundService(context, mode)
                mgr.getMediaProjection(fresh.resultCode, fresh.data)
            }
            ?: return Result.failure(IllegalStateException("无法初始化 MediaProjection 会话"))

        mp.registerCallback(
            object : MediaProjection.Callback() {
                override fun onStop() {
                    projection = null
                    if (screenRecorder != null) {
                        stopScreenRecordingInternal(context)
                    }
                }
            },
            null,
        )
        projection = mp
        return Result.success(mp)
    }

    /** API 26-29 截图回退：使用已有/新请求的 MediaProjection 抽取一帧。 */
    suspend fun captureProjectionFrame(): Bitmap? {
        val ctx = PhoneRuntime.appContext ?: return null
        val mp = ensureMediaProjection(ctx, includeMic = false).getOrNull() ?: return null
        return withContext(Dispatchers.Main) {
            val dm = ctx.resources.displayMetrics
            val w = dm.widthPixels.coerceAtLeast(360)
            val h = dm.heightPixels.coerceAtLeast(640)
            val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
            var vd: VirtualDisplay? = null
            try {
                vd = mp.createVirtualDisplay(
                    "CodeForgeShot",
                    w,
                    h,
                    dm.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    reader.surface,
                    null,
                    null,
                )
                val deadline = SystemClock.uptimeMillis() + 2_500L
                while (SystemClock.uptimeMillis() < deadline) {
                    val img = reader.acquireLatestImage()
                    if (img != null) {
                        return@withContext img.use { image ->
                            val plane = image.planes[0]
                            val rowStride = plane.rowStride
                            val pixelStride = plane.pixelStride
                            val rowPadding = rowStride - pixelStride * w
                            val raw = Bitmap.createBitmap(
                                w + rowPadding / pixelStride,
                                h,
                                Bitmap.Config.ARGB_8888,
                            )
                            raw.copyPixelsFromBuffer(plane.buffer)
                            val cropped = Bitmap.createBitmap(raw, 0, 0, w, h)
                            if (cropped !== raw) raw.recycle()
                            cropped
                        }
                    }
                    kotlinx.coroutines.delay(40L)
                }
                null
            } finally {
                runCatching { vd?.release() }
                runCatching { reader.close() }
            }
        }
    }

    suspend fun startScreenRecording(context: Context, withAudio: Boolean = false): Result<String> {
        if (screenRecorder != null) {
            return Result.failure(IllegalStateException("录屏已在进行中（文件：${screenRecordFile?.name.orEmpty()}）"))
        }
        val micAllowed = withAudio && ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (withAudio && !micAllowed) {
            val perms = PhonePermissionBridge.requestPermissions(context, arrayOf(Manifest.permission.RECORD_AUDIO))
            if (perms[Manifest.permission.RECORD_AUDIO] != true) {
                return Result.failure(IllegalStateException("带声音录屏需要麦克风权限"))
            }
        }

        val mp = ensureMediaProjection(context, includeMic = withAudio).getOrElse { return Result.failure(it) }
        val dm = context.resources.displayMetrics
        // 对齐 16 像素且上限 1280px，保证各机型编码器兼容性
        val rawW = dm.widthPixels.coerceAtLeast(360)
        val rawH = dm.heightPixels.coerceAtLeast(640)
        val scale = minOf(1f, 1280f / maxOf(rawW, rawH))
        val width = (((rawW * scale).toInt() / 16) * 16).coerceAtLeast(320)
        val height = (((rawH * scale).toInt() / 16) * 16).coerceAtLeast(320)

        val dir = PhoneRuntime.artifactDir(context, "recordings")
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val outFile = File(dir, "screen_$stamp.mp4")

        return runCatching {
            val recorder = newMediaRecorder(context).apply {
                if (withAudio) setAudioSource(MediaRecorder.AudioSource.MIC)
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setOutputFile(outFile.absolutePath)
                setVideoSize(width, height)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                if (withAudio) {
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    setAudioEncodingBitRate(96_000)
                    setAudioSamplingRate(44_100)
                }
                setVideoEncodingBitRate(3_500_000)
                setVideoFrameRate(24)
                prepare()
            }
            val vd = mp.createVirtualDisplay(
                "CodeForgeScreenRecord",
                width,
                height,
                dm.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                recorder.surface,
                null,
                null,
            )
            recorder.start()
            synchronized(lock) {
                screenRecorder = recorder
                virtualDisplay = vd
                screenRecordFile = outFile
                screenRecordStartedMs = System.currentTimeMillis()
            }
            PhoneRuntime.toWorkspaceRelativePath(context, outFile)
        }.onFailure {
            runCatching { virtualDisplay?.release() }
            runCatching { screenRecorder?.release() }
            runCatching { outFile.delete() }
            synchronized(lock) {
                virtualDisplay = null
                screenRecorder = null
                screenRecordFile = null
            }
        }
    }

    fun stopScreenRecording(context: Context): Result<PhoneArtifact> =
        stopScreenRecordingInternal(context)

    private fun stopScreenRecordingInternal(context: Context): Result<PhoneArtifact> {
        val recorder: MediaRecorder?
        val vd: VirtualDisplay?
        val outFile: File?
        val startedAt: Long
        synchronized(lock) {
            recorder = screenRecorder
            vd = virtualDisplay
            outFile = screenRecordFile
            startedAt = screenRecordStartedMs
            screenRecorder = null
            virtualDisplay = null
            screenRecordFile = null
            screenRecordStartedMs = 0L
        }
        if (recorder == null || outFile == null) {
            return Result.failure(IllegalStateException("当前没有进行中的录屏"))
        }
        return runCatching {
            runCatching { recorder.stop() }
            runCatching { recorder.reset() }
            runCatching { recorder.release() }
            runCatching { vd?.release() }
            check(outFile.isFile && outFile.length() > 0L) { "录屏文件未生成或为空" }
            val dur = (System.currentTimeMillis() - startedAt).coerceAtLeast(0L)
            val artifact = PhoneArtifactStore.register(
                context = context,
                type = PhoneArtifactType.RECORDING,
                file = outFile,
                durationMs = dur,
            )
            stopServiceIfIdle(context)
            artifact
        }
    }

    suspend fun startAudioRecording(context: Context): Result<String> {
        if (audioRecorder != null) {
            return Result.failure(IllegalStateException("录音已在进行中（文件：${audioRecordFile?.name.orEmpty()}）"))
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            val perms = PhonePermissionBridge.requestPermissions(context, arrayOf(Manifest.permission.RECORD_AUDIO))
            if (perms[Manifest.permission.RECORD_AUDIO] != true) {
                return Result.failure(IllegalStateException("未获得麦克风录音权限"))
            }
        }

        ensureForegroundService(context, PhoneMediaService.MODE_MICROPHONE)
            ?: return Result.failure(IllegalStateException("无法启动录音前台服务"))

        val dir = PhoneRuntime.artifactDir(context, "audio")
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val outFile = File(dir, "audio_$stamp.m4a")

        return runCatching {
            val recorder = newMediaRecorder(context).apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(128_000)
                setAudioSamplingRate(44_100)
                setOutputFile(outFile.absolutePath)
                prepare()
                start()
            }
            synchronized(lock) {
                audioRecorder = recorder
                audioRecordFile = outFile
                audioStartedMs = System.currentTimeMillis()
                audioPaused = false
            }
            PhoneRuntime.toWorkspaceRelativePath(context, outFile)
        }.onFailure {
            runCatching { audioRecorder?.release() }
            runCatching { outFile.delete() }
            synchronized(lock) {
                audioRecorder = null
                audioRecordFile = null
                audioPaused = false
            }
        }
    }

    fun pauseAudioRecording(): Result<String> {
        val rec = audioRecorder ?: return Result.failure(IllegalStateException("当前没有进行中的录音"))
        if (audioPaused) return Result.success("录音已处于暂停状态")
        return runCatching {
            rec.pause()
            audioPaused = true
            "已暂停录音"
        }
    }

    fun resumeAudioRecording(): Result<String> {
        val rec = audioRecorder ?: return Result.failure(IllegalStateException("当前没有进行中的录音"))
        if (!audioPaused) return Result.success("录音正在进行中")
        return runCatching {
            rec.resume()
            audioPaused = false
            "已继续录音"
        }
    }

    fun stopAudioRecording(context: Context): Result<PhoneArtifact> {
        val rec: MediaRecorder?
        val outFile: File?
        val startedAt: Long
        synchronized(lock) {
            rec = audioRecorder
            outFile = audioRecordFile
            startedAt = audioStartedMs
            audioRecorder = null
            audioRecordFile = null
            audioStartedMs = 0L
            audioPaused = false
        }
        if (rec == null || outFile == null) {
            return Result.failure(IllegalStateException("当前没有进行中的录音"))
        }
        return runCatching {
            runCatching { rec.stop() }
            runCatching { rec.reset() }
            runCatching { rec.release() }
            check(outFile.isFile && outFile.length() > 0L) { "录音文件未生成或为空" }
            val dur = (System.currentTimeMillis() - startedAt).coerceAtLeast(0L)
            val artifact = PhoneArtifactStore.register(
                context = context,
                type = PhoneArtifactType.AUDIO,
                file = outFile,
                durationMs = dur,
            )
            stopServiceIfIdle(context)
            artifact
        }
    }

    fun stopAllOnAbort() {
        val ctx = PhoneRuntime.appContext ?: return
        if (screenRecorder != null) runCatching { stopScreenRecordingInternal(ctx) }
        if (audioRecorder != null) runCatching { stopAudioRecording(ctx) }
    }

    private fun stopServiceIfIdle(context: Context) {
        if (screenRecorder == null && audioRecorder == null) {
            runCatching {
                context.stopService(Intent(context, PhoneMediaService::class.java))
            }
        }
    }

    private fun newMediaRecorder(context: Context): MediaRecorder =
        if (Build.VERSION.SDK_INT >= 31) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
}

class PhoneMediaService : Service() {

    companion object {
        const val ACTION_START = "com.hxy.codeforge.PHONE_MEDIA_START"
        const val EXTRA_MODE = "mode"
        const val MODE_PROJECTION = "projection"
        const val MODE_MICROPHONE = "microphone"
        const val MODE_PROJECTION_AND_MIC = "projection_mic"

        private const val CHANNEL_ID = "phone_media_capture"
        private const val NOTIF_ID = 4003
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val mode = intent?.getStringExtra(EXTRA_MODE) ?: MODE_PROJECTION
        promoteForeground(mode)
        PhoneMediaController.onServiceBound(this)
        return START_NOT_STICKY
    }

    fun promoteForeground(mode: String) {
        val mgr = getSystemService(NotificationManager::class.java)
        if (mgr != null && mgr.getNotificationChannel(CHANNEL_ID) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "手机操控媒体采集", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val text = when (mode) {
            MODE_MICROPHONE -> "正在使用麦克风进行录音"
            MODE_PROJECTION_AND_MIC -> "正在录制屏幕与音频"
            else -> "屏幕捕获服务运行中"
        }
        val notif = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        })
            .setContentTitle("CodeForge 手机操控")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()

        val fgType = resolveForegroundServiceType(mode)
        runCatching {
            ServiceCompat.startForeground(this, NOTIF_ID, notif, fgType)
        }.onFailure {
            ServiceCompat.startForeground(this, NOTIF_ID, notif, 0)
        }
    }

    private fun resolveForegroundServiceType(mode: String): Int {
        if (Build.VERSION.SDK_INT < 29) return 0
        val hasMicType = Build.VERSION.SDK_INT >= 30
        return when (mode) {
            MODE_MICROPHONE -> if (hasMicType) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
            MODE_PROJECTION_AND_MIC -> {
                var t = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                if (hasMicType) t = t or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                t
            }
            else -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        }
    }

    override fun onDestroy() {
        PhoneMediaController.onServiceDestroyed()
        super.onDestroy()
    }
}
