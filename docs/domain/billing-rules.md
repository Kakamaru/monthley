# Peraturan Penjanaan Bil — Monthley

> Status: **Disahkan lawan production** (17 Julai 2026)
> Skop: enjin penjanaan invois. Tidak merangkumi resit, penalti lewat, penyata.
> Sumber kebenaran: ujian langsung atas `p302_my` production + persetujuan Kama.

---

## 1. Kenapa dokumen ini wujud

Enjin bil ialah bahagian paling rumit dalam Monthley, dan peraturannya **tidak** dapat dibaca terus dari skema. Empat paksi berasingan berinteraksi untuk menentukan setiap caj. Sebelum dokumen ini, peraturan tersebut hanya wujud dalam kepala Kama dan dalam kelakuan sistem lama.

Setiap peraturan di bawah disertakan **bukti** — query production atau hasil ujian yang mengesahkannya. Kalau peraturan berubah, buktinya kena berubah sekali.

---

## 2. Model teras — empat paksi

Setiap baris caj ditentukan oleh empat paksi yang **bebas antara satu sama lain**:

| Paksi | Sumber | Kesan |
|---|---|---|
| **Mod** | `sp_billing_setting.gen_mode` | Anjak period asas: `POSTPAID` −1 bulan, `CURRENT` 0, `PREPAID` +1 |
| **Ufuk** | `account.charge_frequency` | Berapa banyak kitaran ditarik dalam satu larian |
| **Aras** | `product.charge_frequency` | Granulariti `period_id` setiap caj |
| **Anchor** | `product.anchor_month` | Bulan kitaran bermula (null = Januari) |

### Kesilapan yang dielakkan

Reka bentuk awal menganggap `account.charge_frequency` menentukan **aras** invois — iaitu akaun tahunan menghasilkan 12 invois bulanan. **Salah.**

Ia menentukan **ufuk**: berapa jauh ke depan dijana sekali gus. Aras datang dari produk.

**Bukti (production, invois aktif sejak 2025-01-01):**

```sql
SELECT a.charge_code AS freq_akaun, p.charge_code AS aras_invois, COUNT(*) AS bil
FROM mon_sp_fi_doc d
JOIN fi_period p ON p.period_id = d.fi_period
JOIN mon_sp_acc a ON a.acc_id = d.mbr_acc_id
WHERE d.doc_type = 'INV' AND d.sts_code = 'A'
  AND d.doc_dt >= '2025-01-01'
GROUP BY a.charge_code, p.charge_code;
```

| freq akaun | aras invois | bil |
|---|---|---|
| MO | MO | 318,332 |
| MO | **YR** | **494** |
| MO | HF | 53 |
| MO | QR | 128 |
| YR | **MO** | **784** |
| YR | YR | 1,118 |
| HF | MO | 7,230 |
| HF | HF | 3,819 |
| QR | MO | 432 |
| QR | QR | 361 |

Silang penuh. Akaun bulanan menghasilkan invois tahunan (produk tahunan), akaun tahunan menghasilkan invois bulanan (produk bulanan). Dua paksi berasingan.

### Contoh gabungan

| Ufuk akaun | Freq produk | Hasil |
|---|---|---|
| YEAR (12 bln) | MONTHLY | 12 caj, aras MO |
| YEAR (12 bln) | YEAR | 1 caj, aras YR |
| YEAR (12 bln) | QUARTERLY | 4 caj, aras QR |
| MONTHLY (1 bln) | YEAR | 1 caj aras YR — hanya pada `anchor_month` |

**Bukti (ujian production, 17 Julai 2026):**
Akaun `MY00006000041`, charge_freq = MONTHLY, langgan INSURANCE (YR, RM350) + MAINTENANCE (MO, RM80) + SINKING FUND (MO, RM8).

Larian POSTPAID pada Julai → satu dokumen `MY0000ZCXM`, `doc.fi_period = 2026120600` (Jun), tiga baris:

| baris | txn.fi_period | aras | amaun |
|---|---|---|---|
| INSURANCE | `2026000000` | YR | 350.00 |
| MAINTENANCE | `2026120600` | MO | 80.00 |
| SINKING FUND | `2026120600` | MO | 8.00 |

---

## 3. Dua aras period

Ini pembezaan paling penting dalam keseluruhan enjin.

| Medan | Makna | Ditentukan oleh |
|---|---|---|
| `financial_document.period_id` | **Period larian** — kitaran bil akaun | Mod + bulan larian |
| `financial_document_line.period_id` | **Period liputan** — apa yang dicaj | Freq produk + anchor |

**Bukti (dua larian berturut, akaun sama):**

| Larian | Mod | Bulan larian | `doc.fi_period` | Jangkaan `basePeriod()` |
|---|---|---|---|---|
| 1 | POSTPAID | Julai | `2026120600` (Jun) | Julai − 1 = Jun ✓ |
| 2 | CURRENT | Julai | `2026230700` (Julai) | Julai ✓ |

`PeriodResolver.basePeriod(runMonth, mode)` yang sedia ada menghasilkan tepat `doc.period_id`. Tiada perubahan diperlukan.

Ini juga menjelaskan `MO/MO BEZA` = 10,046 dalam crosstab production: dokumen bulanan dengan baris bulanan tetapi **bulan berlainan** — iaitu offset prepaid/postpaid.

---

## 4. Skema `period_id`

```
2026 2 3 07 00
│    │ │ │  └── simpanan (sentiasa 00)
│    │ │ └───── bulan 01–12 (00 = bukan aras bulan)
│    │ └─────── suku 1–4 (0 = bukan aras suku)
│    └───────── separuh 1–2 (0 = bukan aras separuh)
└────────────── tahun
```

```
period_id = tahun×1000000 + H×100000 + Q×10000 + bulan×100
  H = bulan ≤ 6 ? 1 : 2
  Q = CEIL(bulan / 3)
```

**Deterministik.** Dilaksanakan sebagai fungsi tulen dalam `com.monthley.shared.PeriodIds`.

`fi_period` ialah **jadual rujukan sahaja** — dropdown, join laporan, pemetaan migrasi. Enjin bil tidak membacanya. Kalau data `fi_period` rosak, bil tetap betul.

**Bukti (formula disahkan lawan semua 228 baris MO):**

```sql
SELECT COUNT(*) AS patut_sifar FROM fi_period p
WHERE p.charge_code = 'MO'
  AND p.period_id <> (YEAR(p.start_dt)*1000000
      + IF(MONTH(p.start_dt)<=6,1,2)*100000
      + CEIL(MONTH(p.start_dt)/3)*10000
      + MONTH(p.start_dt)*100);
-- Hasil: 0
```

### Bentuk pokok

`parent_id` mengikut production:

```
Tahun (YR)
├── H1, H2 (HF)          ← tiada anak
└── Q1..Q4 (QR)          ← parent = TAHUN, bukan HF
    └── bulan (MO)
```

HF dan QR ialah **dua cabang selari** di bawah tahun. H1/H2 tiada anak. Ini bukan ralat data — ia bentuk sebenar dalam production, disahkan pada 2026 dan seluruh julat 2018–2026.

Implikasi: rollup laporan melalui `parent_id` tidak akan mencapai bulan dari H1. Gunakan `start_dt`/`end_dt` containment kalau perlu rollup separuh-tahun.

### Perbezaan sengaja dari production

| Perkara | Production | Baru | Sebab |
|---|---|---|---|
| `2026000000` end_dt | `2026-12-01` | `2026-12-31` | Typo production |
| `name_` bulan | `"July,2026"` (tiada space) | `"July, 2026"` | Konsistensi |
| Julat | 2017–2026, 176 baris (tidak lengkap) | 2017–2035, 361 baris | Lengkap |

Production ada 9 baris 2017 dengan `charge_code` kosong, dan 2018–2019 tiada baris HF. Dielakkan dalam skema baru.

---

## 5. Rantaian peraturan penjanaan

Urutan disahkan oleh Kama:

1. **Tentukan mod** — `CURRENT` / `POSTPAID` / `PREPAID` → `basePeriod()`
2. **Semak hari jana** — `sp_billing_setting.invoice_gen_day`
   - Nilai 1–31: invois dijana pada hari tersebut
   - Nilai 99 (legacy): jana manual sahaja → dalam skema baru, `auto_generate = false`
3. **Semak akaun aktif** — `account.status = 'ACTIVE'`
4. **Semak ufuk akaun** — `account.charge_frequency` → berapa kitaran
5. **Senaraikan langganan** — `account_subscription` bagi akaun tersebut
6. **Semak freq produk** — `product.charge_frequency` → aras period
7. **Semak tarikh langganan** — `account_subscription.start_date` / `end_date`
8. **Tentukan harga** — `account_subscription.unit_price ?? product.unit_rate`

### Tarikh berkesan

```
effectiveStart = MAX(account.start_charging, subscription.start_date)
effectiveEnd   = MIN(account.expiry,         subscription.end_date)
```

Dua aras tarikh wujud dan **kedua-duanya mesti dihormati**. Pelanggan boleh melanggan Maintenance dari Januari tetapi Insurance dari Ogos — pada akaun yang sama.

`start_charging` null → tiada had bawah (jana untuk mana-mana period dalam ufuk).

**Bukti:** ujian production dengan `Start Charging` kosong menjana caj untuk Jun 2026 walaupun akaun dicipta 17 Julai 2026. `PeriodResolver.periodsFor()` sedia ada mempunyai semakan yang sama (`afterStart = accountStart == null || !p.isBefore(accountStart)`) — kelakuan padan.

### Harga berkesan

`account_subscription.unit_price` ialah override khusus-akaun. Null → guna `product.unit_rate`.

Komen skema production mengesahkan: *"the account specific price to override standard product price, if any applicable"*.

Diperlukan untuk kes seperti sewa — setiap penyewa bayar kadar berbeza untuk produk yang sama. Contoh production: SEWAAN WISMA MAJU, `unit_rate` RM1.00, `unit_price` RM652.96.

---

## 6. Anchor month

**Anchor = bulan kitaran bermula.** Null → default Januari → kitaran sejajar kalendar.

Terpakai untuk `QUARTERLY`, `HALF_YEAR`, `YEAR`. Tidak bermakna untuk `MONTHLY` (setiap bulan kitaran sendiri).

| Freq | Anchor Februari | Kitaran |
|---|---|---|
| QR | Feb | Feb–Apr, Mei–Jul, Ogos–Okt, Nov–Jan |
| HF | Feb | Feb–Jul, Ogos–Jan |
| YR | Ogos | Ogos 2026 – Julai 2027 |

### `period_id` untuk kitaran terapung

`period_id` = **baldi kalendar tempat kitaran bermula**.

Ini selamat kerana panjang kitaran = panjang baldi kalendar, jadi pemetaan sentiasa 1:1:

- Kitaran QR maju 3 bulan, suku kalendar 3 bulan → tiada dua kitaran dalam suku sama
- Sama untuk HF (6/6) dan YR (12/12)

Liputan sebenar dibawa oleh `line.period_start` / `line.period_end`.

**Kompromi diketahui:** `fi_period.name_` akan tertulis "Q1, 2026" untuk kitaran Feb–Apr. Laporan yang perlukan label tepat mesti baca `period_start`/`period_end`, bukan `fi_period.name_`.

### Titik caj

Dua soalan berbeza, dua jawapan berbeza:

| Soalan | Ditentukan oleh |
|---|---|
| **BILA** dicaj | `effectiveStart = start_charging ?? created_at` |
| **BERAPA** dicaj | `canProrate = start_charging != null && product.prorated` |

Peraturan teras:

```
chargePoint = MAX(cycleStart, effectiveStart)
caj kalau chargePoint jatuh dalam period asas
```

Satu peraturan. Stateless — tiada penunjuk, tiada "sudah dicaj?" tersimpan.

#### Kenapa `start_charging` menentukan proration

`start_charging` ialah pengisytiharan SP: *"pelanggan ini bermula pada tarikh ini."*
Kalau diisi, SP tahu tarikh sebenar dan mahu bil yang tepat.

Kalau kosong, `created_at` digunakan untuk menentukan BILA — tetapi tiada
proration; kitaran berjalan dicaj penuh. Sebab: `created_at` ialah tarikh
kemasukan data, bukan fakta perniagaan. Memprorate berdasarkannya bermakna
mengenakan caj berdasarkan bila kerani menaip.

#### Insurance RM350/tahun

| Kes | Anchor | `start_charging` | Kitaran berjalan | Amaun |
|---|---|---|---|---|
| Normal | Ogos | 15 Jun 2026 | Ogos25–Jul26 | Prorate 47/365 → **RM45.07**, kemudian RM350 penuh pada Ogos |
| Lewat | Ogos | 15 Sep 2026 | Ogos26–Jul27 | Prorate 320/365 → **RM306.85** |
| Kalendar | (null) | 15 Jun 2026 | Jan–Dis 2026 | Prorate 200/365 → **RM191.78** |
| Tiada tarikh | (null) | — (`created_at` 17 Jul) | Jan–Dis 2026 | **RM350 penuh** — tiada proration |

Tiga kes pertama identik dari segi struktur: akaun masuk kitaran yang **sedang
berjalan**, prorate baki. Yang berbeza hanya berapa banyak baki.

Kes keempat: `start_charging` kosong → tiada proration walaupun masuk tengah
kitaran.

> **Nota sejarah.** Versi awal dokumen ini menyatakan kes "Normal" tidak dicaj
> sehingga Ogos, kerana pelanggan "belum dilindungi". Itu salah — ia melayan
> Normal dan Lewat secara berbeza walaupun strukturnya sama. Ujian
> `PeriodResolverTest` mendedahkan percanggahan tersebut. Diselesaikan
> 18 Julai 2026: prorate kitaran berjalan, di kedua-dua kes.

#### PREPAID mengendalikan dirinya

Larian Julai, PREPAID, akaun MONTHLY → `basePeriod()` = Ogos. Kitaran YR anchor
Ogos bermula 1 Ogos, jatuh dalam period asas → caj penuh.

Tiada anjakan tambahan di aras produk. Mod dianjak **sekali**, di aras akaun.

---

## 7. Proration

### Dua mekanisme, penyebut BERBEZA

| Pencetus | Penyebut | Syarat |
|---|---|---|
| Tarikh mula/tamat | **Hari sebenar** kitaran | `start_charging` diisi DAN `product.prorated` |
| Exclude period | **Bilangan bulan** kitaran | Sentiasa |

Bukan pilihan gaya. Exclude prorate ikut bulan: QR RM240 dengan Julai
dikecualikan = 240 ÷ 3 × 2 = **RM160**, bukan 240 × 61/92 = RM159.13.

### Ratio disimpan secara eksplisit

```
amount = ROUND(unit_price × quantity × proration_ratio, 2)
```

`financial_document_line.proration_ratio DECIMAL(9,8)` — V20.

Kuantiti kekal **ASAL**: "2 unit" ialah 2.

Cubaan pertama membakar ratio ke dalam quantity. Gagal: `quantity` ialah
`decimal(15,4)`, jadi ratio dibundar **sebelum** didarab —
240 × 0.6667 = RM160.01, sepatutnya RM160.00. Dan "2 unit" jadi 1.3334.

Ratio **tidak boleh** diterbitkan dari data tersimpan: `cycle_start`/`cycle_end`
tidak disimpan (hanya liputan), dan ratio exclude bergantung pada senarai
exclude pada masa penjanaan. Jadi lajur ini perlu untuk jejak audit.

Legacy menyimpan kuantiti asal dengan amaun diprorate — amaun tidak boleh
dikira semula langsung. Rujuk `legacy-generator-analysis.md` §4.3.

### `product.prorated`

| Nilai | Kelakuan |
|---|---|
| `1` | Masuk tengah kitaran → caj ikut baki hari |
| `0` | Masuk tengah kitaran → **caj penuh** |

Formula: `hari liputan ÷ hari sebenar kitaran`

**Hari sebenar**, bukan andaian 30 tetap. Jun = 30, Julai = 31, Feb = 28/29.

### Proration ialah kes tepi, bukan lalai

**Bukti (production, `mon_sp_prod`, 1,478 produk):**

| `is_prorated` | `term_days` | bil |
|---|---|---|
| N | 0 | **1,425** |
| (null) | 30 | 22 |
| **Y** | 15 | **10** |
| N | 30 | 9 |
| (null) | (null) | 6 |
| N | 365 | 4 |
| **Y** | 0 | **2** |

**12 daripada 1,478 produk (0.8%) menggunakan proration.** 99% caj penuh.

### Exclude prorate tanpa mengira bendera

Exclude ialah keputusan SP (bulan cuti, tempoh percuma), bukan kemasukan
tengah-kitaran. Ia terpakai walaupun `product.prorated = 0`.

```
excludeRatio = (bulan kitaran − bulan dikecualikan) / bulan kitaran
```

Seragam untuk semua aras. Julai dikecualikan:

| Aras | Kiraan | Ratio | Amaun |
|---|---|---|---|
| MO RM80 | (1−1)/1 | 0 | Baris gugur |
| QR RM240 | (3−1)/3 | 0.66666667 | **RM160.00** |
| YR RM350 | (12−1)/12 | 0.91666667 | **RM320.83** |

Tiada semakan exclude di aras akaun diperlukan — baris MO yang gugur bermakna
tiada baris, bermakna tiada invois.

**Divergensi dari legacy:** legacy memadan `period_id` tepat pada aras AKAUN
dan `continue` — melangkau seluruh akaun. Akaun QR/YR tidak pernah terkesan.
Rujuk `legacy-generator-analysis.md` §5.1.

### Kedua-dua ratio boleh bergabung

Akaun mula 15 Jun DAN Julai dikecualikan, produk YR:

```
350 × (200/365) × (11/12) = 175.799 → RM175.80
```

Penyebut berbeza (hari vs bulan), jadi hasil darab adalah **anggaran**.
Diterima secara sedar. Kes jarang.

### Pembundaran: per baris, ke atas

```
raw    = ROUND(rate × qty × ratio, 2)     ← 2 t.p. DAHULU
amount = roundUpToDenom(raw, minDenom)    ← kemudian CEILING ke denominasi
```

Urutan penting: 240 × 0.66666667 = 160.0000008. CEILING terus atas nilai itu
dengan denom 0.05 akan bagi RM160.05.

Legacy membundar **sekali** atas (amaun + cukai). Kita membundar **per baris**,
kemudian cukai dikira atas baris yang sudah dibundar. Divergensi sengaja.

*Belum disemak:* mod pembundaran legacy (`roundCurrency`) tidak diketahui —
kalau ia HALF_UP dan bukan CEILING, migrasi akan menunjukkan beza sen.

### `term_days` bukan penyebut proration

Data menunjukkan `Y` berpasangan dengan `15`, `N` dengan `0`/`30`/`365`. Kalau
`term_days` penyebut proration, produk prorate tidak akan guna 15.

`term_days` = **tempoh bayaran** (due date = tarikh invois + N hari), di aras SP.
Tiada kaitan dengan proration.

Ini bermakna keputusan "hari sebenar bulan" **tidak bercanggah** dengan
production — production tidak guna `term_days` untuk proration langsung.

---

## 8. Idempotency

**Dijaga oleh kekangan DB, bukan semakan aplikasi.**

```sql
idem_key VARCHAR(120) GENERATED ALWAYS AS (
  CASE WHEN active = 1
       THEN CONCAT(account_id, ':', product_id, ':', period_start)
  END
) STORED,
UNIQUE KEY uk_line_idem (idem_key)
```

Tiga sifat:

1. **Tiada lubang race.** Pendekatan `SELECT` dahulu lalu `INSERT` boleh gagal di bawah larian serentak. Kekangan unique tidak boleh.
2. **Batal → boleh jana semula.** `active = 0` → `idem_key` NULL. MySQL benarkan NULL berulang dalam unique index.
3. **`account_id` didenormalkan pada baris** semata-mata untuk ini. Komen skema: *"denormal untuk idempotency"*.

### Kenapa `last_charge` dibuang

Monthley lama menyimpan `mon_acc_subscr.last_charge` (FK ke `fi_period`) — penunjuk boleh-ubah "produk ini sudah dicaj sehingga period X".

Masalah:

- **Punca drift.** Kalau larian gagal separuh jalan atau rollback tidak lengkap, penunjuk tersasar. Caj hilang atau berganda. Ia satu-satunya sumber kebenaran, dan tiada cara mengesan ia salah.
- **Menyekat fleksibiliti.** Penunjuk hanya boleh gerak ke depan. Ciri "user tick period mana nak dicaj" mustahil.

Skema baru: `account_subscription.last_charged_at` **digugurkan** (V17). Soalan "period mana belum dijana?" dijawab dengan bertanya kepada ledger — sumber kebenaran yang sama untuk auto-gen dan pemilihan manual.

---

## 9. Perakaunan bergu

### Invois dikeluarkan

```
Dr  Akaun Belum Terima    350.00      (aset naik — pelanggan berhutang)
    Cr  Pendapatan Servis     350.00  (hasil naik — SP telah usahakan)
```

### Bayaran diterima

```
Dr  Bank                  350.00      (aset naik — tunai masuk)
    Cr  Akaun Belum Terima    350.00  (aset turun — hutang berkurang)
```

Hasil **tidak** disentuh masa bayaran — sudah diiktiraf masa invois. Ini accrual accounting.

### Salah faham biasa

"Invois → tambah duit dalam akaun pelanggan, kurangkan duit dalam akaun SP" — **separuh salah.**

Duit SP tidak berkurang. SP mendapat **hasil**. Tiada balang "duit SP" — yang ada Bank (tunai), AR (hutang orang), Hasil (usaha).

Baki kredit disimpan sebagai negatif dalam production (`cr_acc_amt = -248,262.72`). Tanda itu menandakan **belah**, bukan **kurang**.

### Kenapa tiada `bal_amt` cache

Production menyimpan baki berjalan dalam **setiap baris transaksi**:

| Lajur | Isi | Jenis |
|---|---|---|
| `amt_` | `80.00` | Amaun catatan |
| `dt_acc_amt` | `430 → 510 → 590 → …` | **Baki selepas catatan** |

Snapshot ini dikira masa insert dan dibekukan. Kalau resit dibatalkan di tengah, semua baris selepasnya menjadi salah.

**Ini punca sebenar `bal_amt` drift** yang dikesan lebih awal — bukan sekadar cache di `account`, ia tertanam sehingga aras baris.

Skema baru menyimpan `amount`, `debit_account_id`, `credit_account_id`. Baki diterbitkan dengan `SUM()` atas ledger. Sentiasa betul, tidak boleh drift.

Nota prestasi: kalau `SUM()` menjadi perlahan pada SP besar, penyelesaian ialah materialized view atau snapshot berkala **yang boleh dibina semula** — berbeza dengan cache yang tidak boleh disahkan. Jangan optimize awal.

---

## 10. Pengumpulan invois (split)

Legacy: `mon_sp.set_inv_split_txn` (`Y` / `N` / null)

| Setting | Kelakuan |
|---|---|
| `Y` | Pecah — satu dokumen per caj, `ref_no` berlainan |
| `N` | Cantum — satu dokumen, banyak baris, satu `ref_no` |

**Bukti (dua ujian production, akaun setara):**

| Ujian | Setting | Hasil |
|---|---|---|
| 1 | `Y` | 13 dokumen, 13 `ref_no` (P000002673–P000002685), 1 baris setiap satu |
| 2 | `N` | 1 dokumen `MY0000ZCXM`, 1 `ref_no` (P000002686), 3 baris |

Taburan production: `Y` = 45 SP, `N` = 26, null = 9.

Skema baru menggantikan toggle binari dengan `invoice_grouping`:

| Nilai | Setara legacy |
|---|---|
| `SINGLE` | split = N |
| `BY_PRODUCT` | split = Y |
| `BY_PERIOD` | (baru — tiada setara legacy) |

---

## 11. Jenis dokumen

| Kod | Makna |
|---|---|
| `INV` | Invois |
| `RCP` | Resit |
| `J00` | Jurnal pembukaan / pelarasan |
| `J01` | Contra untuk invois dibatalkan |
| `J02` | Contra untuk resit dibatalkan |
| `JNL` | Jurnal manual |

Pembatalan ialah **pembalikan**, bukan pemadaman. Batal invois → `J01` dengan catatan songsang (`Dr Hasil / Cr AR`). Ledger kekal boleh diaudit.

---

## 12. Soalan terbuka

| # | Soalan | Kesan |
|---|---|---|
| 1 | `charge_1st_mon` dalam `mon_sp_prod` — adakah ini anchor yang tidak siap? | Mungkin partner ada niat asal yang berguna |
| 2 | `parent_subscr_id` — pakej bersarang. Bagaimana caj mengalir dari pakej ke komponen? | Skema `account_subscription` sudah ada medannya, enjin belum tampung |
| 3 | `lic_id`, `lic_ref_no`, `qty_used` — lesen bermeter | Luar skop semasa |
| 4 | `invoice_gen_day` — gate untuk `CURRENT` sahaja, atau semua mod? | Postpaid/prepaid mungkin perlu tunggu hari yang sama |
| 5 | Ujian production 17 Julai meninggalkan 1 akaun ujian + 13 invois sebenar dalam ledger SP `MY000060`. Perlu contra `J01`? | Kebersihan data production |

---

## 13. Rujukan skema

Migrasi berkaitan:

- `V16__fi_period_and_exclude_by_period.sql` — jadual `fi_period` (361 baris, 2017–2035), `invoice_exclude_period` bertukar dari `varchar(7)` 'YYYY-MM' ke `period_id BIGINT`
- `V17__line_period_and_drop_pointer.sql` — `financial_document_line.period_id` + FK, gugurkan `account_subscription.last_charged_at`, gugurkan `product.generation_day`

Nota: `V16` menggunakan jadual sementara dan bukan CTE rekursif — mesti berjalan atas MySQL 9 (dev) dan MariaDB 11 (prod).

Kelas berkaitan:

- `com.monthley.shared.PeriodIds` — penukar `period_id` (fungsi tulen)
- `com.monthley.shared.ChargeFrequency` — `ONE_TIME` / `MONTHLY` / `QUARTERLY` / `HALF_YEAR` / `YEAR` / `PER_USE`
- `com.monthley.billing.internal.PeriodResolver` — `basePeriod()` + `periodsFor()`
- `com.monthley.billing.internal.InvoiceCalculator` — pengiraan baris
- `com.monthley.billing.internal.InvoiceGenerationService` — orchestrator

---

## 14. Log keputusan

| Keputusan | Sebab | Bukti |
|---|---|---|
| Ledger sumber kebenaran, tiada `bal_amt` cache | `dt_acc_amt` snapshot punca drift | §9 |
| `period_id` BIGINT, bukan `YearMonth` | Invois wujud pada aras QR/HF/YR (6,204 dalam prod) | §2 |
| `PeriodIds` fungsi tulen, `fi_period` rujukan sahaja | Data `fi_period` prod tidak konsisten (176 baris, format campur) | §4 |
| Buang `last_charge` | Penunjuk boleh-ubah = punca drift + sekat fleksibiliti | §8 |
| `idem_key` UNIQUE untuk idempotency | Kekangan DB tiada lubang race | §8 |
| Anchor terapung untuk QR/HF/YR | Pemetaan 1:1 dijamin oleh panjang kitaran | §6 |
| Prorate ikut hari sebenar | Prod tidak guna `term_days` sebagai penyebut | §7 |
| `prorated = 0` → caj penuh | Disahkan Kama | §7 |
