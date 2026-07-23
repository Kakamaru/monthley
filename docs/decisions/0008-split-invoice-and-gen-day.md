# ADR 0008 — Split Invoice + Gate Hari Penjanaan

- **Status:** Dicadang
- **Tarikh:** 23 Julai 2026

## Konteks

SP0002 menetapkan split invoice, tetapi invois dijana dengan **satu nombor
dokumen** untuk semua item (bukti: INV000025 mengandungi MAINTENANCE,
INSURANCE dan PRKING sekali). Tetapan disimpan ke `sp_document_setting`,
tetapi enjin bil tidak membacanya.

### Yang SUDAH berfungsi (disemak, bukan diandaikan)

| Perkara | Bukti |
|---|---|
| `anchor_month` | `InvoiceCalculator:84`. INSURANCE anchor=8: larian Julai -> period 2025, larian Ogos -> period 2026 |
| `invoice_gen_mode` | SP0002 = PREPAID; larian 23 Julai menghasilkan invois Ogos |
| Proration mula pertengahan kitaran | `recurringLine` + `effStart/effEnd` mengetip liputan |
| Semakan sudah dijana | `idem_key` UNIQUE pada `financial_document_line` |

Legacy `InvoiceGenerator.java` TIADA logik anchor. Enjin baharu ada.

### Jurang sebenar

| Tetapan | Dibaca enjin? | Kesan |
|---|---|---|
| `split_invoice_by_product` | **TIDAK** | Semua item satu nombor dokumen |
| `invoice_gen_day` | **TIDAK** | Tiada gate hari |

---

## Keputusan

### 1. `split_invoice_by_product` sumber kebenaran; gugurkan `invoice_grouping`

`sp_document_setting` mempunyai DUA lajur untuk satu keputusan:
Bercanggah. Corak sama dengan CASE-002 (baki disimpan empat tempat lalu
menyimpang).

`invoice_grouping` rekaan lebih, tiada padanan legacy. Model legacy — dan
yang difahami SP — ialah **binari**: split atau tidak.

"Grouping" bukan tetapan berasingan; ia sekadar perkataan untuk apa yang
berlaku apabila split = 0 — item dikumpulkan ke satu dokumen dengan satu
jumlah, sementara butiran setiap item kekal dalam baris transaksi.

### 2. Split mempengaruhi BILANGAN DOKUMEN sahaja, bukan baris

Titik paling penting dan paling mudah disalahfaham.

| | split = 0 | split = 1 |
|---|---|---|
| `financial_document` | 1 | N (satu per produk) |
| `financial_document_line` | semua baris | semua baris (diagih) |

Baris transaksi **sentiasa lengkap** dalam kedua-dua kes. Dalam legacy,
`mon_sp_txn` sentiasa mempunyai semua baris tidak kira split; hanya
`mon_sp_fi_doc` berbeza bilangannya.

Implikasi: SUM(baris) kekal sama. Ledger posting per dokumen, jadi N dokumen
menghasilkan N posting — jumlah keseluruhan tidak berubah.

`financial_document.period_id` kekal period **larian** untuk semua dokumen
pecahan (model: dokumen simpan period larian, baris simpan period liputan).
Dokumen dibezakan oleh `doc_no`.

### 3. Gate hari hanya untuk penjanaan AUTO

Terdapat **TIGA pencetus penjanaan**:

| # | Pencetus | Gate hari? | Wujud? |
|---|---|---|---|
| 1 | Auto — tarikh padan `invoice_gen_day` | **Ya** | Belum |
| 2 | Manual pukal — Tools -> Jana Bil | Tidak | Ya |
| 3 | Invois tunggal — dari akaun | Tidak | Ya |

Pencetus 2 dan 3 tindakan disengajakan pentadbir. Kalau tertakluk gate,
pentadbir tidak boleh menjana bila diperlukan.

Gate dilaksanakan bersama penjadual (pencetus 1), bukan sekarang.

---

## Urutan penjanaan (spesifikasi rujukan)

Ditulis daripada penerangan Kama supaya tidak perlu diterangkan semula.

1. **Baca tetapan SP** — `invoice_gen_mode`, `invoice_gen_day`, `split`
2. **Mod menentukan period asas** — POSTPAID −1, CURRENT 0, PREPAID +1
3. **Per produk langganan:**
   - `YEAR` — tengok `anchor_month`. Anchor 8, jana sebelum Ogos -> kitaran
     tahun sebelumnya (belum sampai bulan anchor). Kalau akaun mula caj
     pertengahan kitaran (cth mula 1/1/2026, kitaran Ogos–Ogos) -> **prorate**
   - `MONTHLY` — period = bulan larian
   - `ONE_TIME` — sekali seumur hidup
4. **Semak sudah dijana** untuk period tersebut (idem_key)
5. **Kumpul mengikut split** -> cipta 1 atau N dokumen

## Fasa pelaksanaan

| Fasa | Kandungan |
|---|---|
| P1 | Migration: gugurkan `invoice_grouping` |
| P2 | `BillingSettingsPort` dedahkan `splitInvoiceByProduct` |
| P3 | `createAndPost` terima kumpulan; gelung cipta N dokumen |
| P4 | Ujian: split=0 satu dokumen, split=1 N dokumen, SUM baris kekal |

Gate hari: bersama penjadual, bukan skop ADR ini.

## Rujukan
- `domain/legacy-generator-analysis.md`
- `evidence/CASE-002-amt_actv-scenario-catalog.md` (corak lajur berganda)
