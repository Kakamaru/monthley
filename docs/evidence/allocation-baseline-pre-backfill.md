# Baseline alokasi sebelum backfill line-level

Dirakam: 23 Julai 2026, selepas migration V30 (kolum ditambah, semua NULL).
Tujuan: bukti bahawa backfill (P5) tidak mengubah jumlah.

## Jumlah per dokumen (ACTIVE)

| debit_document_id | jumlah | baris |
|---|---|---|
| 316 | 20.00 | 1 |
| 317 | 50.00 | 1 |
| 373 | 950.59 | 3 |
| 461 | 250.00 | 3 |
| 554 | 50.00 | 1 |
| 593 | 100.00 | 1 |
| 655 | 20.00 | 1 |

## Keseluruhan

- Baris ACTIVE: **11**
- Jumlah ACTIVE: **RM1,440.59**
- `debit_document_line_id`: 11/11 NULL

## Selepas backfill, yang MESTI kekal

- Jumlah per `debit_document_id` — **sama persis** (jadual di atas)
- Jumlah keseluruhan **RM1,440.59**

Yang DIJANGKA berubah:
- Bilangan baris **bertambah** (satu alokasi dokumen pecah kepada beberapa
  baris line)
- `debit_document_line_id` terisi untuk alokasi ke INVOICE

Nota: dokumen 373 dan 461 menerima **bayaran berbilang** (3 baris setiap
satu) — kes ini menguji backfill dengan betul, bukan sekadar satu-alokasi-
satu-line.

## Query pengesahan (jalankan selepas backfill)

```sql
SELECT debit_document_id, SUM(amount) jum, COUNT(*) baris
FROM fi_allocation WHERE status='ACTIVE'
GROUP BY debit_document_id ORDER BY debit_document_id;
```
