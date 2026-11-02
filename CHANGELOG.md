# Changelog

## 3.0.0
- New dashboard with empirical RTP, net result and Free Spins frequency.
- SQLite schema v3 with migration from the previous `aztec_v2.db` schema.
- Spin CRUD, search and CSV export; database is bounded to 10,000 records.
- Statistical rolling-window snapshots with configurable threshold.
- Snapshot evaluation against the next recorded result using TP / FP / TN / FN.
- Accuracy, precision, recall and snapshot reason/parameter reporting.
- OCR automatically saves valid records from gallery images and floating screenshots without user confirmation.
- Vibration immediately after successful floating screenshot capture.
- Heads-up statistical signal notification and floating overlay indicator.

- OCR: tự động lưu bản ghi vào SQLite ngay sau khi nhận diện hợp lệ từ ảnh thư viện hoặc overlay; bỏ bước xác nhận thủ công, có kiểm tra trường bắt buộc và chống lưu trùng ngắn hạn.


## 3.1.0
- Thay giao diện sáng cũ bằng phong cách premium dark/neon cyan-violet.
- Nâng cấp tín hiệu thống kê với Jeffreys smoothing, kiểm định z, ngưỡng cỡ mẫu và ngưỡng hiệu ứng; ghi chẩn đoán vào snapshot.
- Giữ nguyên package, tên database và schema để tiếp tục dùng dữ liệu 3.0.
