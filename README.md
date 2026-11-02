# Aztec Analyzer AI 3.0

Android native application built with Kotlin, XML layouts, SQLite and Google ML Kit Text Recognition.

## Version 3.0 features

- Dashboard: sample count, total bet, total payout, empirical RTP, net result, bonus frequency and scatter count.
- SQLite database with add, list, edit, delete, search and CSV export.
- Database cap: 10,000 spin records. Export data before replacing old records.
- Keeps the existing `aztec_v2.db` filename and upgrades its SQLite schema to version 3. Existing 2.0 spin history is preserved while the new predictions table and note/source fields are added.
- Statistical snapshots: compare a recent rolling window against the preceding historical baseline.
- Snapshot evaluation: the next actual recorded spin evaluates pending snapshots as TP / FP / TN / FN.
- Metrics: accuracy, precision and recall for evaluated snapshots.
- OCR from image picker and floating screenshot button. OCR automatically saves a record to SQLite as soon as required fields are recognized; no review or confirmation is required. Invalid/incomplete OCR results are rejected, and duplicate payloads are suppressed briefly.
- Haptic feedback after successful floating OCR.
- High-priority notification when the configured historical-rate signal threshold is crossed.
- Export spin history to CSV.

## Statistical interpretation and safety

The signal is a descriptive comparison of a recent historical window with the preceding historical baseline. It is not a prediction of the next spin, and it cannot identify or defeat an independent RNG. The TP/FP/TN/FN metrics measure how a historical signal correlates with the next recorded outcome; they do not establish causality or guarantee future performance.

The default signal requires:
- At least 30 observations in the recent window (or the smaller configured window).
- At least 10 observations before that window to calculate a separate baseline.
- Recent Free Spins frequency at least the configured number of percentage points above the baseline.

A snapshot is evaluated only when a subsequent real spin is saved. If multiple snapshots are awaiting evaluation, each is compared with that same next saved spin. For clean experiments, create one snapshot per evaluation point and avoid repeatedly generating snapshots on the same unchanged dataset.

## OCR and Android permissions

- Overlay permission is used only for the floating capture button.
- MediaProjection requires explicit Android system consent.
- Android 13+ notification permission is requested for signal notifications.
- Vibration permission is used for a short haptic confirmation immediately after a screenshot is captured successfully.
- The app does not use Accessibility, auto-click, control the game, or bypass protected-screen capture restrictions.
- Temporary screenshots are stored only in the app's private cache folder and the exact file created for OCR is deleted after processing.

## Build

Requirements:
- JDK 17
- Android SDK Platform 35
- Gradle 8.7 (GitHub Actions workflow provisions it)
- Android Gradle Plugin 8.6.1
- Kotlin 2.0.21

GitHub Actions:
1. Create a new empty repository, for example `aztec-analyzer-ai-v3`.
2. Upload/commit this source tree to its `main` branch.
3. Open Actions -> Build Aztec Analyzer AI 3.0 APK -> Run workflow.
4. Download artifact `aztec-analyzer-ai-v3-debug-apk`.

## Commit from Termux

Download this ZIP to Android's Download folder, then run:

```bash
pkg update && pkg upgrade -y
pkg install git unzip -y
termux-setup-storage

mkdir -p ~/projects/aztec-analyzer-ai-v3
cd ~/projects/aztec-analyzer-ai-v3
unzip ~/storage/downloads/aztec_analyzer_ai_v3_source.zip

# Initialize a new repository
cd aztec_analyzer_ai_v3
git init
git add .
git commit -m "Initial Aztec Analyzer AI 3.0"
git branch -M main
git remote add origin https://github.com/YOUR_USERNAME/aztec-analyzer-ai-v3.git
git push -u origin main
```

Replace `YOUR_USERNAME` and repository name with the new GitHub repository details. If GitHub asks for a password over HTTPS, use a Personal Access Token; do not paste tokens into shared messages or source files.

## Data format

Bet and payout values are stored as integer hundredths of the game's displayed unit. For example, `4.25` is stored as `425`. No conversion to VND is assumed.


## 3.1 — Neon UI & statistical model

- Giao diện dark premium với nền midnight, card xanh navy, điểm nhấn neon cyan/violet, viền card và thanh trạng thái đồng bộ.
- Bộ lọc tín hiệu dùng Jeffreys smoothing (Beta(0.5, 0.5)), so sánh hai tỷ lệ gần đây và baseline, yêu cầu cỡ mẫu tối thiểu, mức tăng thực tế và z-score một phía ≥ 1.96.
- Snapshot lưu tham số và chẩn đoán posterior/z-score trong trường `reason`; không cần thay đổi schema SQLite.
- Đây là phát hiện biến động thống kê trong dữ liệu lịch sử, không thể dự báo hoặc khẳng định kết quả RNG độc lập tiếp theo.
