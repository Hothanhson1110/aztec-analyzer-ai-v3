package vn.aztecanalyzer

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Spin(
    val id: Long = 0,
    val createdAt: String = "",
    val bet: Long,
    val payout: Long,
    val scatters: Int,
    val bonus: Boolean,
    val note: String = "",
    val source: String = "MANUAL"
)

data class AnalysisSnapshot(
    val id: Long = 0,
    val createdAt: String,
    val windowSize: Int,
    val sampleCount: Int,
    val bonusCount: Int,
    val observedRate: Double,
    val baselineRate: Double,
    val thresholdDelta: Double,
    val signal: Boolean,
    val reason: String,
    val evaluated: Boolean = false,
    val actualBonus: Boolean? = null,
    val outcome: String? = null
)

data class ConfusionMetrics(
    val tp: Int, val fp: Int, val tn: Int, val fn: Int,
    val evaluated: Int
)

class SpinDb(context: Context) : SQLiteOpenHelper(context, "aztec_v2.db", null, 3) {
    companion object {
        const val MAX_SAMPLES = 10_000
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE spins(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                created_at TEXT NOT NULL,
                bet INTEGER NOT NULL,
                payout INTEGER NOT NULL,
                scatters INTEGER NOT NULL,
                bonus INTEGER NOT NULL,
                note TEXT NOT NULL DEFAULT '',
                source TEXT NOT NULL DEFAULT 'MANUAL'
            )"""
        )
        createPredictionsTable(db)
    }

    private fun createPredictionsTable(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS predictions(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                created_at TEXT NOT NULL,
                window_size INTEGER NOT NULL,
                sample_count INTEGER NOT NULL,
                bonus_count INTEGER NOT NULL,
                observed_rate REAL NOT NULL,
                baseline_rate REAL NOT NULL,
                threshold_delta REAL NOT NULL,
                signal INTEGER NOT NULL,
                reason TEXT NOT NULL,
                evaluated INTEGER NOT NULL DEFAULT 0,
                actual_bonus INTEGER,
                outcome TEXT
            )"""
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createPredictionsTable(db)
        if (oldVersion < 3) {
            addColumnIfMissing(db, "spins", "note", "TEXT NOT NULL DEFAULT ''")
            addColumnIfMissing(db, "spins", "source", "TEXT NOT NULL DEFAULT 'MANUAL'")
        }
    }

    private fun addColumnIfMissing(db: SQLiteDatabase, table: String, column: String, definition: String) {
        val cursor = db.rawQuery("PRAGMA table_info($table)", null)
        val exists = cursor.use {
            var found = false
            while (it.moveToNext()) if (it.getString(it.getColumnIndexOrThrow("name")) == column) found = true
            found
        }
        if (!exists) db.execSQL("ALTER TABLE $table ADD COLUMN $column $definition")
    }

    fun add(spin: Spin): Long {
        val db = writableDatabase
        val now = timestamp()
        val id = db.insertOrThrow("spins", null, spinValues(spin, now))
        // Keep the working dataset bounded to 10,000 rows.
        db.execSQL(
            "DELETE FROM spins WHERE id NOT IN (SELECT id FROM spins ORDER BY id DESC LIMIT ?)",
            arrayOf(MAX_SAMPLES.toString())
        )
        evaluatePending(spin.bonus)
        return id
    }

    fun update(spin: Spin): Boolean {
        val values = spinValues(spin, spin.createdAt.ifBlank { timestamp() })
        return writableDatabase.update("spins", values, "id=?", arrayOf(spin.id.toString())) > 0
    }

    fun delete(id: Long): Boolean =
        writableDatabase.delete("spins", "id=?", arrayOf(id.toString())) > 0

    fun clear() {
        writableDatabase.beginTransaction()
        try {
            writableDatabase.delete("spins", null, null)
            writableDatabase.delete("predictions", null, null)
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    fun count(): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM spins", null).use {
            return if (it.moveToFirst()) it.getInt(0) else 0
        }
    }

    fun all(query: String = "", limit: Int = MAX_SAMPLES): List<Spin> {
        val rows = mutableListOf<Spin>()
        val where = if (query.isBlank()) "" else "WHERE note LIKE ? OR source LIKE ?"
        val args = if (query.isBlank()) emptyArray() else arrayOf("%$query%", "%$query%")
        readableDatabase.rawQuery(
            """SELECT id,created_at,bet,payout,scatters,bonus,note,source
               FROM spins $where ORDER BY id DESC LIMIT ?""",
            args + limit.coerceIn(1, MAX_SAMPLES).toString()
        ).use { c ->
            while (c.moveToNext()) {
                rows.add(
                    Spin(
                        id = c.getLong(0),
                        createdAt = c.getString(1),
                        bet = c.getLong(2),
                        payout = c.getLong(3),
                        scatters = c.getInt(4),
                        bonus = c.getInt(5) == 1,
                        note = c.getString(6) ?: "",
                        source = c.getString(7) ?: "MANUAL"
                    )
                )
            }
        }
        return rows
    }

    fun createSnapshot(windowSize: Int, thresholdDelta: Double): AnalysisSnapshot? {
        val chronological = all(limit = MAX_SAMPLES).asReversed()
        if (chronological.isEmpty()) return null

        val window = windowSize.coerceIn(10, MAX_SAMPLES)
        val recent = chronological.takeLast(window)
        val previous = chronological.dropLast(recent.size)
        val recentBonus = recent.count { it.bonus }
        val baselineBonus = previous.count { it.bonus }
        val nRecent = recent.size
        val nBase = previous.size
        val observedRate = if (nRecent > 0) recentBonus.toDouble() / nRecent else 0.0
        val baselineRate = if (nBase > 0) baselineBonus.toDouble() / nBase else 0.0

        // Jeffreys smoothing prevents a zero/100% estimate from tiny samples.
        val recentPosterior = (recentBonus + 0.5) / (nRecent + 1.0)
        val basePosterior = (baselineBonus + 0.5) / (nBase + 1.0)
        val pooled = if (nRecent + nBase > 0)
            (recentBonus + baselineBonus).toDouble() / (nRecent + nBase) else 0.0
        val standardError = if (nRecent > 0 && nBase > 0)
            kotlin.math.sqrt((pooled * (1.0 - pooled)) * (1.0 / nRecent + 1.0 / nBase)) else 0.0
        val zScore = if (standardError > 0.0) (observedRate - baselineRate) / standardError else 0.0
        val deltaObserved = (recentPosterior - basePosterior) * 100.0
        val minRecent = maxOf(30, minOf(50, window / 2))
        val hasBaseline = nBase >= 30
        val enoughRecent = nRecent >= minRecent
        val effectGate = deltaObserved >= thresholdDelta
        val significanceGate = zScore >= 1.96 // one-sided evidence threshold, approx. 95%
        val signal = hasBaseline && enoughRecent && effectGate && significanceGate

        val reason = when {
            !hasBaseline -> "Chưa đủ baseline độc lập: cần ít nhất 30 lượt trước cửa sổ; hiện có $nBase."
            !enoughRecent -> "Cần tối thiểu $minRecent lượt trong cửa sổ; hiện có $nRecent."
            signal -> "Tín hiệu thống kê: chênh lệch đã làm trơn ${"%.2f".format(Locale.US, deltaObserved)} điểm %, z=${"%.2f".format(Locale.US, zScore)} (đạt ngưỡng 1,96)."
            !effectGate -> "Mức tăng đã làm trơn ${"%.2f".format(Locale.US, deltaObserved)} điểm %, thấp hơn ngưỡng ${"%.1f".format(Locale.US, thresholdDelta)} điểm %."
            else -> "Chênh lệch chưa đủ bằng chứng thống kê (z=${"%.2f".format(Locale.US, zScore)}; cần ≥ 1,96)."
        }

        val diagnostic = " Posterior gần đây=${"%.2f".format(Locale.US, recentPosterior * 100)}%; baseline=${"%.2f".format(Locale.US, basePosterior * 100)}%; z=${"%.2f".format(Locale.US, zScore)}."
        val snapshot = AnalysisSnapshot(
            createdAt = timestamp(), windowSize = window, sampleCount = nRecent,
            bonusCount = recentBonus, observedRate = observedRate, baselineRate = baselineRate,
            thresholdDelta = thresholdDelta, signal = signal, reason = reason + diagnostic
        )
        val values = ContentValues().apply {
            put("created_at", snapshot.createdAt); put("window_size", snapshot.windowSize)
            put("sample_count", snapshot.sampleCount); put("bonus_count", snapshot.bonusCount)
            put("observed_rate", snapshot.observedRate); put("baseline_rate", snapshot.baselineRate)
            put("threshold_delta", snapshot.thresholdDelta); put("signal", if (snapshot.signal) 1 else 0)
            put("reason", snapshot.reason)
        }
        val id = writableDatabase.insertOrThrow("predictions", null, values)
        return snapshot.copy(id = id)
    }

    private fun evaluatePending(actualBonus: Boolean) {
        val db = writableDatabase
        val cursor = db.rawQuery(
            "SELECT id,signal FROM predictions WHERE evaluated=0 ORDER BY id ASC",
            null
        )
        val pending = mutableListOf<Pair<Long, Boolean>>()
        cursor.use {
            while (it.moveToNext()) pending.add(it.getLong(0) to (it.getInt(1) == 1))
        }
        pending.forEach { (id, signal) ->
            val outcome = when {
                signal && actualBonus -> "TP"
                signal && !actualBonus -> "FP"
                !signal && !actualBonus -> "TN"
                else -> "FN"
            }
            val values = ContentValues().apply {
                put("evaluated", 1)
                put("actual_bonus", if (actualBonus) 1 else 0)
                put("outcome", outcome)
            }
            db.update("predictions", values, "id=?", arrayOf(id.toString()))
        }
    }

    fun snapshots(limit: Int = 30): List<AnalysisSnapshot> {
        val rows = mutableListOf<AnalysisSnapshot>()
        readableDatabase.rawQuery(
            """SELECT id,created_at,window_size,sample_count,bonus_count,
               observed_rate,baseline_rate,threshold_delta,signal,reason,evaluated,actual_bonus,outcome
               FROM predictions ORDER BY id DESC LIMIT ?""",
            arrayOf(limit.coerceIn(1, 500).toString())
        ).use { c ->
            while (c.moveToNext()) {
                rows.add(
                    AnalysisSnapshot(
                        id = c.getLong(0),
                        createdAt = c.getString(1),
                        windowSize = c.getInt(2),
                        sampleCount = c.getInt(3),
                        bonusCount = c.getInt(4),
                        observedRate = c.getDouble(5),
                        baselineRate = c.getDouble(6),
                        thresholdDelta = c.getDouble(7),
                        signal = c.getInt(8) == 1,
                        reason = c.getString(9),
                        evaluated = c.getInt(10) == 1,
                        actualBonus = if (c.isNull(11)) null else c.getInt(11) == 1,
                        outcome = c.getString(12)
                    )
                )
            }
        }
        return rows
    }

    fun confusionMetrics(): ConfusionMetrics {
        var tp = 0; var fp = 0; var tn = 0; var fn = 0
        readableDatabase.rawQuery(
            "SELECT outcome,COUNT(*) FROM predictions WHERE evaluated=1 GROUP BY outcome",
            null
        ).use { c ->
            while (c.moveToNext()) {
                when (c.getString(0)) {
                    "TP" -> tp = c.getInt(1)
                    "FP" -> fp = c.getInt(1)
                    "TN" -> tn = c.getInt(1)
                    "FN" -> fn = c.getInt(1)
                }
            }
        }
        return ConfusionMetrics(tp, fp, tn, fn, tp + fp + tn + fn)
    }

    fun exportCsv(): String {
        val rows = all(limit = MAX_SAMPLES).asReversed()
        return buildString {
            append("id,created_at,bet_x100,payout_x100,scatters,free_spins,source,note\n")
            rows.forEach { s ->
                append("${s.id},${csv(s.createdAt)},${s.bet},${s.payout},${s.scatters},${if (s.bonus) 1 else 0},${csv(s.source)},${csv(s.note)}\n")
            }
        }
    }

    private fun spinValues(spin: Spin, createdAt: String): ContentValues =
        ContentValues().apply {
            put("created_at", createdAt)
            put("bet", spin.bet)
            put("payout", spin.payout)
            put("scatters", spin.scatters)
            put("bonus", if (spin.bonus) 1 else 0)
            put("note", spin.note)
            put("source", spin.source)
        }

    private fun timestamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

    private fun csv(value: String): String = "\"" + value.replace("\"", "\"\"") + "\""
}
