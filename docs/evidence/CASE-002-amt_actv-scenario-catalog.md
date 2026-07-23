# CASE-002 — Katalog Senario: `amt_actv` vs `bal_amt` (SP 000Y Sri Mutiara)

| | |
|---|---|
| **Status** | Dalam progres — audit akaun demi akaun, bina katalog senario |
| **Tujuan** | Kenal pasti setiap corak berbeza, fahami punca, tetapkan cara fix. Setiap corak jadi template untuk SP lain. |
| **Kaitan** | CASE-001 (A0124 duplicate) — sudah selesai. Ni isu berkaitan tapi berbeza. |
| **Kritikal kerana** | `amt_actv` memandu pengiraan **penalti** (per-produk, hanya produk yang enable). `amt_actv` rosak → penalti tersilap, berulang setiap kitaran. |

---

## Definisi Asas (disahkan dokumen ke-15)

Selepas siasatan panjang, definisi sebenar yang **konsisten**:

```
bal_amt (mon_sp_acc)  =  SUM(amt_actv) untuk doc_type='INV', sts_code='A' SAHAJA
```

**Bukan** termasuk:
- `amt_actv` pada RCP — itu **advance payment / kredit** (bayaran lebih belum di-agih), disimpan berasingan
- `amt_actv` pada J00/J01/J02

**Cara `bal_amt` dikemaskini:** incremental (running), bukan dikira semula. Setiap event (invois masuk, resit masuk) *sepatutnya* laraskan `bal_amt` dan `amt_actv` melalui jalan kod tersendiri. Bila sesetengah event (adjustment, pembatalan) terlepas kemaskini, sumber menyimpang.

### Empat representasi baki yang boleh menyimpang
1. `bal_amt` (mon_sp_acc) — running, incremental
2. `amt_actv` per-invois (mon_sp_fi_doc) — memandu penalti
3. `mon_fi_doc_link` — **tak lengkap** (ada bayaran tanpa link)
4. running-balance dalam `mon_fi_doc_txn` (`dt_acc_amt`/`cr_acc_amt`)

> Tiada satu pun boleh mengira semula yang lain dengan tepat, kerana tiada satu pun rekod lengkap. Ini teras masalah — dan hujah utama untuk reka bentuk ledger-tunggal dalam Monthley baru.

### Nota penting tentang `prod_id`
`prod_id` dan `prod_descr` **tiada** dalam `mon_sp_fi_doc`. Ia dalam `mon_fi_doc_txn`. Untuk tahu produk sesuatu invois (dan sama ada ia enable penalti), **mesti join** `mon_sp_fi_doc.doc_id = mon_fi_doc_txn.doc_id`.

Produk 000Y yang dilihat setakat ini:
- `000Y003` MAINTENANCE FEE B
- `000Y004` SINKING FUND B
- `000Y005` INSURANCE
- `000Y006` WATER
- (advance payment: `prod_descr='Advanced Payment'`, `txn_code='M2000'`, `prod_id` kosong)

---

## Query Diagnostik Induk

Gunakan ni untuk saring akaun 000Y yang benar-benar tak tally (guna definisi INV-sahaja):

```sql
SELECT a.acc_id, a.ref_no, a.bal_amt,
       SUM(CASE WHEN d.doc_type='INV' THEN d.amt_actv ELSE 0 END) AS inv_outstanding,
       SUM(CASE WHEN d.doc_type IN ('RCP','J00') THEN d.amt_actv ELSE 0 END) AS advance_credit,
       a.bal_amt - SUM(CASE WHEN d.doc_type='INV' THEN d.amt_actv ELSE 0 END) AS diff
FROM   mon_sp_acc a
LEFT JOIN mon_sp_fi_doc d ON d.mbr_acc_id=a.acc_id AND d.sts_code='A'
WHERE  a.sp_code='000Y' AND a.sts_code='A' AND a.acc_id != '000Y00001'
GROUP  BY a.acc_id, a.ref_no, a.bal_amt
HAVING ABS(a.bal_amt - SUM(CASE WHEN d.doc_type='INV' THEN d.amt_actv ELSE 0 END)) > 0.005
ORDER  BY ABS(a.bal_amt - SUM(CASE WHEN d.doc_type='INV' THEN d.amt_actv ELSE 0 END)) DESC;
```

**Ujian kawalan:** 000Y00025 (A0124, sudah fixed) dan 000Y00895 mesti **TIDAK** keluar.

Audit penuh satu akaun:
```sql
-- Semua doc
SELECT doc_id, doc_type, ref_no, doc_dt, amt_, amt_actv, sts_code, cancel_dt
FROM   mon_sp_fi_doc WHERE mbr_acc_id=:acc ORDER BY doc_dt, doc_id;

-- Semua link
SELECT l.link_id, l.src_doc_id, l.tgt_doc_id, l.amt_, l.sts_code,
       s.doc_type src_type, s.ref_no src_ref, t.ref_no tgt_ref
FROM   mon_fi_doc_link l
JOIN   mon_sp_fi_doc s ON s.doc_id=l.src_doc_id
JOIN   mon_sp_fi_doc t ON t.doc_id=l.tgt_doc_id
WHERE  t.mbr_acc_id=:acc OR s.mbr_acc_id=:acc ORDER BY l.create_dt;

-- Produk per doc (untuk penalti)
SELECT d.doc_id, d.doc_type, d.amt_actv, x.prod_id, x.prod_descr
FROM   mon_sp_fi_doc d
JOIN   mon_fi_doc_txn x ON x.doc_id=d.doc_id AND x.sts_code='A'
WHERE  d.mbr_acc_id=:acc AND ABS(d.amt_actv)>0.005;
```

---

## SENARIO YANG DISAHKAN

### Senario 0 — TALLY (bukan bug, jangan sentuh)
**Tanda:** `bal_amt = SUM(amt_actv INV sahaja)`. Mungkin ada `amt_actv` pada RCP (advance) — itu SAH.
**Contoh:** 000Y00895 (bal 62.54 = inv 62.54; ada advance 33.60 dalam RCP — kredit sah)
**Tindakan:** Tiada. Advance payment bukan ketidaktalian.
**Pelajaran:** JANGAN campur `amt_actv` RCP dengan `amt_actv` INV. Advance = kredit, bukan tunggakan.

---

### Senario 1 — Duplicate journal posting (rujuk CASE-001)
**Tanda:** Dua doc J-series identik (sama akaun, sama amaun, sama `create_dt` saat, sama `create_by`), dua-dua knock `tgt_doc_id` yang SAMA.
**Punca:** Double-submit. Satu doc hantu cipta doc+link+txn tapi tak gerakkan baki pelanggan.
**Kesan:** `amt_actv` invois sasaran jadi 0 (patut ada baki). Wang benar hilang dari pandangan SP.
**Contoh:** 000Y00025 (A0124) — J00 000037844/000037845, RM7.10, 2020-05-20 21:00:11
**Fix:** Void doc+link+txn hantu, pulih `amt_actv` invois. `bal_amt` betul, jangan sentuh. (Lihat CASE-001 §9)
**Status:** ✅ SELESAI untuk A0124. Corak boleh berulang di akaun/SP lain.

---

<!-- SENARIO BAHARU DITAMBAH DI SINI SEMASA AUDIT -->

### Senario 2 — `amt_actv` invois terkini tertinggal (bal_amt betul)
**Tanda:** `bal_amt` = running balance ledger terakhir (`dt_acc_amt`/`cr_acc_amt` baris terbaru) — **betul**. Tapi `SUM(amt_actv INV)` ≠ `bal_amt`, biasanya sebab invois terkini `amt_actv=0` sepatutnya ada baki.
**Punca:** Event terakhir tak set `amt_actv` invois dengan betul (jalan kod amt_actv terlepas, jalan kod bal_amt jalan). Songsang dari A0124.
**Contoh:** 000Y00589 (C0316, RADHWA BINTI ABU BAKAR)
- `bal_amt` = 35.04, ledger running akhir = 35.04 ✓ (setuju)
- Invois WATER I20205630 (35.40): advance 0.36 di-knock → patut `amt_actv=35.04`, tapi ter-set **0**
- `SUM(amt_actv INV)` = 0 ≠ 35.04
**Fix:** `UPDATE mon_sp_fi_doc SET amt_actv=<baki betul> WHERE doc_id=<invois terkini>`. `bal_amt` jangan sentuh (betul). Uji `SUM(amt_actv INV)=bal_amt`.
**Tool:** Sepatutnya boleh — tool main semula ledger, kesan mismatch, jana fix amt_actv. TAPI ada J00 aktif (7.10) → tool mungkin `return` (had #1). Perlu ujian.
**Status:** Punca disahkan. Fix belum dilaksanakan (calon ujian tool).

---

### CATATAN — "Arrears Carried Forward" = baki migrasi 2019
Corak berulang: akaun dengan `create_by='MIG'` (dimigrate 2019-11) ada invois pertama berjenis **"Arrears Carried Forward"** — tunggakan bawa masuk dari sistem lama. Running balance ledger **bermula** pada nilai arrears ni, bukan 0.

Dilihat dalam:
- **000Y00025 (A0124):** "Arrears Carried Forward (2019)" RM7.10 — yang kita pulihkan
- **000Y00589:** "Arrears Carried Forward" RM377.20 (invois pertama, 2019-11-17)

**Implikasi untuk migrasi Monthley baru:** JANGAN bawa masuk `bal_amt` sebagai nombor sahaja. Setiap baki bawa-masuk MESTI jadi **opening journal (J00) yang sebenar dengan txn ledger setara** — supaya baki boleh di-derive dari ledger dalam sistem baru. Kalau tidak, sistem baru pun akan ada baki gantung tak-boleh-derive (macam legacy). Ini loophole #9 (lihat jadual).

---

### Senario 3 — [DIRIZAB]
Nombor senario dikekalkan untuk elak kekeliruan rujukan. Corak advance-payment-tak-knock-arrears diperhatikan (000Y00589 ada advance 107.76 tak sepenuhnya knock arrears) — perlu lebih contoh sebelum disahkan sebagai senario tersendiri.

---

<!-- SENARIO BAHARU DITAMBAH DI SINI SEMASA AUDIT -->

### Senario lama #2 (placeholder) — DIGANTI
Kandungan asal digantikan dengan Senario 2 yang disahkan di atas.

---

## Akaun 000Y Tak Tally (guna definisi betul INV-sahaja)

Senarai sementara (perlu disahkan bila query diagnostik induk dijalankan bersih):

| acc_id | ref | bal_amt | inv_actv | diff | senario |
|---|---|---|---|---|---|
| 000Y00589 | C0316 | 35.04 | 0.00 | 35.04 | ? (inv_actv 0 tapi bal 35 — pelik) |
| 000Y00155 | A0624 | 927.79 | 852.49 | 75.30 | ? |
| 000Y00573 | C0228 | 512.00 | 492.00 | 20.00 | ? (corak RM20) |
| 000Y00706 | C0803 | 2096.03 | 2076.03 | 20.00 | ? (corak RM20) |
| 000Y00333 | B0320 | 1025.35 | 1005.35 | 20.00 | ? (corak RM20) |
| 000Y00416 | B0626 | 1037.14 | 1032.84 | 4.30 | ? |
| 000Y00977 | D0814 | 24.78 | 42.38 | -17.60 | ? (inv_actv > bal) |
| 000Y00394 | B0603 | 1893.11 | 1910.61 | -17.50 | ? (inv_actv > bal) |
| 000Y00019 | A0118 | 375.49 | 382.09 | -6.60 | ? |
| 000Y00026 | A0126 | 213.34 | 218.24 | -4.90 | ? |
| ... | | | | | (lebih — saring dengan query induk) |

**Nota:** Senarai ni dari query lama yang mungkin masih ada advance-payment noise. Jalankan query diagnostik induk (INV-sahaja) untuk senarai muktamad sebelum audit.

---

## Kaedah Fix Utama — `FixAccountGLM.java` (tool partner)

Partner sudah bina tool yang **main semula (replay) seluruh ledger** dari `mon_fi_doc_txn` mengikut turutan masa (`create_dt`), bina semula running balance dari sifar, dan jana SQL pembetulan. Ni menyelesaikan masalah teras yang buat pengiraan-dari-link gagal: **link tak lengkap**. Tool tak bergantung pada link — ia main semula txn (sumber lengkap) dan **jana semula link yang hilang** melalui padanan kronologi invois↔resit.

### Apa yang tool betulkan (tiga jenis, sepadan dengan senario kita)

| Lokasi kod | Semakan | Senario katalog |
|---|---|---|
| Baris 288-310 | Running balance `dt_acc_amt`/`cr_acc_amt` tak padan replay → `UPDATE mon_fi_doc_txn` | Senario 1 (lost update A0124) |
| Baris 335-369 | `amt_actv` INV vs jumlah link tak tally → betulkan; kalau over, batal link berlebihan, pulih amt_actv | Senario over-allocation |
| Baris 407-554 | Link hilang → padan semula invois↔resit FIFO ikut masa, jana `INSERT mon_fi_doc_link` + kemaskini amt_actv | Punca "link tak lengkap" |
| Baris 561-569 | `bal_amt` akaun tak padan replay → `UPDATE mon_sp_acc` | Senario 2 (bal tersangkut) |

### Aliran kerja
1. Partner run tool mod diagnostik (`printStmt=false`) untuk akaun bermasalah
2. Tool jana SQL pembetulan (bukan auto-execute — output sahaja)
3. **Kita sahkan SQL ikut katalog senario** sebelum run
4. Run dalam transaction, uji `SUM(amt_actv INV) = bal_amt`, commit

### HAD TOOL — mesti tahu sebelum guna

| # | Had | Baris | Kesan |
|---|---|---|---|
| 1 | **J01/J02 aktif tak disokong** — `return` terus | 542-545 | Akaun dengan adjustment aktif berhenti tanpa fix penuh. Perlu kendali manual (macam A0124 dibuat tangan). |
| 2 | **Bug cabang RCP < INV** — kedua-dua `t.amtActv` & `d.amtActv` di-set 0 | 531-534 | Boleh tinggal baki tak terpadan. Partner sedar ("preserves original Python logic"). Semak akaun yang kena cabang ni. |
| 3 | `link_id` guna placeholder `?` | 428+ | Diisi oleh `CALL rdt_generate_sequence('FI_DOC_LINK')` sebelum tiap INSERT. Faham mekanisme sequence sebelum run. |
| 4 | Guna `open_amt` (mon_sp_acc) | 175 | Peranan belum disahkan sepenuhnya — lihat bahagian bawah. |

### Nilai tool untuk siasatan
Tool ni **kaedah audit utama** sekarang, ganti audit-tangan. Katalog senario jadi **lapisan pengesahan** — kita guna dia untuk sahkan SQL yang tool jana masuk akal, bukan untuk audit dari sifar. Bila tool jumpa corak baru (atau tersekat pada had di atas), kita audit tangan dan tambah senario baharu.

### Nota untuk Monthley baru
Tool ni **wujud kerana** legacy ada empat sumber baki yang boleh menyimpang dan link tak lengkap. Dalam sistem baru yang ledger-derived, **tool jenis ni tak perlu wujud langsung** — sebab baki dikira on-read dari satu ledger lengkap, mustahil drift. Kalau Monthley baru perlukan tool "fix account" suatu hari, itu tanda reka bentuk ledger-tunggal telah dilanggar.

---

## `open_amt` — peranan (SEDANG DISIASAT)

Kolum `mon_sp_acc.open_amt` dibaca oleh tool (baris 175) tapi tak pernah muncul dalam siasatan SQL kita. Hipotesis: **opening balance** akaun masa dicipta/dimigrate — baki pembukaan yang mungkin tiada invois sokongan.

**Calon jawapan:** 000Y00589 (`bal_amt=35.04`, `inv_actv=0`). Kalau `open_amt=35.04`, maka bal tersangkut tu sebenarnya opening balance yang sah, bukan bug. Berkait dengan `doc_type='J00'` (opening/adjustment).

Perlu sahkan: query `open_amt` untuk akaun 000Y, terutama 000Y00589.

---

## Prinsip Kerja

1. **Jalankan `FixAccountGLM` mod diagnostik** untuk akaun bermasalah → tool jana SQL pembetulan.
2. **Sahkan SQL ikut katalog senario** — masuk akal? Sepadan dengan senario yang dikenali? Kalau tool tersekat (J01/J02, atau corak baru) → audit tangan, tambah senario.
3. **Run dalam transaction.** Uji `SUM(amt_actv INV) = bal_amt` sebelum commit.
4. **`bal_amt` biasanya BETUL** (running, disahkan majoriti). Yang rosak selalunya `amt_actv`. Tapi ada kes terpencil songsang (partner sahkan ~1/100) — tool main-semula ledger tangani kedua-dua arah.
5. **Kumpul senario di sini.** Bila SP 000Y habis, katalog ni jadi rujukan untuk SP lain.
6. **Ujian kawalan:** sebelum percaya output tool untuk akaun baru, sahkan ia bagi jawapan betul untuk 000Y00025 (A0124, kita tahu jawapannya) dan 000Y00895 (tally, ada advance).

---

## Loophole untuk Monthley Baru (kumulatif)

| # | Loophole legacy | Guard Monthley baru |
|---|---|---|
| 1 | Duplicate journal posting | Idempotency token atas niat operasi `(sp, acc, doc_type, source_ref, period)`, di-mint klien masa borang dibuka |
| 2 | Over-allocation tanpa had | Invariant `SUM(alloc aktif) <= doc.amt` + pessimistic lock pada doc (aggregate boundary) |
| 3 | Posting asimetri / lost update | Ledger-derived balance — satu sumber, derive on-read |
| 4 | Entry tak seimbang | Balanced-entry invariant per JournalEntry aggregate |
| 5 | **Empat representasi baki menyimpang** (bal_amt, amt_actv, link, txn) | **Satu event ledger lengkap; bal_amt, amt_actv, allocation semua derive daripadanya. Mustahil menyimpang.** |
| 6 | `amt_actv` memandu penalti tapi boleh rosak senyap | Penalti dikira atas baki derived, bukan cache. Kalau baki derived, `amt_actv` rosak mustahil. |
| 7 | Advance payment bercampur dengan tunggakan dalam `amt_actv` | Model kredit/advance sebagai entiti berasingan yang eksplisit, bukan `amt_actv` pada RCP. |
| 8 | `mon_fi_doc_link` tak lengkap (bayaran tanpa link) | Setiap pergerakan wang WAJIB jadi event ledger. Tiada jalan pintas yang terlepas rekod. |
| 9 | **Baki migrasi ditulis terus ke `bal_amt` tanpa txn setara** (`create_by='MIG'`, "Arrears Carried Forward") | Migrasi WAJIB cipta opening journal (J00) dengan txn ledger sebenar untuk setiap baki bawa-masuk. Jangan tulis nombor terus. |

---

*Dokumen hidup. Dikemaskini setiap kali senario baharu disahkan semasa audit SP 000Y.*
