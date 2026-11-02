package vn.aztecanalyzer

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

class CaptureOverlayService : Service() {
    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val ACTION_SIGNAL_ALERT = "vn.aztecanalyzer.SIGNAL_ALERT"
        const val EXTRA_SIGNAL_MESSAGE = "signal_message"
        const val ACTION_OCR_RESULT = "vn.aztecanalyzer.OCR_RESULT"
        const val EXTRA_BET = "bet"
        const val EXTRA_PAYOUT = "payout"
        const val EXTRA_SCATTERS = "scatters"
        const val EXTRA_BONUS = "bonus"
        const val EXTRA_RAW = "raw"
        private const val CHANNEL_CAPTURE = "aztec_capture"
        private const val CHANNEL_SIGNAL = "aztec_signal"
        private const val NOTIFICATION_CAPTURE_ID = 2202
        private const val NOTIFICATION_SIGNAL_ID = 2303
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var windowManager: WindowManager? = null
    private var overlay: TextView? = null
    @Volatile private var capturePending = false
    @Volatile private var busy = false
    private var captureDir: File? = null
    private val handlerThread = HandlerThread("AztecCapture").apply { start() }
    private val handler by lazy { Handler(handlerThread.looper) }
    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensity = 0

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        getSharedPreferences("aztec_capture_state", Context.MODE_PRIVATE).edit().putBoolean("active", true).apply()
        startForeground(NOTIFICATION_CAPTURE_ID, captureNotification())
        captureDir = File(cacheDir, "aztec_owned_captures").apply {
            mkdirs()
            listFiles()?.filter { it.isFile && it.name.startsWith("AZTEC_OCR_") && it.name.endsWith(".jpg") }
                ?.forEach { it.delete() }
        }
        val metrics = resources.displayMetrics
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi
        showOverlay()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_SIGNAL_ALERT) {
            val message = intent.getStringExtra(EXTRA_SIGNAL_MESSAGE) ?: "Có tín hiệu thống kê mới."
            showSignalNotification(message)
            showSignalOnOverlay()
            return START_NOT_STICKY
        }

        if (projection == null && intent != null) {
            val code = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            val data = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(EXTRA_RESULT_DATA)
            }
            if (code != Activity.RESULT_OK || data == null) {
                stopSelf()
                return START_NOT_STICKY
            }
            try {
                val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                projection = manager.getMediaProjection(code, data)
                projection?.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() { stopSelf() }
                }, handler)
                setupVirtualDisplay()
            } catch (_: Exception) {
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun setupVirtualDisplay() {
        val reader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 3)
        imageReader = reader
        reader.setOnImageAvailableListener({ source ->
            val image = try { source.acquireLatestImage() } catch (_: Exception) { null }
            if (image == null) return@setOnImageAvailableListener
            if (!capturePending || busy) {
                image.close()
                return@setOnImageAvailableListener
            }
            capturePending = false
            busy = true
            handler.post { processImage(image) }
        }, handler)
        virtualDisplay = projection?.createVirtualDisplay(
            "AztecAnalyzerCapture",
            screenWidth, screenHeight, screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface, null, handler
        )
    }

    private fun showOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val button = TextView(this).apply {
            text = "AZ\n📷"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(23, 74, 53))
            setPadding(18, 14, 18, 14)
            contentDescription = "Chụp màn hình và phân tích Aztec"
            setOnClickListener {
                if (busy) {
                    android.widget.Toast.makeText(this@CaptureOverlayService, "Đang đọc ảnh, vui lòng đợi.", android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    capturePending = true
                    android.widget.Toast.makeText(this@CaptureOverlayService, "Đã yêu cầu chụp màn hình.", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            setOnLongClickListener {
                stopSelf()
                true
            }
        }
        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                   else WindowManager.LayoutParams.TYPE_PHONE
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 12
            y = screenHeight / 3
        }
        try {
            windowManager?.addView(button, params)
            overlay = button
        } catch (_: Exception) { stopSelf() }
    }

    private fun processImage(image: Image) {
        var file: File? = null
        try {
            val bitmap = imageToBitmap(image)
            image.close()
            val ownedFile = File(captureDir, "AZTEC_OCR_${System.currentTimeMillis()}_${UUID.randomUUID()}.jpg")
            file = ownedFile
            FileOutputStream(ownedFile).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            bitmap.recycle()
            vibrateSuccess()
            android.widget.Toast.makeText(this, "Đã chụp màn hình, đang nhận diện OCR.", android.widget.Toast.LENGTH_SHORT).show()

            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                .process(InputImage.fromFilePath(this, android.net.Uri.fromFile(ownedFile)))
                .addOnSuccessListener { result ->
                    val parsed = parseFields(result.text)
                    getSharedPreferences("aztec_pending", Context.MODE_PRIVATE).edit()
                        .putBoolean("has_result", true)
                        .putString(EXTRA_BET, parsed.first ?: "")
                        .putString(EXTRA_PAYOUT, parsed.second ?: "")
                        .putInt(EXTRA_SCATTERS, parsed.third ?: 0)
                        .putBoolean(EXTRA_BONUS, Regex("FREE\\s*SPINS|BONUS", RegexOption.IGNORE_CASE).containsMatchIn(result.text))
                        .putString(EXTRA_RAW, result.text.take(2500))
                        .apply()

                    val out = Intent(ACTION_OCR_RESULT).apply {
                        setPackage(packageName)
                        putExtra(EXTRA_BET, parsed.first ?: "")
                        putExtra(EXTRA_PAYOUT, parsed.second ?: "")
                        putExtra(EXTRA_SCATTERS, parsed.third ?: 0)
                        putExtra(EXTRA_BONUS, Regex("FREE\\s*SPINS|BONUS", RegexOption.IGNORE_CASE).containsMatchIn(result.text))
                        putExtra(EXTRA_RAW, result.text.take(2500))
                    }
                    sendBroadcast(out)
                    android.widget.Toast.makeText(this, "OCR thành công – đã điền dữ liệu.", android.widget.Toast.LENGTH_SHORT).show()
                }
                .addOnFailureListener {
                    sendBroadcast(Intent(ACTION_OCR_RESULT).setPackage(packageName)
                        .putExtra(EXTRA_RAW, "OCR_ERROR: ${it.localizedMessage}"))
                }
                .addOnCompleteListener {
                    try {
                        if (ownedFile.canonicalPath.startsWith(captureDir!!.canonicalPath + File.separator)) {
                            ownedFile.delete()
                        }
                    } catch (_: Exception) {}
                    busy = false
                }
        } catch (e: Exception) {
            try { image.close() } catch (_: Exception) {}
            file?.delete()
            busy = false
            sendBroadcast(Intent(ACTION_OCR_RESULT).setPackage(packageName)
                .putExtra(EXTRA_RAW, "CAPTURE_ERROR: ${e.localizedMessage}"))
        }
    }

    @Suppress("DEPRECATION")
    private fun vibrateSuccess() {
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                val manager = getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager
                manager.defaultVibrator.vibrate(VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
                if (Build.VERSION.SDK_INT >= 26) vibrator.vibrate(VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE))
                else vibrator.vibrate(120)
            }
        } catch (_: Exception) {}
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * screenWidth
        val paddedWidth = screenWidth + rowPadding / pixelStride
        val padded = Bitmap.createBitmap(paddedWidth, screenHeight, Bitmap.Config.ARGB_8888)
        padded.copyPixelsFromBuffer(buffer)
        val cropped = Bitmap.createBitmap(padded, 0, 0, screenWidth, screenHeight)
        padded.recycle()
        return cropped
    }

    private fun parseFields(raw: String): Triple<String?, String?, Int?> {
        val lines = raw.lines()
        val amountRegex = Regex("""([0-9]+[.,][0-9]{1,2})""")
        fun amount(s: String?): String? = s?.let { amountRegex.find(it)?.groupValues?.get(1)?.replace(',', '.') }
        val payoutLine = lines.firstOrNull { it.contains("TỔNG THẮNG", true) }
            ?: lines.firstOrNull { Regex("\\bWIN\\b", RegexOption.IGNORE_CASE).containsMatchIn(it) }
        val betLine = lines.firstOrNull { it.contains("BET", true) || it.contains("CƯỢC", true) }
        val decimals = amountRegex.findAll(raw).map { it.groupValues[1].replace(',', '.') }.toList()
        val payout = amount(payoutLine)
        val bet = amount(betLine) ?: decimals.lastOrNull { it != payout }
        val scatter = Regex("SCATTER", RegexOption.IGNORE_CASE).findAll(raw).count().takeIf { it > 0 }
        return Triple(bet, payout, scatter)
    }

    private fun captureNotification(): Notification {
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        val pending = PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_CAPTURE)
            .setContentTitle("Aztec Analyzer đang hoạt động")
            .setContentText("Chạm nút AZ để chụp. Giữ nút AZ để kết thúc.")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(pending)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(NotificationChannel(CHANNEL_CAPTURE, "Aztec capture", NotificationManager.IMPORTANCE_LOW))
            manager.createNotificationChannel(NotificationChannel(CHANNEL_SIGNAL, "Aztec statistical signals", NotificationManager.IMPORTANCE_HIGH))
        }
    }

    private fun showSignalNotification(message: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        val pending = PendingIntent.getActivity(this, 1, launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, CHANNEL_SIGNAL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Aztec Analyzer · Tín hiệu thống kê")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$message Đây là mô tả lịch sử, không phải dự đoán kết quả RNG."))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_SIGNAL_ID, notification)
    }

    private fun showSignalOnOverlay() {
        val view = overlay ?: return
        view.text = "AZ\n⚠"
        handler.postDelayed({
            if (overlay === view) view.text = "AZ\n📷"
        }, 5000)
    }

    override fun onDestroy() {
        try { overlay?.let { windowManager?.removeView(it) } } catch (_: Exception) {}
        virtualDisplay?.release()
        imageReader?.close()
        projection?.stop()
        getSharedPreferences("aztec_capture_state", Context.MODE_PRIVATE).edit().putBoolean("active", false).apply()
        handlerThread.quitSafely()
        super.onDestroy()
    }
}
