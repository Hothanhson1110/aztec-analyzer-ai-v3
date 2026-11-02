package vn.aztecanalyzer

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var db: SpinDb
    private lateinit var summary: TextView
    private lateinit var riskAdvice: TextView
    private lateinit var analysisText: TextView
    private lateinit var evaluationText: TextView
    private lateinit var historyCount: TextView
    private lateinit var historyContainer: LinearLayout
    private lateinit var betInput: EditText
    private lateinit var payoutInput: EditText
    private lateinit var scatterInput: EditText
    private lateinit var noteInput: EditText
    private lateinit var bonusCheck: MaterialCheckBox
    private lateinit var searchInput: EditText
    private var receiverRegistered = false
    private var pendingSource = "MANUAL"
    private val projectionRequestCode = 7312
    private val signalChannel = "aztec_signal"
    private val signalNotificationId = 2303

    private val imagePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) runOcr(uri)
    }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) Toast.makeText(this, "Bạn chưa cấp quyền thông báo; tín hiệu vẫn được lưu trong lịch sử phân tích.", Toast.LENGTH_LONG).show()
    }

    private val ocrReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == CaptureOverlayService.ACTION_OCR_RESULT) applyOcrResult(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        db = SpinDb(this)

        summary = findViewById(R.id.summary)
        riskAdvice = findViewById(R.id.riskAdvice)
        analysisText = findViewById(R.id.analysisText)
        evaluationText = findViewById(R.id.evaluationText)
        historyCount = findViewById(R.id.historyCount)
        historyContainer = findViewById(R.id.historyContainer)
        betInput = findViewById(R.id.betInput)
        payoutInput = findViewById(R.id.payoutInput)
        scatterInput = findViewById(R.id.scatterInput)
        noteInput = findViewById(R.id.noteInput)
        bonusCheck = findViewById(R.id.bonusCheck)
        searchInput = findViewById(R.id.searchInput)

        findViewById<MaterialButton>(R.id.ocrButton).setOnClickListener { imagePicker.launch("image/*") }
        findViewById<MaterialButton>(R.id.overlayStartButton).setOnClickListener { startOverlayFlow() }
        findViewById<MaterialButton>(R.id.overlayStopButton).setOnClickListener {
            stopService(Intent(this, CaptureOverlayService::class.java))
        }
        findViewById<MaterialButton>(R.id.saveButton).setOnClickListener { saveSpin() }
        findViewById<MaterialButton>(R.id.analyzeButton).setOnClickListener { analyzeHistory() }
        findViewById<MaterialButton>(R.id.searchButton).setOnClickListener { refreshHistory() }
        findViewById<MaterialButton>(R.id.exportButton).setOnClickListener { exportCsv() }
        findViewById<MaterialButton>(R.id.resetButton).setOnClickListener { confirmClear() }

        createSignalChannel()
        requestNotificationPermissionIfNeeded()
        refreshAll()
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(CaptureOverlayService.ACTION_OCR_RESULT)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(ocrReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(ocrReceiver, filter)
        }
        receiverRegistered = true

        val prefs = getSharedPreferences("aztec_pending", MODE_PRIVATE)
        if (prefs.getBoolean("has_result", false)) {
            val pending = Intent(CaptureOverlayService.ACTION_OCR_RESULT).apply {
                putExtra(CaptureOverlayService.EXTRA_BET, prefs.getString(CaptureOverlayService.EXTRA_BET, ""))
                putExtra(CaptureOverlayService.EXTRA_PAYOUT, prefs.getString(CaptureOverlayService.EXTRA_PAYOUT, ""))
                putExtra(CaptureOverlayService.EXTRA_SCATTERS, prefs.getInt(CaptureOverlayService.EXTRA_SCATTERS, -1))
                putExtra(CaptureOverlayService.EXTRA_RAW, prefs.getString(CaptureOverlayService.EXTRA_RAW, ""))
            }
            applyOcrResult(pending)
            prefs.edit().clear().apply()
        }
    }

    override fun onStop() {
        if (receiverRegistered) {
            try { unregisterReceiver(ocrReceiver) } catch (_: Exception) {}
            receiverRegistered = false
        }
        super.onStop()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun startOverlayFlow() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            Toast.makeText(this, "Hãy cấp quyền hiển thị trên ứng dụng khác rồi quay lại bật nút chụp.", Toast.LENGTH_LONG).show()
            return
        }
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION")
        startActivityForResult(manager.createScreenCaptureIntent(), projectionRequestCode)
    }

    @Deprecated("MediaProjection consent result compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != projectionRequestCode) return
        if (resultCode == Activity.RESULT_OK && data != null) {
            val serviceIntent = Intent(this, CaptureOverlayService::class.java).apply {
                putExtra(CaptureOverlayService.EXTRA_RESULT_CODE, resultCode)
                putExtra(CaptureOverlayService.EXTRA_RESULT_DATA, data)
            }
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(serviceIntent) else startService(serviceIntent)
        } else {
            Toast.makeText(this, "Bạn chưa cấp quyền chụp màn hình.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveSpin() {
        if (db.count() >= SpinDb.MAX_SAMPLES) {
            Toast.makeText(this, "Database đã đạt giới hạn ${SpinDb.MAX_SAMPLES} mẫu. Hãy xuất CSV trước khi thêm dữ liệu mới.", Toast.LENGTH_LONG).show()
            return
        }
        val bet = parseMoney(betInput.text.toString())
        val payout = parseMoney(payoutInput.text.toString())
        val scatters = scatterInput.text.toString().toIntOrNull() ?: 0
        if (bet == null || bet <= 0 || payout == null || payout < 0 || scatters < 0) {
            Toast.makeText(this, "Vui lòng nhập cược > 0, trả thưởng ≥ 0 và Scatter hợp lệ.", Toast.LENGTH_LONG).show()
            return
        }
        db.add(Spin(bet = bet, payout = payout, scatters = scatters, bonus = bonusCheck.isChecked,
            note = noteInput.text.toString().trim(), source = pendingSource))
        betInput.text.clear()
        payoutInput.text.clear()
        scatterInput.text.clear()
        noteInput.text.clear()
        bonusCheck.isChecked = false
        pendingSource = "MANUAL"
        refreshAll()
        Toast.makeText(this, "Đã lưu lượt chơi.", Toast.LENGTH_SHORT).show()
    }

    private fun parseMoney(raw: String): Long? {
        return try {
            BigDecimal(raw.trim().replace(",", "."))
                .movePointRight(2)
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact()
        } catch (_: Exception) { null }
    }

    private fun money(value: Long): String =
        BigDecimal.valueOf(value, 2).stripTrailingZeros().toPlainString()

    private fun refreshAll() {
        refreshDashboard()
        refreshHistory()
        refreshEvaluation()
    }

    private fun refreshDashboard() {
        val rows = db.all(limit = SpinDb.MAX_SAMPLES)
        val totalBet = rows.sumOf { it.bet }
        val totalPayout = rows.sumOf { it.payout }
        val net = totalPayout - totalBet
        val paidSpins = rows.count { it.payout > 0 }
        val bonuses = rows.count { it.bonus }
        val scatterTotal = rows.sumOf { it.scatters }
        val rtp = if (totalBet > 0) totalPayout.toDouble() / totalBet * 100.0 else 0.0
        val bonusRate = if (rows.isNotEmpty()) bonuses.toDouble() / rows.size * 100.0 else 0.0

        summary.text = buildString {
            append("Số mẫu: ${rows.size} / ${SpinDb.MAX_SAMPLES}\n")
            append("Tổng cược: ${money(totalBet)} đơn vị\n")
            append("Tổng trả thưởng: ${money(totalPayout)} đơn vị\n")
            append("RTP thực nghiệm: ${"%.2f".format(Locale.US, rtp)}%\n")
            append("Lãi / lỗ: ${if (net >= 0) "+" else ""}${money(net)} đơn vị\n")
            append("Lượt có trả thưởng: $paidSpins\n")
            append("Lượt Free Spins: $bonuses (${ "%.2f".format(Locale.US, bonusRate) }%)\n")
            append("Tổng Scatter ghi nhận: $scatterTotal")
        }
        riskAdvice.text = when {
            rows.isEmpty() -> "Chưa có dữ liệu. Hãy nhập thủ công hoặc dùng OCR."
            net < 0 -> "Kết quả đang âm theo lịch sử. Không tăng cược để gỡ; nên tuân thủ giới hạn ngân sách đã đặt."
            rows.size < 30 -> "Mẫu còn nhỏ; tỷ lệ quan sát có thể dao động mạnh."
            else -> "Dashboard mô tả lịch sử đã nhập, không dự báo kết quả ngẫu nhiên tiếp theo."
        }
    }

    private fun analyzeHistory() {
        val window = findViewById<EditText>(R.id.windowInput).text.toString().toIntOrNull() ?: 100
        val delta = findViewById<EditText>(R.id.deltaInput).text.toString().trim().replace(",", ".").toDoubleOrNull() ?: 5.0
        if (window !in 10..SpinDb.MAX_SAMPLES || delta !in 0.0..100.0) {
            Toast.makeText(this, "Cửa sổ phải từ 10 đến 10.000; chênh lệch từ 0 đến 100 điểm %.", Toast.LENGTH_LONG).show()
            return
        }
        val snapshot = db.createSnapshot(window, delta)
        if (snapshot == null) {
            analysisText.text = "Chưa có dữ liệu để phân tích."
            return
        }
        val latest = db.snapshots(1).firstOrNull()
        analysisText.text = if (latest == null) "Không thể đọc snapshot." else buildString {
            append("Snapshot #${latest.id} · ${latest.createdAt}\n")
            append("Cửa sổ: ${latest.windowSize} lượt · Mẫu dùng: ${latest.sampleCount}\n")
            append("Free Spins trong cửa sổ: ${latest.bonusCount} (${percent(latest.observedRate)})\n")
            append("Mức nền trước cửa sổ: ${percent(latest.baselineRate)}\n")
            append("Ngưỡng chênh lệch: ${"%.1f".format(Locale.US, latest.thresholdDelta)} điểm %\n")
            append("Tín hiệu: ${if (latest.signal) "CÓ" else "CHƯA CÓ"}\n")
            append(latest.reason)
            append("\n\nSnapshot đang chờ lượt thực tế tiếp theo để đánh giá.")
        }
        if (snapshot.signal) notifySignal(snapshot.reason)
        refreshEvaluation()
    }

    private fun percent(rate: Double): String = "${"%.2f".format(Locale.US, rate * 100.0)}%"

    private fun refreshEvaluation() {
        val m = db.confusionMetrics()
        val accuracy = if (m.evaluated > 0) (m.tp + m.tn).toDouble() / m.evaluated * 100.0 else 0.0
        val precision = if (m.tp + m.fp > 0) m.tp.toDouble() / (m.tp + m.fp) * 100.0 else 0.0
        val recall = if (m.tp + m.fn > 0) m.tp.toDouble() / (m.tp + m.fn) * 100.0 else 0.0
        val snapshots = db.snapshots(8)
        evaluationText.text = buildString {
            append("ĐÁNH GIÁ TÍN HIỆU (chỉ snapshot đã có lượt kết quả)\n")
            append("Đã đánh giá: ${m.evaluated}\n")
            append("TP: ${m.tp} · FP: ${m.fp} · TN: ${m.tn} · FN: ${m.fn}\n")
            append("Accuracy: ${"%.1f".format(Locale.US, accuracy)}% · Precision: ${"%.1f".format(Locale.US, precision)}% · Recall: ${"%.1f".format(Locale.US, recall)}%\n")
            append("TP: tín hiệu + có Free Spins; FP: tín hiệu + không có; TN: không tín hiệu + không có; FN: không tín hiệu + có.\n")
            append("\nSnapshot gần đây (tham số và lý do):\n")
            if (snapshots.isEmpty()) append("Chưa có snapshot.")
            snapshots.forEach {
                append("#${it.id} · window=${it.windowSize} · n=${it.sampleCount} · bonus=${it.bonusCount}\n")
                append("Tỷ lệ ${percent(it.observedRate)} / nền ${percent(it.baselineRate)} · ")
                append(if (it.signal) "SIGNAL" else "NO SIGNAL")
                append(if (it.evaluated) " · Kết quả ${it.outcome}" else " · Chờ lượt kế tiếp")
                append("\nLý do: ${it.reason}\n")
            }
        }
    }

    private fun notifySignal(reason: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Có tín hiệu thống kê. Hãy bật quyền thông báo để nhận cảnh báo nổi.", Toast.LENGTH_LONG).show()
        } else {
            val launch = packageManager.getLaunchIntentForPackage(packageName)
            val pending = android.app.PendingIntent.getActivity(
                this, 1, launch,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            val notification = NotificationCompat.Builder(this, signalChannel)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Aztec Analyzer · Tín hiệu thống kê")
                .setContentText("Tần suất lịch sử gần đây vượt mức nền.")
                .setStyle(NotificationCompat.BigTextStyle().bigText("$reason Đây không phải dự đoán lượt quay kế tiếp."))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pending)
                .build()
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(signalNotificationId, notification)
        }

        val active = getSharedPreferences("aztec_capture_state", MODE_PRIVATE).getBoolean("active", false)
        if (active) {
            startService(Intent(this, CaptureOverlayService::class.java).apply {
                action = CaptureOverlayService.ACTION_SIGNAL_ALERT
                putExtra(CaptureOverlayService.EXTRA_SIGNAL_MESSAGE, reason)
            })
        }
    }

    private fun createSignalChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(signalChannel, "Aztec statistical signals", NotificationManager.IMPORTANCE_HIGH)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    private fun refreshHistory() {
        val query = searchInput.text.toString().trim()
        val rows = db.all(query, 100)
        historyContainer.removeAllViews()
        historyCount.text = "Hiển thị ${rows.size} lượt gần nhất (tối đa 100 trong danh sách) · Tổng database ${db.count()}"
        if (rows.isEmpty()) {
            addHistoryText("Không có bản ghi phù hợp.")
            return
        }
        rows.forEach { spin ->
            val card = MaterialCardView(this).apply {
                radius = dp(10).toFloat()
                cardElevation = dp(1).toFloat()
                setCardBackgroundColor(0xFFFFFFFF.toInt())
                useCompatPadding = true
            }
            val column = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
            }
            val detail = TextView(this).apply {
                text = buildString {
                    append("#${spin.id} · ${spin.createdAt}\n")
                    append("Cược ${money(spin.bet)} → Trả ${money(spin.payout)} · Scatter ${spin.scatters}\n")
                    append("Free Spins: ${if (spin.bonus) "Có" else "Không"} · ${spin.source}")
                    if (spin.note.isNotBlank()) append("\nGhi chú: ${spin.note}")
                }
                textSize = 13f
                setTextColor(0xFF25352C.toInt())
            }
            val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val edit = MaterialButton(this).apply {
                text = "Sửa"
                textSize = 11f
                setOnClickListener { showEditDialog(spin) }
            }
            val delete = MaterialButton(this).apply {
                text = "Xóa"
                textSize = 11f
                setOnClickListener { confirmDelete(spin) }
            }
            actions.addView(edit, LinearParams(0, dp(42), 1f))
            actions.addView(delete, LinearParams(0, dp(42), 1f))
            column.addView(detail)
            column.addView(actions)
            card.addView(column)
            historyContainer.addView(card, LinearParams(-1, -2).apply { bottomMargin = dp(8) })
        }
    }

    private fun addHistoryText(message: String) {
        historyContainer.addView(TextView(this).apply {
            text = message
            textSize = 13f
            setPadding(dp(8), dp(12), dp(8), dp(12))
        })
    }

    private fun showEditDialog(spin: Spin) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val bet = dialogInput("Tiền cược", money(spin.bet), InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        val payout = dialogInput("Tiền trả thưởng", money(spin.payout), InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        val scatters = dialogInput("Số Scatter", spin.scatters.toString(), InputType.TYPE_CLASS_NUMBER)
        val note = dialogInput("Ghi chú", spin.note, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
        val bonus = MaterialCheckBox(this).apply {
            text = "Đã kích hoạt Free Spins"
            isChecked = spin.bonus
        }
        box.addView(bet); box.addView(payout); box.addView(scatters); box.addView(note); box.addView(bonus)
        AlertDialog.Builder(this)
            .setTitle("Sửa lượt #${spin.id}")
            .setView(box)
            .setNegativeButton("Hủy", null)
            .setPositiveButton("Lưu") { _, _ ->
                val b = parseMoney(bet.text.toString())
                val p = parseMoney(payout.text.toString())
                val s = scatters.text.toString().toIntOrNull()
                if (b == null || b <= 0 || p == null || p < 0 || s == null || s < 0) {
                    Toast.makeText(this, "Dữ liệu sửa không hợp lệ.", Toast.LENGTH_LONG).show()
                } else {
                    db.update(spin.copy(bet = b, payout = p, scatters = s, bonus = bonus.isChecked, note = note.text.toString().trim()))
                    refreshAll()
                    Toast.makeText(this, "Đã cập nhật lượt #${spin.id}.", Toast.LENGTH_SHORT).show()
                }
            }.show()
    }

    private fun dialogInput(hintText: String, value: String, type: Int): EditText =
        EditText(this).apply {
            hint = hintText
            setText(value)
            inputType = type
            textSize = 14f
        }

    private fun confirmDelete(spin: Spin) {
        AlertDialog.Builder(this)
            .setTitle("Xóa lượt #${spin.id}?")
            .setMessage("Bản ghi sẽ bị xóa khỏi database trên thiết bị.")
            .setNegativeButton("Hủy", null)
            .setPositiveButton("Xóa") { _, _ ->
                db.delete(spin.id)
                refreshAll()
                Toast.makeText(this, "Đã xóa lượt.", Toast.LENGTH_SHORT).show()
            }.show()
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setTitle("Xóa toàn bộ database?")
            .setMessage("Lịch sử lượt chơi và snapshot đánh giá sẽ bị xóa. Hãy xuất CSV trước nếu cần lưu bản sao.")
            .setNegativeButton("Hủy", null)
            .setPositiveButton("Xóa tất cả") { _, _ ->
                db.clear()
                analysisText.text = "Chưa có phân tích."
                refreshAll()
                Toast.makeText(this, "Đã xóa database.", Toast.LENGTH_SHORT).show()
            }.show()
    }

    private fun exportCsv() {
        val csv = db.exportCsv()
        pendingCsv = csv
        createCsvLauncher.launch("aztec_analyzer_ai_v3_history.csv")
    }

    private var pendingCsv = ""
    private val createCsvLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) {
            contentResolver.openOutputStream(uri)?.use { it.write(pendingCsv.toByteArray(Charsets.UTF_8)) }
            Toast.makeText(this, "Đã xuất CSV.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun runOcr(uri: Uri) {
        try {
            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                .process(InputImage.fromFilePath(this, uri))
                .addOnSuccessListener { result ->
                    val parsed = parseScreenshot(result.text)
                    pendingSource = "OCR_IMAGE"
                    parsed.bet?.let { betInput.setText(it) }
                    parsed.payout?.let { payoutInput.setText(it) }
                    parsed.scatters?.let { scatterInput.setText(it.toString()) }
                    Toast.makeText(this, "OCR hoàn tất. Hãy kiểm tra trường đã điền rồi bấm Lưu lượt.", Toast.LENGTH_LONG).show()
                }
                .addOnFailureListener {
                    Toast.makeText(this, "OCR thất bại: ${it.localizedMessage}", Toast.LENGTH_LONG).show()
                }
        } catch (e: Exception) {
            Toast.makeText(this, "Không mở được ảnh: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
        }
    }

    private data class OcrFields(val bet: String?, val payout: String?, val scatters: Int?)

    private fun parseScreenshot(raw: String): OcrFields {
        val lines = raw.lines()
        val amountRegex = Regex("""([0-9]+[.,][0-9]{1,2})""")
        fun amount(line: String?): String? = line?.let {
            amountRegex.find(it)?.groupValues?.get(1)?.replace(',', '.')
        }
        val payoutLine = lines.firstOrNull { it.contains("TỔNG THẮNG", true) }
            ?: lines.firstOrNull { Regex("""\bWIN\b""", RegexOption.IGNORE_CASE).containsMatchIn(it) }
        val betLine = lines.firstOrNull { it.contains("BET", true) || it.contains("CƯỢC", true) }
        val payout = amount(payoutLine)
        val decimals = amountRegex.findAll(raw).map { it.groupValues[1].replace(',', '.') }.toList()
        val bet = amount(betLine) ?: decimals.lastOrNull { it != payout }
        val scatterCount = Regex("SCATTER", RegexOption.IGNORE_CASE).findAll(raw).count().takeIf { it > 0 }
        return OcrFields(bet, payout, scatterCount)
    }

    private fun applyOcrResult(intent: Intent) {
        val raw = intent.getStringExtra(CaptureOverlayService.EXTRA_RAW).orEmpty()
        if (raw.startsWith("OCR_ERROR:") || raw.startsWith("CAPTURE_ERROR:")) {
            Toast.makeText(this, raw, Toast.LENGTH_LONG).show()
            return
        }
        val bet = intent.getStringExtra(CaptureOverlayService.EXTRA_BET).orEmpty()
        val payout = intent.getStringExtra(CaptureOverlayService.EXTRA_PAYOUT).orEmpty()
        val scatters = intent.getIntExtra(CaptureOverlayService.EXTRA_SCATTERS, -1)
        pendingSource = "OCR_OVERLAY"
        if (bet.isNotBlank()) betInput.setText(bet)
        if (payout.isNotBlank()) payoutInput.setText(payout)
        if (scatters >= 0) scatterInput.setText(scatters.toString())
        getSharedPreferences("aztec_pending", MODE_PRIVATE).edit().clear().apply()
        Toast.makeText(this, "OCR thành công. Kiểm tra dữ liệu trước khi lưu.", Toast.LENGTH_LONG).show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun LinearParams(width: Int, height: Int, weight: Float = 0f): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(width, height, weight)
}
