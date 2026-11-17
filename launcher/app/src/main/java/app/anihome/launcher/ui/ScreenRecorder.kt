package app.anihome.launcher.ui

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.widget.Toast
import app.anihome.launcher.R

/**
 * Screen recording from the Control Center: Android asks for permission to capture the screen,
 * a short countdown runs, then video is recorded to Movies/AniHome until you tap the control
 * again or Stop in the notification. Video only; nothing leaves the device.
 */
internal object ScreenRecorder {
    @Volatile var recording = false
        internal set
    fun start(context: Context) {
        context.startActivity(Intent(context, ScreenRecordActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    fun stop(context: Context) {
        context.startService(Intent(context, ScreenRecordService::class.java).setAction(ScreenRecordService.ACTION_STOP))
    }
}

/** Invisible step that asks Android for screen capture (and, on Android 13+, to show the stop notification). */
class ScreenRecordActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        else askCapture()
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        askCapture() // Recording works either way; without it the stop button lives only in AniHome's panel.
    }
    private fun askCapture() {
        val manager = getSystemService(MediaProjectionManager::class.java) ?: return finish()
        @Suppress("DEPRECATION") startActivityForResult(manager.createScreenCaptureIntent(), 2)
    }
    @Deprecated("Activity result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 2 && resultCode == RESULT_OK && data != null) {
            val start = Intent(this, ScreenRecordService::class.java).putExtra(ScreenRecordService.EXTRA_CODE, resultCode).putExtra(ScreenRecordService.EXTRA_DATA, data)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(start) else startService(start)
        }
        finish()
    }
}

class ScreenRecordService : Service() {
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var recorder: MediaRecorder? = null
    private var output: Uri? = null
    private var file: ParcelFileDescriptor? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { finish(true); return START_NOT_STICKY }
        val data = if (Build.VERSION.SDK_INT >= 33) intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java) else @Suppress("DEPRECATION") intent?.getParcelableExtra(EXTRA_DATA)
        if (data == null || ScreenRecorder.recording) { stopSelf(); return START_NOT_STICKY }
        val notification = notification("Starting in 3…")
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION) else startForeground(ID, notification)
        val code = intent!!.getIntExtra(EXTRA_CODE, 0)
        // A short countdown so the panel and the permission dialog are gone from the first frame.
        handler.postDelayed({ begin(code, data) }, 3000)
        return START_NOT_STICKY
    }

    private fun begin(code: Int, data: Intent) {
        val ok = runCatching {
            val manager = getSystemService(MediaProjectionManager::class.java)!!
            val mp = manager.getMediaProjection(code, data) ?: error("No projection")
            projection = mp
            mp.registerCallback(object : MediaProjection.Callback() { override fun onStop() { handler.post { finish(true) } } }, handler)
            val metrics = resources.displayMetrics
            // Up to 1080 px on the short side, even dimensions (encoders need them).
            val scale = minOf(1f, 1080f / minOf(metrics.widthPixels, metrics.heightPixels))
            val width = ((metrics.widthPixels * scale).toInt() / 2) * 2; val height = ((metrics.heightPixels * scale).toInt() / 2) * 2
            val name = "AniHome-" + java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date()) + ".mp4"
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name); put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                if (Build.VERSION.SDK_INT >= 29) { put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/AniHome"); put(MediaStore.Video.Media.IS_PENDING, 1) }
            }
            val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: error("No storage")
            output = uri
            val fd = contentResolver.openFileDescriptor(uri, "w") ?: error("No file")
            file = fd
            val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()
            r.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            r.setVideoSize(width, height); r.setVideoFrameRate(30); r.setVideoEncodingBitRate(8_000_000)
            r.setOutputFile(fd.fileDescriptor)
            r.prepare()
            recorder = r
            display = mp.createVirtualDisplay("AniHome recording", width, height, metrics.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, handler)
            r.start()
            ScreenRecorder.recording = true
            getSystemService(NotificationManager::class.java)?.notify(ID, notification("Recording your screen"))
        }.isSuccess
        if (!ok) { Toast.makeText(this, "Screen recording couldn't start", Toast.LENGTH_LONG).show(); finish(false) }
    }

    private fun finish(save: Boolean) {
        val wasRecording = ScreenRecorder.recording
        ScreenRecorder.recording = false
        handler.removeCallbacksAndMessages(null)
        val stopped = runCatching { recorder?.stop() }.isSuccess
        runCatching { recorder?.release() }; recorder = null
        runCatching { display?.release() }; display = null
        runCatching { projection?.stop() }; projection = null
        runCatching { file?.close() }; file = null
        output?.let { uri ->
            if (save && wasRecording && stopped) {
                if (Build.VERSION.SDK_INT >= 29) runCatching { contentResolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null) }
                Toast.makeText(this, "Screen recording saved to Movies/AniHome", Toast.LENGTH_LONG).show()
            } else runCatching { contentResolver.delete(uri, null, null) }
        }
        output = null
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true)
        stopSelf()
    }

    private fun notification(text: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && manager?.getNotificationChannel(CHANNEL) == null)
            manager?.createNotificationChannel(NotificationChannel(CHANNEL, "Screen recording", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 0, Intent(this, ScreenRecordService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(this)
        return builder.setSmallIcon(R.drawable.ic_cc_record).setContentTitle("AniHome screen recording").setContentText(text).setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build()).build()
    }

    override fun onDestroy() { if (ScreenRecorder.recording) finish(true); super.onDestroy() }

    companion object {
        const val ACTION_STOP = "app.anihome.launcher.record.STOP"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        private const val CHANNEL = "screen_recording"
        private const val ID = 4242
    }
}
