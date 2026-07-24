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

**SEMUA FASA SELESAI** (23 Julai 2026). Backfill dijalankan pada SP0002:
8 alokasi diproses, 15 baris baharu, jumlah kekal RM1,440.59. Laporan
kutipan ikut produk berkira dengan ledger (1,180.59 line-level + 170.00
peringkat dokumen = 1,350.59 alokasi resit bulan semasa).

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

---

## Nota susulan: guna-advance (skop ADR 0009)

Disahkan 23 Julai 2026 — laluan "guna advance" **belum wujud** dalam sistem
baharu (grep applyAdvance/useDeposit/consumeAdvance = kosong), walaupun
advance memang tercipta (payment id 74: bayar 500, alokasi 300, advance 200).

### Tingkah laku legacy (bukti: penyata akaun M0318, 2025)

- Resit "Advanced Payment" RM880 -> baki terus jadi **(880.00)** iaitu kredit.
- Setiap bulan invois RM80 dijana -> advance di-knock automatik masa jana bil.
- Baki susut: (800) -> (720) -> ... -> 0.00 pada Disember. Setiap invois
  bertanda lunas.

Rumusan: **advance di-knock pada masa penjanaan bil**, dan **baki akaun boleh
negatif** apabila advance melebihi invois.

### Implikasi untuk ADR 0009

1. Baki mesti boleh negatif. Query semasa (`SUM(invois) - SUM(alokasi)`)
   tidak tolak advance yang belum dipakai — perlu diputuskan.
2. **Lubang invariant sisi kredit**: `AllocationGuard` hanya semak sisi debit
   (invois tidak boleh over-allocate). Tiada semakan bahawa jumlah alokasi
   dari satu resit <= nilai resit. Bila advance di-knock guna resit asal
   sebagai `credit_document_id`, lubang ini jadi berisiko. Perlu invariant
   sisi kredit.
3. Alokasi yang dicipta oleh laluan guna-advance mesti **peringkat line**
   juga — sebab itu line-level (ADR 0006) didahulukan.

### Semakan kod legacy (InvoiceGenerator.java, 1958 baris)

Soalan: adakah legacy menyemak baki/advance semasa menjana bil?
**Jawapan: TIDAK.**

- `TXN_CODE_ADVANCED_PYMT = "M2000"` diisytihar (baris 146) tetapi **tidak
  pernah digunakan** dalam generator.
- Tiada kod alokasi langsung (grep allocat/knockoff/settle/paid_amt = kosong).
- Mekanisme sebenar: **baki berjalan** — `accBal.add(totalAmt)` menokok
  `balance_amount` yang di-cache.

Kerana baki sudah negatif (advance -880), setiap invois +80 menolaknya ke
arah sifar. Kesan pada penyata kelihatan seperti knock-off, tetapi **tiada
rekod alokasi** — legacy tidak dapat menjawab "invois ini dibayar oleh resit
yang mana".

Ini punca yang sama dengan CASE-001: bergantung pada baki cache tanpa rekod
alokasi menyebabkan hanyutan yang tidak dapat direkonsiliasi.

**Kesimpulan:** ambil **tingkah laku** legacy (baki boleh negatif, advance
susut setiap bulan) tetapi **bukan mekanismenya**. Sistem baharu mesti guna
alokasi eksplisit yang boleh dijejaki — selaras prinsip "ledger sumber
kebenaran".

---

## Hutang ditemui semasa P4 — DIBETULKAN dalam P4.5

`ModularityTests.verifiesModuleStructure` GAGAL pada kod sedia ada:
`AllocationGuard` mengunci dokumen menggunakan `em.find(FinancialDocument
.class, ...)` — kelas DALAMAN modul document, sedangkan Modulith hanya
membenarkan `document::api`.

Disahkan hutang sedia ada: ujian juga gagal tanpa perubahan P4 (disemak
dengan git stash), dan AllocationGuard tidak diusik sesi ini.

### Penyelesaian (P4.5, commit 8d66cf2)

Asalnya dicatat sebagai "betulkan berasingan". Diubah kerana dua sebab:
P5 (wiring laluan duit) memerlukan regresi penuh HIJAU sebagai jaring
keselamatan — masuk fasa berisiko tanpa jaring tidak berbaloi. Dan
membiarkan lubang sempadan dalam sistem yang dibina khusus untuk
menghapuskan lubang legacy adalah bertentangan dengan tujuannya.

`document::api` += `lockAndGetTotal(documentId)` — kunci pesimis dan
bacaan jumlah dalam SATU operasi (memisahkannya memusnahkan tujuan kunci).
Dilaksana melalui repository `@Lock(PESSIMISTIC_WRITE)` mengikut gaya modul
document. Kunci kekal di modul pemilik data.

`AllocationGuard` kekal pemilik invariant (pengajaran CASE-001: satu tempat
dikongsi, bukan disalin) — hanya cara mendapat kunci yang berubah.

**Status: SELESAI.** ModularityTests hijau, AllocationGuardTest 3/3.
