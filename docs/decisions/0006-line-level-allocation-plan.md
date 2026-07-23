# ADR 0006 — Alokasi Peringkat Line (rancangan pelaksanaan)

- **Status:** Dicadang — menunggu kelulusan sebelum kod
- **Tarikh:** 23 Julai 2026
- **Menggantikan:** ADR 0005 (yang hanya mencatat isu)

---

## Konteks

`fi_allocation` mengait bayaran pada peringkat DOKUMEN sahaja. Sistem tidak
tahu bayaran untuk item/produk mana. Legacy sudah ada laporan "kutipan ikut
produk" — jadi keupayaan ini memang diperlukan, bukan tambahan hiasan.

### Fakta disahkan (bukan andaian)

| Semakan | Hasil |
|---|---|
| Doc total vs SUM(line) | **Padan 100%** — tiada baki tergantung |
| Line per invois | 19 invois = 1 line; 3 invois = 12-13 line |
| Line ada `product_id`, `amount`, `tax_amount`, `period_start` | Ya |
| `period_start` NULL | Tiada (57/57 terisi) |
| Line `active=0` | Tiada |
| DEBIT_NOTE / CREDIT_NOTE / RECEIPT ada line | **TIADA** (0 line) |
| Alokasi ACTIVE sedia ada | 11 baris (RM1,440.59) |

### Cerapan kritikal

Jika kita simpan **satu baris alokasi per line** (bukan satu per dokumen),
maka `SUM(amount) GROUP BY debit_document_id` **kekal sama**. Ini bermakna:

- `AllocationGuard` (invariant + kunci pesimis) — **tidak berubah**
- Semua query baki sedia ada — **tidak berubah**
- Pembatalan resit (`WHERE credit_document_id`) — **tidak berubah**

Radius kesan jadi kecil kerana jumlah per dokumen dikekalkan.

---

## Keputusan

### 1. Skema
**NULL dibenarkan** kerana DEBIT_NOTE tiada line — alokasi debit note kekal
peringkat dokumen. Bukan semua alokasi boleh (atau perlu) ada line.

### 2. FIFO dua peringkat

Peringkat 1 (sedia ada): merentas invois — tertua dahulu.
Peringkat 2 (**baharu**): dalam setiap invois — merentas line.

Susunan line: `period_start ASC`, NULL di belakang, pecah seri ikut `id ASC`.
Rasional: tempoh tertua dilunaskan dahulu — konsisten dengan FIFO invois.

Contoh: bayar RM150 ke INV001 (Yuran Jan RM100, Parking Jan RM50)
menghasilkan **dua baris**: (line=Yuran, 100) + (line=Parking, 50).
Jumlah dokumen tetap RM150 — baki tidak berubah.

Baki line = `(line.amount + line.tax_amount) - SUM(alokasi ACTIVE line itu)`.

### 3. Invariant

- Peringkat dokumen: **kekal** (`AllocationGuard.checkAndLock`) — tidak diusik.
- Peringkat line (**baharu**): `SUM(alokasi ACTIVE line) <= line total`.

Dua lapis: dokumen ialah sempadan kunci (aggregate root), line ialah semakan
ketepatan. Konsisten dengan pengajaran CASE-001 (invariant di sempadan).

### 4. Backfill alokasi sedia ada

Setiap alokasi lama (line_id NULL) dipecah ikut FIFO line yang **sama**:

- Baris asal dikemas kini → menunjuk line pertama (jejak audit kekal)
- Baki dimasukkan sebagai baris tambahan untuk line berikutnya

**Invariant backfill:** `SUM(amount) per debit_document_id` mesti **sama
sebelum & selepas**. Disahkan dengan query perbandingan.

Logik pecahan ditulis sebagai komponen boleh guna semula supaya **migrasi
data legacy nanti pakai logik yang sama**.

Alokasi ke dokumen tanpa line (DEBIT_NOTE) kekal `line_id = NULL`.

### 5. Fasa pelaksanaan

| Fasa | Kandungan | Guard |
|---|---|---|
| P1 | Migration V30 + medan entity | ddl-auto=validate |
| P2 | FIFO line dalam allocator (logik murni) | ujian unit |
| P3 | PaymentService cipta alokasi per line | ujian integrasi |
| P4 | Invariant peringkat line | ujian sengaja langgar |
| P5 | Backfill + sahkan jumlah tak berubah | query perbandingan |
| P6 | Laporan kutipan ikut produk (guna line_id) | sahkan lawan data |

Setiap fasa: `mvn test` hijau + commit berasingan. Boleh berhenti di
mana-mana fasa tanpa merosakkan sistem.

---

## Risiko & mitigasi

| Risiko | Mitigasi |
|---|---|
| Jumlah baki berubah | Invariant SUM per dokumen; query perbandingan sebelum/selepas |
| Over-allocation line | Invariant peringkat line + guard dokumen sedia ada |
| Dokumen tanpa line | line_id NULL dibenarkan |
| Regresi laluan bayaran | Ujian sedia ada (22) mesti kekal hijau |
| Pembundaran | Doc total == SUM(line) telah disahkan padan |

---

## Alternatif ditolak

- **Agih proportional masa lapor** — anggaran, bukan fakta. Legacy sudah ada
  laporan sebenar; anggaran satu kemunduran.
- **Kekal peringkat dokumen** — masalah asal tidak selesai.
- **Jadual alokasi berasingan untuk line** — dua sumber kebenaran, risiko
  drift (pengajaran CASE-001: satu sumber kebenaran).

## Rujukan
- `0005-line-level-allocation.md` (catatan isu asal)
- `0004-manual-payment-idempotency.md`
- `evidence/CASE-001-balance-mismatch-A0124.md`
- `domain/accounting-invariants.md`
