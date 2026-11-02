# Changelog

## 3.0.0
- New dashboard with empirical RTP, net result and Free Spins frequency.
- SQLite schema v3 with migration from the previous `aztec_v2.db` schema.
- Spin CRUD, search and CSV export; database is bounded to 10,000 records.
- Statistical rolling-window snapshots with configurable threshold.
- Snapshot evaluation against the next recorded result using TP / FP / TN / FN.
- Accuracy, precision, recall and snapshot reason/parameter reporting.
- OCR auto-fill without an intermediate confirmation dialog.
- Vibration immediately after successful floating screenshot capture.
- Heads-up statistical signal notification and floating overlay indicator.
