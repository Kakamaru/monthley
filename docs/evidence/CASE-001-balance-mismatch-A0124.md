# CASE-001 — Balance Mismatch: Akaun A0124 (SP 000Y)

| | |
|---|---|
| **Status** | Punca disahkan — fix belum dilaksanakan |
| **Tarikh siasat** | 2026-07-17 |
| **DB** | `p302_my` (MariaDB 11.x, production) |
| **Severity** | Sederhana per-akaun; **Tinggi** secara sistemik (2 bug family, 21 SP terjejas) |
| **Rujukan sistem baru** | Finding #2 (idempotency), Finding #3 (invariant guard) |

---

## 1. Ringkasan Eksekutif

Akaun `A0124` papar `Balance Amount = MYR 42.50` tetapi senarai invois aktif hanya tunjuk `MYR 35.40` — gap **RM7.10**.

Punca: **duplicate journal posting** pada 2020-05-20. Dua dokumen `J00` identik dicipta dalam saat yang sama, dua-dua knock invois yang sama. Salah satu daripadanya adalah hantu — dia cipta doc + link + txn, tetapi **tidak menggerakkan baki pelanggan**.

**Kesimpulan penting: `bal_amt` = 42.50 adalah BETUL. `amt_actv` yang bocor.**

Ini bertentangan dengan andaian awal kita (bug cancellation / `bal_amt` cache drift). Cancellation flow akaun ni sebenarnya bersih.

Siasatan turut membongkar **bug family kedua yang berasingan**: 387 invois merentasi 21 SP mempunyai allocation melebihi nilai invois (~RM19,147).

---

## 2. Problem Statement

### Gejala

| Sumber | Nilai | Table/kolum |
|---|---|---|
| UI "Balance Amount" | MYR 42.50 | `mon_sp_acc.bal_amt` |
| UI senarai invois aktif | MYR 35.40 | `SUM(mon_sp_fi_doc.amt_actv)` |
| **Gap** | **RM7.10** | |

### Akaun terlibat

```
sp_code     : 000Y
acc_id      : 000Y00025
ref_no      : A0124
mbr_name    : PENDAMAH A/P GURULU
bal_amt     : 42.5000
sts_code    : A
SP          : PERBADANAN PENGURUSAN PANGSAPURI SRI MUTIARA
```

### Soalan asal

Mana satu betul — `bal_amt` atau `amt_actv`? Dan di mana culprit?

---

## 3. Punca Problem (Root Cause)

### 3.1 Dokumen duplicate

Pada `2020-05-20 21:00:11`, user `00IK` mencipta **dua** dokumen `J00` yang identik:

| Field | `MY000022KQ` | `MY000022KS` |
|---|---|---|
| doc_type | J00 | J00 |
| ref_no | 000037844 | 000037845 |
| doc_dt | 2020-05-20 | 2020-05-20 |
| amt_ | 7.1000 | 7.1000 |
| fi_period | 2020120500 | 2020120500 |
| mbr_acc_id | 000Y00025 | 000Y00025 |
| descr | Account Adjustment | Account Adjustment |
| remarks | ERROR FOR INSURANCE | ERROR FOR INSURANCE |
| **create_dt** | **2020-05-20 21:00:11** | **2020-05-20 21:00:11** |
| **create_by** | **00IK** | **00IK** |
| sts_code | A | A |

**Saat yang sama. User yang sama. Amaun yang sama. Akaun yang sama. Remark yang sama.**

Manusia tidak boleh key-in dua adjustment berasingan dalam saat yang sama. Ref no berturutan (`...844` / `...845`) hanyalah sequence generator yang jalan dua kali. Ini **double-submit**, bukan dua adjustment yang disengajakan.

### 3.2 Kenapa `amt_actv` jadi 0

Invois `MY00000NTQ` (`A01240024`, 2019-11-17, RM817.70) menerima allocation berikut:

| Src doc | Type | Ref | Amaun |
|---|---|---|---|
| MY00000PFQ | RCP | R19-37774 | 252.40 |
| MY00000PYF | RCP | RN0038089 | 101.60 |
| MY00003LH4 | RCP | RN0041111 | 449.50 |
| MY000022KQ | J00 | 000037844 | **7.10** |
| MY000022KS | J00 | 000037845 | **7.10** ← hantu |
| | | **JUMLAH** | **817.70** |

Sebab jumlah = `817.70` **tepat**, sistem kira invois dah settle penuh → `amt_actv = 0`.

Tanpa J00 hantu: `803.50 (resit) + 7.10 (J00 sah) = 810.60` → baki `7.10` sepatutnya kekal outstanding.

### 3.3 Pengesahan matematik

```
Invois A01240024                        817.70
− Resit (252.40 + 101.60 + 449.50)     −803.50
− J00 sah (satu sahaja)                  −7.10
                                        ───────
Baki invois A01240024                     7.10   ← sepatutnya masih aktif
+ Invois I20205066 (2026-07-13)          35.40
                                        ───────
JUMLAH                                   42.50   ✓ padan bal_amt
```

Padan **tepat**. Bukan kebetulan.

---

## 4. Finding Utama — Semantik Ledger

> **Ini penemuan paling penting dalam siasatan ini, dan ia tiada dalam mana-mana dokumentasi.**

Dalam `mon_fi_doc_txn`:

| Kolum | Anggapan asal (SALAH) | Realiti |
|---|---|---|
| `dt_acc_amt` | Amaun debit | **Running balance** akaun `dt_acc_id` selepas posting |
| `cr_acc_amt` | Amaun kredit | **Running balance** akaun `cr_acc_id` selepas posting |
| `amt_` | — | **Amaun transaksi sebenar** |

Salah faham ini menyebabkan kami tersasar **3 pusingan siasatan**. Nombor seperti `−954,060.95` dan `−21,461,169.72` nampak seperti amaun gila-gila, sedangkan ia hanyalah snapshot baki akaun kawalan.

### Bukti — jejak baki pelanggan `000Y00025`

| Doc | Tarikh | Amaun (`amt_`) | `cr_acc_amt` (baki) |
|---|---|---|---|
| INV I2013661 | 2020-05-07 | +33.00 | **854.30** |
| J00 #1 `MY000022KQ` | 2020-05-20 21:00:11 | −7.10 | **847.20** ✓ |
| J00 #2 `MY000022KS` | 2020-05-20 21:00:11 | −7.10 | **847.20** ← tak bergerak! |
| RCP RN0039610 | 2020-06-01 | −94.40 | **752.80** ✓ |

`854.30 − 7.10 = 847.20`. Betul.
J00 kedua sepatutnya bawa ke `840.10`. **Tidak berlaku.**

### Cek silang hujung talian — RCP RN0071598 (2026-07-16)

```
196.50 − 7 = 189.50 → −70 = 119.50 → −7 = 112.50 → −70 = 42.50
```

Baki akhir = **42.50** ✓ — mengesahkan `bal_amt` betul.

### Baris txn penuh

```
txn_id   sp    doc_id       fi_period   txn_code  prod_descr                      dt_acc_id   dt_acc_amt      cr_acc_id   cr_acc_amt  amt_    sts
MY3GC8   000Y  MY000022KQ   2020120500  M3000     Account Adjustment (Reduction)  000Y00001   -954060.9500    000Y00025   847.2000    7.1000  A
MY3GCA   000Y  MY000022KS   2020120500  M3000     Account Adjustment (Reduction)  000Y00001   -954053.8500    000Y00025   847.2000    7.1000  A
```

### Implikasi kritikal

Posting J00 #2 adalah **separuh jalan / asimetri**:

- Akaun kawalan `000Y00001`: **BERGERAK** (−954,060.95 → −954,053.85, iaitu +7.10)
- Akaun pelanggan `000Y00025`: **TIDAK BERGERAK** (847.20 → 847.20)
- Doc, link, txn: **SEMUA TERCIPTA**

Jadi ini **bukan sekadar cache drift**. Ledger itu sendiri tidak simetri untuk transaksi ini. Satu kaki double-entry bergerak, satu lagi tidak.

---

## 5. Apa Yang BUKAN Bug (False Positives)

> **Bahagian ini wajib dibaca sebelum menulis sebarang detector baru.**
> Kami tersalah label **dua kali**. Jangan ulang.

### 5.1 Detector #1 — "journal sama akaun/hari/amaun" → 2,378 rekod

```sql
GROUP BY sp_code, mbr_acc_id, doc_type, doc_dt, amt_
HAVING COUNT(*) > 1
```

**Hampir semuanya false positive.** Detector ini tidak mengambil kira `period_id`.

### 5.2 Detector #2 — tambah `create_dt` + `create_by` → masih false positive

Pecahan mengikut `doc_type`:

| doc_type | Kumpulan | "Extra" docs | Verdict |
|---|---|---|---|
| INV | 10,404 | 22,646 | **False positive** — batch generation |
| J01 | 657 | 1,841 | **Majoriti false positive** — bulk cancel |
| RCP | 13 | 13 | Perlu semak |
| J00 | 8 | 10 | **True positive** ← wabak sebenar |

**Kenapa INV false positive:**
Satu run generation menghasilkan banyak invois dengan amaun sama, saat sama, akaun sama, tetapi **period berbeza**. Contoh A0124: `I20200795` (RM7) dan `I20200796` (RM70) diposting serentak setiap bulan. Ini reka bentuk sistem (`charge_frequency`), bukan bug.

**Kenapa J01 false positive:**
```
000Y01010  J01  2022-02-03  42.00  16:28:42  8 docs  ref 000039513–520
```
Lapan J01 RM42 dalam satu saat = **batch cancel 8 invois bulanan berbeza**. Setiap J01 knock invois **berlainan**. Betul, bukan bug.

Corak sama untuk SP `001T` (2021-11-01, RM12 × ratusan akaun) dan `MY000000` (2026-01-05) — semua bulk cancellation run yang sah.

### 5.3 Signature sebenar

> **Dua doc yang knock target yang SAMA.**

Inilah yang membezakan kes A0124 daripada semua batch operation yang sah. Batch cancel yang betul tidak pernah buat begini — setiap contra ada invois sendiri.

```sql
-- DETECTOR YANG BETUL
SELECT d.sp_code, d.mbr_acc_id, d.doc_type, d.doc_dt, d.amt_,
       d.create_dt, d.create_by, l.tgt_doc_id,
       COUNT(*) AS cnt,
       GROUP_CONCAT(d.doc_id) AS doc_ids,
       GROUP_CONCAT(d.ref_no) AS ref_nos
FROM   mon_sp_fi_doc d
JOIN   mon_fi_doc_link l
       ON l.src_doc_id = d.doc_id AND l.sts_code = 'A'
WHERE  d.sts_code = 'A'
GROUP  BY d.sp_code, d.mbr_acc_id, d.doc_type, d.doc_dt, d.amt_,
          d.create_dt, d.create_by, l.tgt_doc_id
HAVING COUNT(*) > 1
ORDER  BY d.doc_type, cnt DESC;
```

> ⚠️ **BELUM DISAHKAN.** Query ini belum dijalankan/dilaporkan hasilnya. Kes A0124 **sepatutnya** keluar dalam hasil (dua src → satu tgt `MY00000NTQ`, dua-dua link 'A'). **Jika ia pulang kosong, query ini ada kecacatan dan mesti dibetulkan sebelum digunakan untuk fix pukal.**

---

## 6. Bug Family Kedua — Over-Allocation

Berasingan sepenuhnya daripada duplicate posting. Ditemui secara kebetulan semasa siasatan.

### 6.1 Detector

```sql
SELECT i.doc_id, i.ref_no, i.doc_type, i.amt_,
       SUM(l.amt_) AS allocated,
       SUM(l.amt_) - i.amt_ AS over_by
FROM   mon_sp_fi_doc i
JOIN   mon_fi_doc_link l ON l.tgt_doc_id = i.doc_id AND l.sts_code = 'A'
WHERE  i.sts_code = 'A'
GROUP  BY i.doc_id, i.ref_no, i.doc_type, i.amt_
HAVING SUM(l.amt_) > i.amt_ + 0.005;
```

### 6.2 Skala

**387 invois, ~RM19,147.02, merentasi 21 SP.**

| sp_code | Invois terjejas | Jumlah lebih (RM) |
|---|---|---|
| 0013 | 108 | 6,110.00 |
| MY000050 | 62 | 1,496.00 |
| 001A | 35 | 1,570.00 |
| 000Y | 30 | 536.40 |
| 000I | 19 | 267.20 |
| 000U | 18 | 820.90 |
| 0011 | 18 | 153.70 |
| 001I | 15 | 1,626.00 |
| 000R | 10 | 475.00 |
| 0012 | 9 | 497.78 |
| 001C | 9 | 290.65 |
| 0014 | 9 | 1,195.00 |
| 000J | 8 | 802.00 |
| 001L | 7 | 506.87 |
| 001B | 7 | 112.67 |
| 000F | 7 | 913.00 |
| MY000090 | 5 | 490.00 |
| 001K | 4 | 358.35 |
| 001J | 3 | 35.50 |
| 001D | 3 | 400.00 |
| 000H | 1 | 490.00 |
| **JUMLAH** | **387** | **19,147.02** |

Merata 21 SP → **isu engine, bukan isu proses satu SP**.

### 6.3 Tiga corak, satu punca

**Corak A — over_by = amt_ (double-knock)**

`MY0000JER1` (INV RM40.00):
```
link_id  src_doc      amt_    create_dt              sts  src_type  src_ref   src_amt
MYMY3V   MY0000YAPH   40.00   2026-05-07 15:42:30    C    RCP       RCP9454   2710.00
MYMY40   MY0000YAPJ   40.00   2026-05-07 15:42:35    C    RCP       RCP9455   2710.00
MYMY8K   MY0000YAQ7   80.00   2026-05-07 16:58:08    A    RCP       RCP9458   2710.00
```
Dua allocation dibatal, kemudian re-allocate **RM80 ke invois RM40**. Sistem terima bulat-bulat.

**Corak B — over_by tetap RM50**

`MY000046F4` (INV RM300.00):
```
MY20T4   MY00003BC6   50.00    2020-11-26 05:57:56   A   RCP   RST1584   400.00  (asal 2020-09-28)
MY20T5   MY000043ZJ   300.00   2020-11-26 05:57:56   A   RCP   RST1836   400.00  (asal 2020-11-13)
```
Dua link dicipta **saat yang sama** oleh user `0002` — batch job — walaupun resit asal dari Sep & Nov. Batch re-allocation double-count.

**Corak C — ekstrem**

`MY000016DI1` (INV RM7.20):
```
MY21PS   MY0000472R   376.70   2020-11-28 08:53:32   A   RCP   R01148   630.00
```
Satu link **RM376.70** ke invois **RM7.20** — 52× nilai invois.

### 6.4 Punca

**Tiada validation `SUM(active allocations) <= document.amt_` pada masa write.**

Ketiga-tiga corak berkongsi punca yang sama. Sistem membenarkan allocation melebihi nilai dokumen tanpa sebarang halangan.

### 6.5 Nota penting

Kes A0124 **TIDAK** keluar dalam detector ini — allocation dia `817.70` tepat sama dengan invois. Duplicate tersebut *menggantikan* baki yang sah, bukan *melimpah*.

> **Dua famili bug ini memerlukan dua detector berasingan. Satu tidak menangkap satu lagi.**

---

## 7. Kronologi Siasatan (Hipotesis Yang Mati)

Direkod supaya orang lain tidak mengulang jalan buntu yang sama.

| # | Hipotesis | Status | Kenapa mati |
|---|---|---|---|
| 1 | Bug cancellation — `bal_amt` tak revert bila resit dibatal | ❌ MATI | Dua invois batal (`MY0000MWC2` 82.30 → J01 `000040741`; `MY0000PU48` 22.10 → J01 `000041615`) — **dua-dua bersih**. Cancellation flow akaun ni betul. |
| 2 | `bal_amt` cache drift, ledger betul | ❌ MATI | Terbalik. `bal_amt` yang betul. |
| 3 | Dua J00 tu pasangan Dr/Cr net zero | ❌ MATI | Kalau ya, baki = 35.40 + 14.20 = **49.60**. Tak padan langsung. |
| 4 | Dua J00 sah, `bal_amt` terlepas satu | ❌ MATI | Ledger tunjuk baki pelanggan tak bergerak untuk J00 #2. |
| 5 | **Duplicate posting, `amt_actv` bocor** | ✅ **DISAHKAN** | Timestamp identik + jejak ledger + pengesahan matematik 42.50. |

**Pelajaran:** Andaian awal (berdasarkan finding sesi lepas tentang cancellation) adalah salah sepenuhnya untuk kes ini. Data mengalahkan andaian.

---

## 8. Solution — Sistem Baru (Monthley Rebuild)

### 8.1 Guard yang diperlukan

| Bug family | Guard | Lapisan |
|---|---|---|
| Duplicate journal posting | **Idempotency key atas niat operasi**: `(sp_code, account_id, doc_type, source_ref, period_id)` | Domain / DB unique constraint |
| Over-allocation | **Invariant**: `SUM(active allocations) <= document.amount` | Domain, enforce masa write |
| Posting asimetri | **Atomic posting** — ledger + baki + allocation dalam satu transaction boundary | Domain / aggregate |

### 8.2 Kenapa BUKAN unique constraint biasa

> ⚠️ Unique constraint atas `(akaun, jenis, amaun, masa)` akan **menolak batch generation dan bulk cancel yang sah** (rujuk §5).

Guard mesti berdasarkan **niat operasi**, bukan bentuk data:
- ✅ "adjust invois X sebanyak Y" — hanya boleh berlaku **sekali**
- ✅ "cancel 8 invois berbeza" — tetap **lulus**

### 8.3 Ledger sebagai source of truth

Dalam reka bentuk baru, baki **di-derive** daripada ledger dan tidak disimpan sebagai kebenaran berasingan.

> **Kelas bug ini hilang sepenuhnya** — kerana tidak ada dua tempat untuk menjadi tidak selaras.

### 8.4 Kenapa reconciliation job SAHAJA tidak memadai

Bug A0124 **tidak akan tertangkap** oleh reconciliation job biasa yang membandingkan `bal_amt` vs derived — kerana dari sudut `doc_link`, semuanya balance cantik (817.70 = 817.70).

Yang salah adalah **kewujudan doc yang tidak sepatutnya wujud**.

Reconciliation = jaring keselamatan. **Idempotency + invariant = pertahanan utama.**

---

## 9. Cara Fix — Akaun A0124 (Single)

### 9.1 Pra-syarat

- [ ] `DESCRIBE mon_fi_doc_link` — sahkan nama kolum PK (diteka sebagai `link_id` daripada output `MY0W4K` / `MY0W4M`)
- [ ] `DESCRIBE mon_sp_fi_doc` — sahkan nama table (`mon_fi_doc` vs `mon_sp_fi_doc`)
- [ ] **Backup** table terlibat
- [ ] Keputusan perakaunan untuk akaun kawalan (rujuk §11)

### 9.2 Verify dahulu — JANGAN run UPDATE lagi

```sql
SELECT COUNT(*) FROM mon_fi_doc_link
WHERE  tgt_doc_id = 'MY00000NTQ' AND sts_code = 'A';
-- jangkaan: 5

SELECT SUM(amt_) FROM mon_fi_doc_link
WHERE  tgt_doc_id = 'MY00000NTQ' AND sts_code = 'A';
-- jangkaan: 817.70
```

Jika tidak padan → **berhenti**. Andaian kita tidak lengkap.

### 9.3 Fix script

```sql
START TRANSACTION;

-- 1. Void doc hantu
UPDATE mon_sp_fi_doc SET sts_code='C', cancel_dt=NOW(),
       update_dt=NOW(), update_by='FIXDUP'
WHERE  doc_id='MY000022KS' AND sts_code='A';

-- 2. Void link hantu
UPDATE mon_fi_doc_link SET sts_code='C',
       update_dt=NOW(), update_by='FIXDUP'
WHERE  link_id='MY0W4M' AND sts_code='A';

-- 3. Void txn hantu
UPDATE mon_fi_doc_txn SET sts_code='C',
       update_dt=NOW(), update_by='FIXDUP'
WHERE  txn_id='MY3GCA' AND sts_code='A';

-- 4. Pulihkan amt_actv invois
UPDATE mon_sp_fi_doc SET amt_actv=7.1000,
       update_dt=NOW(), update_by='FIXDUP'
WHERE  doc_id='MY00000NTQ';

-- 5. SEMAK SEBELUM COMMIT
SELECT SUM(amt_actv) FROM mon_sp_fi_doc
WHERE  mbr_acc_id='000Y00025' AND sts_code='A';
-- MESTI 42.50, padan bal_amt

-- COMMIT;   ← hanya selepas nombor disahkan
ROLLBACK;
```

### 9.4 Yang TIDAK disentuh

| Item | Sebab |
|---|---|
| `mon_sp_acc.bal_amt` | **Sudah betul (42.50)**. Jangan sentuh. |
| `MY000022KQ` (J00 sah) | Adjustment yang sah — kekalkan. |
| `MY0W4K` (link sah) | Kekalkan. |
| `MY3GC8` (txn sah) | Kekalkan. |

### 9.5 Post-fix verification

- [ ] UI papar `Balance Amount = MYR 42.50`
- [ ] Senarai invois aktif papar **dua** baris: `A01240024` (RM7.10) + `I20205066` (RM35.40)
- [ ] `SUM(amt_actv) = bal_amt = 42.50`

### 9.6 Rollback

Jika UI tidak seperti dijangka:

```sql
UPDATE mon_sp_fi_doc  SET sts_code='A', cancel_dt=NULL WHERE doc_id='MY000022KS';
UPDATE mon_fi_doc_link SET sts_code='A' WHERE link_id='MY0W4M';
UPDATE mon_fi_doc_txn  SET sts_code='A' WHERE txn_id='MY3GCA';
UPDATE mon_sp_fi_doc  SET amt_actv=0.0000 WHERE doc_id='MY00000NTQ';
```

Semua perubahan bertanda `update_by='FIXDUP'` — mudah dikesan dan diundur.

---

## 10. Cara Fix — Kesemua Sekali (Bulk)

> ⚠️ **JANGAN mulakan bulk fix sebelum A0124 selesai dan disahkan.**
> Buat satu akaun dahulu → sahkan UI → baru scale.

### 10.1 Fasa 1 — Duplicate J00 (8 kumpulan, 10 extra docs)

Skala kecil — **boleh audit satu per satu dengan tangan**. Ini pendekatan yang disyorkan.

**Langkah 1: Senarai penuh**

```sql
SELECT d.sp_code, d.mbr_acc_id, d.doc_type, d.doc_dt, d.amt_,
       d.create_dt, d.create_by, l.tgt_doc_id,
       COUNT(*) AS cnt,
       GROUP_CONCAT(d.doc_id ORDER BY d.doc_id)  AS doc_ids,
       GROUP_CONCAT(d.ref_no ORDER BY d.doc_id)  AS ref_nos,
       GROUP_CONCAT(l.link_id ORDER BY d.doc_id) AS link_ids
FROM   mon_sp_fi_doc d
JOIN   mon_fi_doc_link l
       ON l.src_doc_id = d.doc_id AND l.sts_code = 'A'
WHERE  d.sts_code = 'A'
  AND  d.doc_type IN ('J00','J01','J02','JNL')
GROUP  BY d.sp_code, d.mbr_acc_id, d.doc_type, d.doc_dt, d.amt_,
          d.create_dt, d.create_by, l.tgt_doc_id
HAVING COUNT(*) > 1;
```

**Langkah 2: Untuk setiap kumpulan, sahkan secara manual**

- [ ] Timestamp benar-benar identik (bukan berbeza beberapa saat)?
- [ ] Semua knock `tgt_doc_id` yang **sama**?
- [ ] Semak `mon_fi_doc_txn` — adakah baki pelanggan (`cr_acc_amt`) **tidak bergerak** untuk doc kedua?
- [ ] **Jika baki BERGERAK untuk kedua-dua doc → BUKAN kes yang sama. Siasat berasingan.**

**Langkah 3: Fix — kekalkan yang PERTAMA, void yang selebihnya**

Untuk setiap kumpulan: kekalkan doc dengan `doc_id` terkecil (yang berjaya gerakkan baki), void yang lain. Ikut corak §9.3.

**Langkah 4: Kira semula `amt_actv` untuk setiap invois terjejas**

```sql
-- Untuk setiap tgt_doc_id yang terjejas:
SELECT i.doc_id, i.amt_,
       i.amt_ - COALESCE(SUM(CASE WHEN l.sts_code='A' THEN l.amt_ END),0) AS should_be_active,
       i.amt_actv AS current_active
FROM   mon_sp_fi_doc i
LEFT JOIN mon_fi_doc_link l ON l.tgt_doc_id = i.doc_id
WHERE  i.doc_id = :tgt_doc_id
GROUP BY i.doc_id, i.amt_, i.amt_actv;
```

**Langkah 5: Semak RCP (13 kumpulan)**

Belum disiasat. Resit tunai RM50 × 2 pada hari sama **boleh jadi sah**. Guna signature "sama target" untuk menapis.

### 10.2 Fasa 2 — Over-Allocation (387 invois, 21 SP)

> ⚠️ **JAUH lebih kompleks. Setiap corak perlukan strategi fix berbeza. Jangan automate secara buta.**

| Corak | Contoh | Strategi cadangan | Status |
|---|---|---|---|
| A — double-knock | `MY0000JER1` (40→80) | Void link berlebihan, kembalikan allocation kepada nilai invois | Perlu semakan |
| B — over RM50 tetap | `MY000046F4` (300→350) | **Punca belum difahami sepenuhnya.** Terlalu konsisten untuk jadi kemalangan. Siasat mekanisme dahulu. | **BLOCKED** |
| C — ekstrem | `MY000016DI1` (7.20→376.70) | Kes demi kes, mungkin data entry error | Perlu semakan |

**Cadangan urutan:**

1. Mulakan dengan SP `0013` (108 invois, RM6,110) — impak terbesar, corak mungkin seragam dalam satu SP
2. Klasifikasikan setiap invois kepada corak A/B/C dahulu
3. Fix corak A sahaja (paling jelas), tinggalkan B & C sehingga punca difahami
4. Untuk setiap fix: `amt_actv` mesti dikira semula, dan `bal_amt` **mesti disemak** (belum tentu betul untuk kes-kes ini!)

> **Amaran:** Kesimpulan "`bal_amt` betul" hanya disahkan untuk **A0124 sahaja**. Untuk 387 kes over-allocation, `bal_amt` mungkin betul, mungkin tidak. **Setiap kes perlu jejak ledger sendiri.**

### 10.3 Query kesihatan selepas fix

```sql
-- Semua akaun: bandingkan bal_amt vs SUM(amt_actv)
SELECT a.sp_code, a.acc_id, a.ref_no, a.bal_amt,
       COALESCE(SUM(d.amt_actv),0) AS sum_active,
       a.bal_amt - COALESCE(SUM(d.amt_actv),0) AS diff
FROM   mon_sp_acc a
LEFT JOIN mon_sp_fi_doc d
       ON d.mbr_acc_id = a.acc_id AND d.sts_code = 'A'
WHERE  a.sts_code = 'A'
GROUP  BY a.sp_code, a.acc_id, a.ref_no, a.bal_amt
HAVING ABS(a.bal_amt - COALESCE(SUM(d.amt_actv),0)) > 0.005
ORDER  BY ABS(a.bal_amt - COALESCE(SUM(d.amt_actv),0)) DESC;
```

> Query ini belum dijalankan. **Ia sepatutnya menjadi langkah pertama** untuk mengetahui skala sebenar masalah `bal_amt` vs `amt_actv` merentasi seluruh sistem — bukan hanya A0124.

---

## 11. Isu Terbuka

| # | Isu | Pemilik | Catatan |
|---|---|---|---|
| 1 | **Akaun kawalan `000Y00001` lebih RM7.10** | Bosku sebenar (perakaunan) | Void txn sahaja **tidak** reverse kesan pada akaun kawalan. Perlu keputusan: post correcting entry atau biarkan? Jumlah kecil per kes, tetapi × 8 kes J00 + kesan lain. |
| 2 | Nama kolum `link_id` | Teknikal | Diteka daripada output. Sahkan dengan `DESCRIBE mon_fi_doc_link`. |
| 3 | Nama table: `mon_fi_doc` vs `mon_sp_fi_doc` | Teknikal | Digunakan bertukar-tukar sepanjang siasatan. |
| 4 | Detector §5.3 belum disahkan | Teknikal | Kes A0124 mesti keluar. Jika kosong → query cacat. |
| 5 | Corak B (over RM50 tetap) — mekanisme? | Teknikal + perakaunan | Terlalu konsisten untuk jadi kemalangan. Deposit? Penalti? Komponen tidak termasuk dalam `amt_`? |
| 6 | RCP duplicate (13 kumpulan) | Teknikal | Belum disiasat. |
| 7 | **Skala sebenar `bal_amt` vs `amt_actv`** | Teknikal | Query §10.3 belum dijalankan. Kita hanya tahu tentang 1 akaun. |
| 8 | Bug masih hidup? | Teknikal | Corak A (`MY0000JER1`) bertarikh **2026-05-07** — baru-baru ini. Over-allocation **masih berlaku hari ini**. |

---

## 12. Lampiran — Rujukan Skema

### `mon_sp_acc` (kolum berkaitan)
```
sp_code, acc_id, ref_no, mbr_name, bal_amt, sts_code
```

### `mon_sp_fi_doc` (kolum berkaitan)
```
doc_id, sp_code, doc_type, doc_dt, descr, ref_no, curr_,
amt_, amt_actv, mbr_name, mbr_acc_id, fi_period, sts_code,
cancel_dt, create_dt, create_by, update_dt, update_by, remarks
```

**doc_type:** `INV` (invois), `RCP` (resit), `J00` (opening/adjustment), `J01` (contra invois batal), `J02` (contra resit batal), `JNL` (journal manual)

### `mon_fi_doc_link`
```
link_id(?), src_doc_id, tgt_doc_id, amt_, create_dt, create_by,
update_dt, update_by, sts_code
```
**Arah:** `src_doc_id` = RCP/J00 (yang bayar) → `tgt_doc_id` = INV (yang dibayar)

### `mon_fi_doc_txn` (PENUH — dari DESCRIBE)
```
txn_id          varchar(22)      NO   PRI
sp_code         varchar(12)      NO   MUL
doc_id          varchar(20)      NO   MUL
fi_period       int(4) unsigned  YES  MUL
txn_code        varchar(5)       NO
prod_id         varchar(12)      YES
prod_ref_no     varchar(50)      YES
prod_descr      varchar(100)     YES
dt_acc_id       varchar(15)      NO   MUL
dt_acc_amt      decimal(18,4)    YES   ← RUNNING BALANCE, bukan amaun!
cr_acc_id       varchar(15)      NO   MUL
cr_acc_amt      decimal(18,4)    YES   ← RUNNING BALANCE, bukan amaun!
qty_            decimal(5,2)     YES  default 1.00
curr_           varchar(3)       NO   default MYR
price_unit      decimal(18,4)    YES
amt_            decimal(18,4)    YES   ← AMAUN SEBENAR
sales_tax_rate  decimal(2,2)     YES
sales_tax_amt   decimal(18,4)    YES
sts_code        char(1)          YES  default P
create_dt       timestamp        NO   default current_timestamp()
create_by       varchar(10)      NO
update_dt       datetime         YES
update_by       varchar(10)      YES
subscr_id       varchar(18)      YES
```

**txn_code:** `M3000` = Account Adjustment

### Nota semantik penting

| Perkara | Nilai |
|---|---|
| Amaun transaksi | `mon_fi_doc_txn.amt_` |
| Baki selepas posting | `dt_acc_amt` (untuk `dt_acc_id`), `cr_acc_amt` (untuk `cr_acc_id`) |
| Akaun kawalan SP `000Y` | `000Y00001` |
| Akaun pelanggan A0124 | `000Y00025` |
| Status aktif | `sts_code = 'A'` |
| Status batal | `sts_code = 'C'` + `cancel_dt IS NOT NULL` |

---

## 13. Data Rujukan Mentah

### Dokumen J00 duplicate
```
MY000022KQ  c178bf02-0866-4eff-bcbb-72ce28e252d1  000Y  J00  2020-05-20  Account Adjustment  000037844  MYR  7.1000  0.0000  TERUVANGADAN A/L GOVINDASAMY  000Y00025  2020120500  A  2020-05-20  ...  2020-05-20 21:00:11  00IK  2020-05-20 21:00:11  00IK  0  ERROR FOR INSURANCE  -1
MY000022KS  f1f25424-05f3-43fa-8988-39246cf9262c  000Y  J00  2020-05-20  Account Adjustment  000037845  MYR  7.1000  0.0000  TERUVANGADAN A/L GOVINDASAMY  000Y00025  2020120500  A  2020-05-20  ...  2020-05-20 21:00:11  00IK  2020-05-20 21:00:11  00IK  0  ERROR FOR INSURANCE  -1
```

> **Nota:** `mbr_name` pada doc = `TERUVANGADAN A/L GOVINDASAMY`, tetapi `mon_sp_acc.mbr_name` = `PENDAMAH A/P GURULU`. Kemungkinan pertukaran pemilik unit sejak 2020. Bukan sebahagian daripada bug ini, tetapi direkod untuk rujukan.

### Link
```
link_id  src_doc_id   tgt_doc_id   amt_    create_dt              create_by  ...  sts
MY0W4K   MY000022KQ   MY00000NTQ   7.1000  2020-05-20 21:00:11    00IK            A
MY0W4M   MY000022KS   MY00000NTQ   7.1000  2020-05-20 21:00:11    00IK            A
```

### Transaksi ledger
```
txn_id   sp_code  doc_id       fi_period   txn_code  prod_descr                      dt_acc_id  dt_acc_amt     cr_acc_id  cr_acc_amt  qty   curr  amt_    tax  sts  create_dt            create_by
MY3GC8   000Y     MY000022KQ   2020120500  M3000     Account Adjustment (Reduction)  000Y00001  -954060.9500   000Y00025  847.2000    1.00  MYR   7.1000  0.00 A    2020-05-20 21:00:11  00IK
MY3GCA   000Y     MY000022KS   2020120500  M3000     Account Adjustment (Reduction)  000Y00001  -954053.8500   000Y00025  847.2000    1.00  MYR   7.1000  0.00 A    2020-05-20 21:00:11  00IK
```

### Cancellation yang BERSIH (bukti hipotesis #1 mati)
```
MY0000MWC2  INV  I20140376  2024-06-26  82.3000  0.0000  C  2024-06-26 18:41:18
MY0000MXZ5  J01  000040741  2024-06-26  82.3000  0.0000  A          ← contra padan

MY0000PU48  INV  I20156492  2025-01-01  22.1000  0.0000  C  2025-01-02 15:28:43
MY0000Q5RG  J01  000041615  2025-01-02  22.1000  0.0000  A          ← contra padan
```

### Invois aktif A0124 (keadaan semasa)
```
MY0000ZBOH  INV  I20205066  2026-07-13  35.4000  35.4000  A    ← satu-satunya aktif
MY00000NTQ  INV  A01240024  2019-11-17  817.7000  0.0000  A    ← sepatutnya 7.1000
```

### Allocation penuh ke `MY00000NTQ`
```
MY00000PFQ  R19-37774   2019-11-18  252.4000  A  →  MY00000NTQ  252.4000  A
MY00000PYF  RN0038089   2019-11-29  101.6000  A  →  MY00000NTQ  101.6000  A
MY000022KS  000037845   2020-05-20    7.1000  A  →  MY00000NTQ    7.1000  A   ← HANTU
MY000022KQ  000037844   2020-05-20    7.1000  A  →  MY00000NTQ    7.1000  A   ← SAH
MY00003LH4  RN0041111   2020-10-08  847.2000  A  →  MY00000NTQ  449.5000  A
                                                    ─────────────────────
                                                    JUMLAH: 817.7000
```

---

*Dokumen ini disediakan semasa siasatan 2026-07-17. Semua query dijalankan terhadap production `p302_my` melalui DBeaver (VPN). Tiada data diubah — siasatan read-only sahaja.*
